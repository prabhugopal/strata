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

/** strata.entity.passivate-after is the one idle setting; Pekko's own idle passivation is off. */
class PassivationTest {
    @TempDir Path dir;

    Config config(String passivateAfter) {
        return ConfigFactory.parseString("strata.pekko.system.pekko.remote.artery.canonical.port = 0\n"
                        + "strata.store.jdbc.url = \"jdbc:h2:file:" + dir.resolve("db") + "\"\n"
                        + "strata.entity.passivate-after = " + passivateAfter)
                .withFallback(ConfigFactory.load()).resolve();
    }

    @Test
    void pekkosOwnIdlePassivationIsOff() {
        Config system = PekkoSettings.from(ConfigFactory.load()).systemConfig();
        assertEquals("off", system.getString(PekkoSettings.IDLE_PASSIVATION));
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
    void aLongerSettingKeepsThemLoaded() throws Exception {
        var type = PekkoRuntimeTest.counter(Durability.PERSISTENT);
        try (StrataRuntime rt = Strata.start(config("1h"))) {
            rt.register(type);
            PekkoRuntimeTest.call(rt, type, "a", "increment", null);
            Thread.sleep(3000);
            assertEquals(1, inMemory(rt));
        }
    }
}
