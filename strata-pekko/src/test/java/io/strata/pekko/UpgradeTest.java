package io.strata.pekko;

import com.typesafe.config.ConfigFactory;
import io.strata.EntityType;
import io.strata.Reply;
import io.strata.Services;
import io.strata.Strata;
import io.strata.StrataException;
import io.strata.StrataRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/** Two releases of the same entity type over one store: migrations, refusing newer state, stalling on divergence. */
class UpgradeTest {
    @TempDir Path dir;

    StrataRuntime start(Services services, Map<String, Object> extra, EntityType<?>... types) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
                "strata.entity.discover", false,
                "strata.pekko.system.pekko.remote.artery.canonical.port", 0,
                "strata.store.kind", "jdbc",
                "strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db")));
        cfg.putAll(extra);
        StrataRuntime s = Strata.start(ConfigFactory.parseMap(cfg).withFallback(ConfigFactory.load()).resolve(), services);
        for (EntityType<?> t : types) s.register(t);
        return s;
    }

    static <T> T await(CompletionStage<T> s) throws Exception { return s.toCompletableFuture().get(30, TimeUnit.SECONDS); }

    // Release 1 stored whole units; release 2 stores cents.
    public static final class PurseV1 { public long balance; }
    public static final class PurseV2 { public long cents; }

    static final EntityType<PurseV1> PURSE_V1 = EntityType.builder("purse", PurseV1.class).initial(PurseV1::new).mutableState()
            .command("add", Long.class, (s, n) -> { s.balance += n; return Reply.update(s, s.balance); })
            .query("balance", s -> s.balance).build();

    static final EntityType<PurseV2> PURSE_V2 = EntityType.builder("purse", PurseV2.class).initial(PurseV2::new).mutableState()
            .version(2)
            .migrate(1, v1 -> { v1.put("cents", ((Number) v1.remove("balance")).longValue() * 100); return v1; })
            .command("addCents", Long.class, (s, n) -> { s.cents += n; return Reply.update(s, s.cents); })
            .query("cents", s -> s.cents).build();

    @Test
    void olderStateIsMigratedAndOlderCodeRefusesNewerState() throws Exception {
        try (var r1 = start(Services.NONE, Map.of(), PURSE_V1)) {
            assertEquals(5L, await(r1.ref(PURSE_V1, "p").call("add", 5L, Long.class)));
        }
        try (var r2 = start(Services.NONE, Map.of(), PURSE_V2)) {
            assertEquals(500L, await(r2.ref(PURSE_V2, "p").call("cents", Long.class)), "5 units became 500 cents");
            assertEquals(550L, await(r2.ref(PURSE_V2, "p").call("addCents", 50L, Long.class)));
            String stored = new String(r2.introspection().store().load("purse", "p").toCompletableFuture().get().orElseThrow().state());
            assertTrue(stored.contains("\"$v\":2") && stored.contains("\"cents\":550"), stored);
        }
        try (var old = start(Services.NONE, Map.of(), PURSE_V1)) {
            var e = assertThrows(ExecutionException.class, () -> await(old.ref(PURSE_V1, "p").call("balance", Long.class)));
            assertTrue(e.getCause().getMessage().contains("written by a newer release"), e.getCause().getMessage());
        }
    }

    // A job that calls two steps; release 2 calls them in another order.
    public interface Steps { String a(); String b(); }
    public static final class Job { public int done; transient Steps steps; }

    static EntityType<Job> job(boolean reordered) {
        return EntityType.builder("job", Job.class).initial(Job::new).mutableState()
                .inject(services -> { Steps st = EntityType.require(services, Steps.class, "", "job", "steps"); return j -> j.steps = st; })
                .command("run", j -> {
                    if (reordered) { j.steps.b(); j.steps.a(); } else { j.steps.a(); j.steps.b(); }
                    j.done++;
                    return Reply.update(j, j.done);
                })
                .query("done", j -> j.done).build();
    }

    static Services steps(Runnable onB) {
        return Services.builder().add(Steps.class, new Steps() {
            public String a() { return "a"; }
            public String b() { onB.run(); return "b"; }
        }).build();
    }

    @Test
    void resumingWithChangedCodeStallsByDefaultAndCanBeAbandoned() throws Exception {
        CountDownLatch inB = new CountDownLatch(1);
        var r1 = start(steps(() -> { inB.countDown(); while (true) LockSupport.park(); }), Map.of(), job(false));
        r1.ref(job(false), "j1"); // (same name; the registered type is what runs)
        r1.ref(r1.types().iterator().next(), "j1").call("run");
        assertTrue(inB.await(30, TimeUnit.SECONDS), "a recorded, crashed inside b");
        r1.close();

        // Release 2 reordered the calls: replay can't follow the record, so the entity stalls.
        try (var r2 = start(steps(() -> { }), Map.of(), job(true))) {
            var type = r2.types().iterator().next();
            var e = assertThrows(ExecutionException.class, () -> await(r2.ref(type, "j1").call("done")));
            assertEquals(StrataException.Kind.UNAVAILABLE, ((StrataException) e.getCause()).kind());
            assertTrue(e.getCause().getMessage().contains("stalled") && e.getCause().getMessage().contains("diverged"), e.getCause().getMessage());
            assertTrue(r2.introspection().store().pending("job", "j1").toCompletableFuture().get().isPresent(), "the record is kept");
            var kinds = r2.introspection().observability().events().since(0, 100, ev -> true).stream().map(io.strata.management.Event::kind).toList();
            assertTrue(kinds.contains("stalled"), kinds.toString());
        }

        // Told to abandon: the interrupted command is dropped and the entity carries on.
        try (var r3 = start(steps(() -> { }), Map.of("strata.durability.on-divergence", "abandon"), job(true))) {
            var type = r3.types().iterator().next();
            assertEquals(0, ((Number) await(r3.ref(type, "j1").call("done"))).intValue(), "the interrupted run didn't count");
            assertTrue(r3.introspection().store().pending("job", "j1").toCompletableFuture().get().isEmpty());
            assertEquals(1, ((Number) await(r3.ref(type, "j1").call("run"))).intValue(), "and new commands work");
        }
    }
}
