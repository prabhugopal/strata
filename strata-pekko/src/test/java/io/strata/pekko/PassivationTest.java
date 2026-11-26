package io.strata.pekko;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.strata.Durability;
import io.strata.Strata;
import io.strata.StrataRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PassivationTest {
    static final String KEY = "pekko.cluster.sharding.passivation.default-idle-strategy.idle-entity.timeout";
    @TempDir Path dir;

    Config config(String idle) {
        return ConfigFactory.parseString("strata.pekko.system.pekko.remote.artery.canonical.port = 0\n"
                        + "strata.store.jdbc.url = \"jdbc:h2:file:" + dir.resolve("db") + "\"\n"
                        + "strata.pekko.passivation.idle-timeout = " + idle)
                .withFallback(ConfigFactory.load()).resolve();
    }

    static Config passivation(String v) {
        return PekkoSettings.passivation(ConfigFactory.parseMap(Map.of("idle-timeout", v)));
    }

    @Test
    void theSettingBecomesPekkosIdleTimeout() {
        assertEquals(Duration.ofSeconds(30), passivation("30s").getDuration(KEY));
        assertEquals("off", passivation("off").getString(KEY));
        assertEquals(Duration.ofMinutes(2), PekkoSettings.passivation(ConfigFactory.load().getConfig("strata.pekko.passivation")).getDuration(KEY),
                "the default stays Pekko's 2 minutes");
        var e = assertThrows(IllegalArgumentException.class, () -> passivation("soon"));
        assertTrue(e.getMessage().contains("strata.pekko.passivation.idle-timeout"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> passivation("0s"));
    }

    static int inMemory(StrataRuntime rt) {
        return rt.introspection().cluster().toCompletableFuture().join().entitiesByTypeAndNode()
                .getOrDefault("counter", Map.of()).values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void idleEntitiesAreUnloadedAndComeBackWithTheirState() throws Exception {
        var type = PekkoRuntimeTest.counter(Durability.PERSISTENT);
        try (StrataRuntime rt = Strata.start(config("1s"))) {
            rt.register(type);
            assertEquals(5, PekkoRuntimeTest.call(rt, type, "a", "add", 5L));
            assertEquals(1, inMemory(rt));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (inMemory(rt) > 0 && System.nanoTime() < deadline) Thread.sleep(200);
            assertEquals(0, inMemory(rt), "unloaded after 1 s idle");
            assertEquals(5, PekkoRuntimeTest.call(rt, type, "a", "get", null), "state loaded again from the store");
        }
    }

    @Test
    void offKeepsThemLoaded() throws Exception {
        var type = PekkoRuntimeTest.counter(Durability.PERSISTENT);
        try (StrataRuntime rt = Strata.start(config("off"))) {
            rt.register(type);
            PekkoRuntimeTest.call(rt, type, "a", "increment", null);
            Thread.sleep(3000);
            assertEquals(1, inMemory(rt));
        }
    }
}
