package io.strata.examples.scala;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Scala counter runs on the real Pekko runtime with Strata's default config. */
class ScalaInteropTest {
    static Config config() {
        return ConfigFactory.parseString("strata.pekko.system.pekko.remote.artery.canonical.port = 0")
                .withFallback(ConfigFactory.load()).resolve();
    }

    @Test
    void countsWithTheSameApi() {
        long count = Counter.demo(config(), "s", 3);
        assertEquals(3L, count);
    }
}
