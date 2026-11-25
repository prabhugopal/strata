package io.strata.pekko;

import com.typesafe.config.ConfigFactory;
import io.strata.Strata;
import io.strata.StrataException;
import io.strata.StrataRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

class AnnotatedEntityTest {

    static StrataRuntime start(Map<String, Object> overrides) {
        return Strata.start(ConfigFactory.parseMap(overrides)
                .withFallback(ConfigFactory.parseMap(Map.of("strata.pekko.system.pekko.remote.artery.canonical.port", 0)))
                .withFallback(ConfigFactory.load()).resolve());
    }

    static <T> T await(CompletionStage<T> s) throws Exception { return s.toCompletableFuture().get(); }

    @Test
    void generatedTypeIsDiscoveredAndTheClientIsTyped() throws Exception {
        try (var strata = start(Map.of())) {
            assertTrue(strata.types().contains(CartEntity.TYPE), "discovered via ServiceLoader");
            assertEquals("cart", CartEntity.TYPE.name());

            var cart = CartClient.of(strata, "c1");
            assertEquals(2, await(cart.add("apple", 2)));            // several parameters
            assertEquals(5, await(cart.add("apple", 3)));
            await(cart.addAll(List.of("pear", "fig")));               // generic parameter
            assertEquals(List.of("apple", "pear", "fig"), await(cart.items())); // generic result
            assertEquals(5, await(cart.quantity("apple")));           // query with an argument
            await(cart.note("leave at door"));                        // void, renamed command
            assertEquals("leave at door", await(cart.currentNote()));
            assertEquals(3, await(cart.snapshot()).items().size());
        }
    }

    @Test
    void aFailingCommandLeavesCommittedStateAlone() throws Exception {
        try (var strata = start(Map.of())) {
            var cart = CartClient.of(strata, "c2");
            await(cart.add("apple", 1));
            var failed = assertThrows(ExecutionException.class, () -> await(cart.addThenFail("caviar")));
            assertEquals(StrataException.Kind.FAILED, ((StrataException) failed.getCause()).kind());
            assertTrue(failed.getCause().getMessage().contains("payment declined"));
            assertEquals(List.of("apple"), await(cart.items()), "the in-place change was rolled back");

            var bad = assertThrows(ExecutionException.class, () -> await(cart.add("apple", -1)));
            assertEquals(StrataException.Kind.FAILED, ((StrataException) bad.getCause()).kind());
            assertEquals(1, await(cart.quantity("apple")));

            var missing = assertThrows(ExecutionException.class, () -> await(strata.ref(CartEntity.TYPE, "c2").call("quantity")));
            assertEquals(StrataException.Kind.INVALID_ARGUMENT, ((StrataException) missing.getCause()).kind());
            var unknown = assertThrows(ExecutionException.class, () -> await(strata.ref(CartEntity.TYPE, "c2").call("nope")));
            assertEquals(StrataException.Kind.NOT_FOUND, ((StrataException) unknown.getCause()).kind());
        }
    }

    @Test
    void annotatedStateSurvivesRestartsThroughTheStore(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Map<String, Object> jdbc = Map.of("strata.store.kind", "jdbc", "strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db"));
        try (var strata = start(jdbc)) {
            await(CartClient.of(strata, "c3").add("plum", 4));
            await(CartClient.of(strata, "c3").note("gift"));
        }
        try (var strata = start(jdbc)) {
            var cart = CartClient.of(strata, "c3");
            assertEquals(4, await(cart.quantity("plum")));
            assertEquals("gift", await(cart.currentNote()));
        }
    }

    @Test
    void discoveryCanBeTurnedOff() {
        try (var strata = start(Map.of("strata.entity.discover", false))) {
            assertFalse(strata.types().contains(CartEntity.TYPE));
            strata.register(CartEntity.TYPE);
            strata.register(CartEntity.TYPE); // the same instance again is fine
            assertTrue(strata.types().contains(CartEntity.TYPE));
        }
    }
}
