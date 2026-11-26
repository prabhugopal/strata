package io.strata.examples.services;

import com.typesafe.config.ConfigFactory;
import io.strata.Services;
import io.strata.Strata;
import io.strata.StrataException;
import io.strata.StrataRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crashes in the middle of a command. A node is "crashed" while a service call hangs: it's shut
 * down and the hung thread never continues (as when a process dies). The next node resumes.
 */
class DurableInvocationTest {
    @TempDir Path dir;

    final AtomicInteger charges = new AtomicInteger();
    final List<String> mails = new CopyOnWriteArrayList<>();
    volatile boolean decline;

    Payments payments() {
        return (order, cents) -> {
            charges.incrementAndGet();
            if (decline) throw new Payments.PaymentDeclined("card declined");
            return "R-" + cents + "-" + charges.get();
        };
    }

    /** A mailer whose first call never returns (and ignores interrupts): the node "dies" there. */
    static Mailer hangingMailer(CountDownLatch reached) {
        return text -> {
            reached.countDown();
            while (true) LockSupport.park();
        };
    }

    StrataRuntime start(Mailer mailer) {
        return start(mailer, text -> { });
    }

    StrataRuntime start(Mailer mailer, Notifier notifier) {
        return Strata.start(ConfigFactory.parseMap(Map.of(
                        "strata.pekko.system.pekko.remote.artery.canonical.port", 0,
                        "strata.store.kind", "jdbc",
                        "strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db")))
                .withFallback(ConfigFactory.load()).resolve(),
                Services.builder().add(Payments.class, payments()).add(Mailer.class, mailer).add(Notifier.class, notifier)
                        .add(Pricing.class, i -> 1L).add("discounts", Pricing.class, i -> 0L).build());
    }

    static <T> T await(java.util.concurrent.CompletionStage<T> s) throws Exception { return s.toCompletableFuture().get(30, TimeUnit.SECONDS); }

    @Test
    void aCrashBetweenTwoEffectsResumesWithoutRepeatingTheFirst() throws Exception {
        CountDownLatch inMailer = new CountDownLatch(1);
        StrataRuntime a = start(hangingMailer(inMailer));
        CompletableFuture<String> caller = OrderClient.of(a, "o1").checkout(4200).toCompletableFuture();
        assertTrue(inMailer.await(30, TimeUnit.SECONDS), "the command reached the mailer");
        assertEquals(1, charges.get(), "charged once before the crash");
        a.close(); // the node goes away mid-command; the hung mailer call never finishes
        java.time.Instant crashed = java.time.Instant.now();

        try (StrataRuntime b = start(mails::add)) {
            var order = OrderClient.of(b, "o1");
            // Using the entity loads it, and it finishes the interrupted checkout first.
            assertEquals("paid", await(order.status()));
            assertEquals("R-4200-1", await(order.receipt()), "the recorded charge, not a new one");
            assertEquals(1, charges.get(), "the card was charged exactly once");
            assertEquals(List.of("Thanks! Receipt R-4200-1"), mails, "the email went out once, after resuming");
            assertTrue(await(order.paidAt()).isBefore(crashed), "ctx.now() was recorded before the crash and replayed, not read again");
            assertTrue(b.introspection().store().pending("order", "o1").toCompletableFuture().get().isEmpty(), "nothing left unfinished");
        }
        assertTrue(caller.isCompletedExceptionally() || !caller.isDone(), "the original caller never got a success from the dead node");
    }

    @Test
    void aRecordedExceptionReplaysSoTheSameBranchRuns() throws Exception {
        decline = true;
        CountDownLatch inMailer = new CountDownLatch(1);
        StrataRuntime a = start(hangingMailer(inMailer));
        OrderClient.of(a, "o2").checkoutOrWait(100);
        assertTrue(inMailer.await(30, TimeUnit.SECONDS));
        a.close();

        decline = false; // the card would work now, but the recorded decline must replay
        try (StrataRuntime b = start(mails::add)) {
            assertEquals("waiting for a card (card declined)", await(OrderClient.of(b, "o2").status()));
            assertEquals(1, charges.get(), "the decline was replayed, not retried");
            assertEquals(List.of("Order waiting for a card (card declined)"), mails);
        }
    }

    @Test
    void idempotencyKeysAnswerRetriesWithTheFirstOutcome() throws Exception {
        try (StrataRuntime s = start(mails::add)) {
            var order = OrderClient.of(s, "o3").withIdempotencyKey("checkout-o3-1");
            String first = await(order.checkout(500));
            assertEquals(first, await(order.checkout(500)), "a retry gets the same receipt");
            assertEquals(1, charges.get());
            assertEquals(1, mails.size());

            // A failure is kept too: retrying a declined checkout doesn't charge again.
            decline = true;
            var other = OrderClient.of(s, "o4").withIdempotencyKey("checkout-o4-1");
            var e1 = assertThrows(ExecutionException.class, () -> await(other.checkout(700)));
            decline = false;
            var e2 = assertThrows(ExecutionException.class, () -> await(other.checkout(700)));
            assertEquals(e1.getCause().getMessage(), e2.getCause().getMessage());
            assertEquals(StrataException.Kind.FAILED, ((StrataException) e2.getCause()).kind());
            assertEquals(2, charges.get(), "o4 was charged (declined) once, not retried");

            // A new key is a new request.
            assertNotEquals(first, await(OrderClient.of(s, "o3").withIdempotencyKey("checkout-o3-2").checkout(500)));
        }
    }

    /** A notifier whose first call never returns. */
    static Notifier hangingNotifier(CountDownLatch reached) {
        return text -> { reached.countDown(); while (true) LockSupport.park(); };
    }

    @Test
    void aTransferInterruptedBetweenTwoWalletsFinishesWithEachWalletChangedOnce() throws Exception {
        CountDownLatch between = new CountDownLatch(1);
        StrataRuntime a = start(mails::add, hangingNotifier(between));
        assertEquals(1000L, await(WalletClient.of(a, "w-alice").deposit(1000)));
        TransferClient.of(a, "t1").run(new Transfer.Request("w-alice", "w-bob", 300));
        assertTrue(between.await(30, TimeUnit.SECONDS), "withdrawn, not yet deposited");
        assertEquals(700L, await(WalletClient.of(a, "w-alice").balance()));
        a.close();

        try (StrataRuntime b = start(mails::add)) {
            assertTrue(await(TransferClient.of(b, "t1").status()).startsWith("done t1 at "), "the transfer resumed and finished");
            assertEquals(700L, await(WalletClient.of(b, "w-alice").balance()), "withdrawn exactly once");
            assertEquals(300L, await(WalletClient.of(b, "w-bob").balance()), "deposited exactly once");
        }
    }

    @Test
    void ctxGivesTimeIdAndRandomnessAndCallsAreChecked() throws Exception {
        try (StrataRuntime s = start(mails::add)) {
            java.time.Instant before = java.time.Instant.now();
            assertEquals("stamp/s1", await(StampClient.of(s, "s1").stamp()));
            assertFalse(await(StampClient.of(s, "s1").at()).isBefore(before));

            var self = assertThrows(ExecutionException.class, () -> await(TransferClient.of(s, "t9").askMyself()));
            assertTrue(self.getCause().getMessage().contains("can't call itself"), self.getCause().getMessage());

            // A failing call to another entity fails the calling command, and changes nothing.
            var poor = assertThrows(ExecutionException.class, () -> await(TransferClient.of(s, "t2").run(new Transfer.Request("w-empty", "w-x", 5))));
            assertTrue(poor.getCause().getMessage().contains("insufficient funds"), poor.getCause().getMessage());
            assertEquals("new", await(TransferClient.of(s, "t2").status()));
            assertEquals(0L, await(WalletClient.of(s, "w-x").balance()));
        }
    }

    @Test
    void commandsWithServicesDontBlockOtherEntities() throws Exception {
        CountDownLatch inMailer = new CountDownLatch(1);
        AtomicBoolean release = new AtomicBoolean();
        Mailer slow = text -> { inMailer.countDown(); while (!release.get()) LockSupport.parkNanos(1_000_000); mails.add(text); };
        try (StrataRuntime s = start(slow)) {
            var stuck = OrderClient.of(s, "slow").checkout(1).toCompletableFuture();
            assertTrue(inMailer.await(30, TimeUnit.SECONDS));
            // The slow call runs on its own virtual thread: other entities keep working meanwhile.
            for (int i = 0; i < 20; i++) assertEquals("new", await(OrderClient.of(s, "other-" + i).status()));
            release.set(true);
            assertTrue(await(stuck).startsWith("R-1-"));
        }
    }
}
