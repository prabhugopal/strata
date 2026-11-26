package io.strata.examples.services;

import com.typesafe.config.ConfigFactory;
import io.strata.Services;
import io.strata.Strata;
import io.strata.StrataRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class TimerTest {
    @TempDir Path dir;

    StrataRuntime start(Map<String, Object> extra) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
                "strata.pekko.system.pekko.remote.artery.canonical.port", 0,
                "strata.store.kind", "jdbc",
                "strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db") + ";AUTO_SERVER=FALSE",
                "strata.timers.poll-interval", "100ms"));
        cfg.putAll(extra);
        return Strata.start(ConfigFactory.parseMap(cfg).withFallback(ConfigFactory.load()).resolve(), Services.builder()
                .add(Payments.class, (o, c) -> "R").add(Mailer.class, t -> { }).add(Notifier.class, t -> { })
                .add(Pricing.class, i -> 1L).add("discounts", Pricing.class, i -> 0L).build());
    }

    static <T> T await(CompletionStage<T> s) throws Exception { return s.toCompletableFuture().get(30, TimeUnit.SECONDS); }

    static void eventually(String what, Supplier<Boolean> check) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!check.get()) {
            if (System.nanoTime() > deadline) fail("timed out waiting: " + what);
            Thread.sleep(100);
        }
    }

    static String status(StrataRuntime s, String id) {
        try { return await(ApprovalClient.of(s, id).status()); } catch (Exception e) { return e.toString(); }
    }

    @Test
    void aTimerFiresByItselfAndCancellingStopsIt() throws Exception {
        try (var s = start(Map.of())) {
            await(ApprovalClient.of(s, "late").request(300));
            await(ApprovalClient.of(s, "quick").request(300));
            assertEquals(1, s.introspection().store().timers("approval", "quick", 10).toCompletableFuture().get().size());
            assertEquals("approved", await(ApprovalClient.of(s, "quick").approve()));     // a signal: just a command
            assertTrue(s.introspection().store().timers("approval", "quick", 10).toCompletableFuture().get().isEmpty(), "approve cancelled the timer");

            eventually("late expires", () -> status(s, "late").equals("expired"));
            Thread.sleep(800);
            assertEquals("approved", status(s, "quick"), "the cancelled timer never fired");
            assertTrue(s.introspection().store().timers("approval", null, 10).toCompletableFuture().get().isEmpty(), "fired timers are gone");
        }
    }

    @Test
    void aFailedCommandLeavesNoTimer() throws Exception {
        try (var s = start(Map.of())) {
            assertThrows(Exception.class, () -> await(ApprovalClient.of(s, "f").requestAndFail(100)));
            assertTrue(s.introspection().store().timers("approval", "f", 10).toCompletableFuture().get().isEmpty());
        }
    }

    @Test
    void aTimerSurvivesItsNodeStopping() throws Exception {
        var a = start(Map.of("strata.timers.enabled", false)); // this node never delivers
        await(ApprovalClient.of(a, "r1").request(500));
        a.close();
        try (var b = start(Map.of())) {
            eventually("the timer set on the first node fires on the second", () -> status(b, "r1").equals("expired"));
        }
    }

    @Test
    void twoNodesDeliverEachTimerExactlyOnce() throws Exception {
        int pa, pb;
        try (var x = new ServerSocket(0); var y = new ServerSocket(0)) { pa = x.getLocalPort(); pb = y.getLocalPort(); }
        Map<String, Object> cluster = Map.of("strata.pekko.cluster-mode", "seed-nodes", "strata.pekko.seed-nodes", "pekko://strata@127.0.0.1:" + pa);
        var startA = java.util.concurrent.CompletableFuture.supplyAsync(() -> start(with(cluster, pa)));
        var startB = java.util.concurrent.CompletableFuture.supplyAsync(() -> start(with(cluster, pb)));
        try (var a = startA.get(60, TimeUnit.SECONDS); var b = startB.get(60, TimeUnit.SECONDS)) {
            for (int i = 0; i < 20; i++) {
                (i % 2 == 0 ? a : b).schedule(WalletEntity.TYPE, "w-" + (i % 4), Duration.ofMillis(300), "deposit", 1L);
            }
            eventually("all 20 deposits", () -> total(a) == 20);
            Thread.sleep(1500);
            assertEquals(20, total(a), "no timer delivered twice, though both nodes poll");
        }
    }

    static Map<String, Object> with(Map<String, Object> base, int port) {
        Map<String, Object> m = new HashMap<>(base);
        m.put("strata.pekko.system.pekko.remote.artery.canonical.port", port);
        return m;
    }

    static long total(StrataRuntime s) {
        long sum = 0;
        for (int w = 0; w < 4; w++) {
            try { sum += await(WalletClient.of(s, "w-" + w).balance()); } catch (Exception e) { return -1; }
        }
        return sum;
    }
}
