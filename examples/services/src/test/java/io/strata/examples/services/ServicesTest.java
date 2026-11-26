package io.strata.examples.services;

import com.typesafe.config.ConfigFactory;
import io.strata.Services;
import io.strata.Strata;
import io.strata.StrataException;
import io.strata.StrataRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ServicesTest {
    @TempDir Path dir;
    final AtomicInteger priced = new AtomicInteger();

    Services services() {
        return Services.builder()
                .add(Pricing.class, item -> { priced.incrementAndGet(); return item.length() * 100L; })
                .add("discounts", Pricing.class, item -> 10L)
                .add(Payments.class, (order, cents) -> "R")      // Order (same module) needs these
                .add(Mailer.class, text -> { })
                .build();
    }

    StrataRuntime start(Services services, Map<String, Object> extra) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
                "strata.pekko.system.pekko.remote.artery.canonical.port", 0,
                "strata.store.kind", "jdbc",
                "strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db")));
        cfg.putAll(extra);
        return Strata.start(ConfigFactory.parseMap(cfg).withFallback(ConfigFactory.load()).resolve(), services);
    }

    static <T> T await(java.util.concurrent.CompletionStage<T> s) throws Exception { return s.toCompletableFuture().get(); }

    @Test
    void entitiesUseServicesAndOnlyTheirStateIsStored() throws Exception {
        try (var strata = start(services(), Map.of())) {
            var b = BasketClient.of(strata, "b1");
            assertEquals(490L, await(b.add("apple")));           // 500 - 10, injected services on every (copied) instance
            assertEquals(1080L, await(b.add("banana")));
            assertEquals(2, priced.get());
            var stored = strata.introspection().store().load("basket", "b1").toCompletableFuture().get().orElseThrow();
            String json = new String(stored.state());
            assertTrue(json.contains("\"items\"") && json.contains("\"total\""), json);
            assertFalse(json.contains("pricing") || json.contains("discounts"), "services are never stored: " + json);
        }
        // After a restart the entity reloads from the store and gets its services again.
        try (var strata = start(services(), Map.of())) {
            assertEquals(1670L, await(BasketClient.of(strata, "b1").add("cherry")));     // + 6*100 - 10
            assertEquals(List.of("apple", "banana", "cherry"), await(BasketClient.of(strata, "b1").items()));
        }
    }

    @Test
    void aMissingServiceFailsAtStartupNamingWhatIsMissing() {
        var e = assertThrows(StrataException.class, () -> start(Services.builder().add(Pricing.class, i -> 1L)
                .add(Payments.class, (o, c) -> "R").add(Mailer.class, t -> { }).build(), Map.of()));
        assertTrue(e.getMessage().contains("basket") && e.getMessage().contains("discounts") && e.getMessage().contains("Pricing"), e.getMessage());
    }

    @Test
    void reflectionInjectsTheSameWay() throws Exception {
        // As for Scala classes: built by reflection from strata.entity.classes.
        try (var strata = start(services(), Map.of("strata.entity.discover", false, "strata.entity.classes", List.of(Basket.class.getName())))) {
            var type = strata.types().stream().filter(t -> t.name().equals("basket")).findFirst().orElseThrow();
            assertNotSame(BasketEntity.TYPE, type);
            assertEquals(490L, await(strata.ref(type, "r1").call("add", "apple", Long.class)));
        }
    }
}
