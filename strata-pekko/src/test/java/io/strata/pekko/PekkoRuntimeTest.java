package io.strata.pekko;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.strata.Durability;
import io.strata.EntityType;
import io.strata.Reply;
import io.strata.Strata;
import io.strata.StrataException;
import io.strata.StrataRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PekkoRuntimeTest {
    @TempDir Path dir;

    static EntityType<Long> counter(Durability d) {
        return EntityType.builder("counter", Long.class)
                .initial(() -> 0L)
                .durability(d)
                .command("increment", s -> Reply.update(s + 1, s + 1))
                .command("add", Long.class, (s, n) -> {
                    if (n < 0) throw new IllegalArgumentException("can't add a negative number");
                    return Reply.update(s + n, s + n);
                })
                .query("get", s -> s)
                .build();
    }

    /** Every test node gets a free port and its own database; everything else is reference.conf. */
    Config config(String extra) {
        return ConfigFactory.parseString("strata.pekko.system.pekko.remote.artery.canonical.port = 0\n"
                        + "strata.store.jdbc.url = \"jdbc:h2:file:" + dir.resolve("db") + "\"\n" + extra)
                .withFallback(ConfigFactory.load()).resolve();
    }

    static long call(StrataRuntime rt, EntityType<Long> t, String id, String cmd, Object arg) {
        return rt.ref(t, id).call(cmd, arg, Long.class).toCompletableFuture().join();
    }

    @Test
    void entitiesAreIsolatedAndCommandsApplyOneAtATime() {
        var type = counter(Durability.PERSISTENT);
        try (StrataRuntime rt = Strata.start(config(""))) {
            assertInstanceOf(PekkoStrataRuntime.class, rt);
            rt.register(type);
            assertEquals(1, call(rt, type, "alice", "increment", null));
            assertEquals(11, call(rt, type, "alice", "add", 10L));
            assertEquals(0, call(rt, type, "bob", "get", null));

            List<CompletableFuture<Long>> calls = IntStream.range(0, 200)
                    .mapToObj(i -> rt.ref(type, "hot").call("increment", Long.class).toCompletableFuture()).toList();
            calls.forEach(CompletableFuture::join);
            assertEquals(200, call(rt, type, "hot", "get", null), "200 concurrent increments on one entity must all apply");
        }
    }

    @Test
    void persistentStateSurvivesRestartAndPassivation() {
        var type = counter(Durability.PERSISTENT);
        String cfg = "strata.store.kind = jdbc\nstrata.entity.passivate-after = 300ms";
        try (StrataRuntime rt = Strata.start(config(cfg))) {
            rt.register(type);
            call(rt, type, "alice", "add", 41L);
            sleep(900); // passivated by now; the next call reloads from the store
            assertEquals(42, call(rt, type, "alice", "increment", null));
        }
        try (StrataRuntime rt = Strata.start(config(cfg))) {
            rt.register(type);
            assertEquals(42, call(rt, type, "alice", "get", null), "a new node reads the committed state");
            assertEquals(42L, rt.ref(type, "alice").state().toCompletableFuture().join());
        }
    }

    @Test
    void ephemeralStateDoesNotSurviveRestart() {
        var type = counter(Durability.EPHEMERAL);
        String cfg = "strata.store.kind = jdbc";
        try (StrataRuntime rt = Strata.start(config(cfg))) {
            rt.register(type);
            assertEquals(5, call(rt, type, "e", "add", 5L));
        }
        try (StrataRuntime rt = Strata.start(config(cfg))) {
            rt.register(type);
            assertEquals(0, call(rt, type, "e", "get", null));
        }
    }

    @Test
    void handlerErrorsAndUnknownCommandsReachTheCallerAndLeaveStateUnchanged() {
        var type = counter(Durability.PERSISTENT);
        try (StrataRuntime rt = Strata.start(config(""))) {
            rt.register(type);
            call(rt, type, "a", "add", 3L);
            var neg = assertThrows(CompletionException.class, () -> call(rt, type, "a", "add", -1L));
            assertInstanceOf(StrataException.class, neg.getCause());
            assertTrue(neg.getCause().getMessage().contains("can't add a negative number"));
            var unknown = assertThrows(CompletionException.class, () -> call(rt, type, "a", "reset", null));
            assertTrue(unknown.getCause().getMessage().contains("has no command \"reset\""));
            assertEquals(3, call(rt, type, "a", "get", null));
        }
    }

    @Test
    void badConfigFailsFast() {
        assertThrows(IllegalArgumentException.class, () -> Strata.start(config("strata.pekko.cluster-mode = mesh")));
        assertThrows(StrataException.class, () -> Strata.start(config("strata.runtime.backend = temporal")));
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
