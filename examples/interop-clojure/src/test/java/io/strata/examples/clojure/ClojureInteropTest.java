package io.strata.examples.clojure;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import clojure.java.api.Clojure;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The Clojure counter runs on the real Pekko runtime with Strata's default config. */
class ClojureInteropTest {
    static Config config() {
        return ConfigFactory.parseString("strata.pekko.system.pekko.remote.artery.canonical.port = 0")
                .withFallback(ConfigFactory.load()).resolve();
    }

    @Test
    void countsWithTheSameApi() {
        Clojure.var("clojure.core", "require").invoke(Clojure.read("io.strata.examples.counter"));
        long count = (Long) Clojure.var("io.strata.examples.counter", "demo").invoke(config(), "c", 3);
        assertEquals(3L, count);
    }
}
