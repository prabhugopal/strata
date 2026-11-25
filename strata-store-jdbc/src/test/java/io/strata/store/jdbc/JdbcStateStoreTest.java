package io.strata.store.jdbc;

import com.typesafe.config.ConfigFactory;
import io.strata.StrataSettings;
import io.strata.spi.StaleRevisionException;
import io.strata.spi.StateStores;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;

class JdbcStateStoreTest {
    @TempDir Path dir;

    StrataSettings settings() {
        return StrataSettings.from(ConfigFactory.parseString(
                "strata.store.kind = jdbc\nstrata.store.jdbc.url = \"jdbc:h2:file:" + dir.resolve("db") + "\"")
                .withFallback(ConfigFactory.load()));
    }

    @Test
    void commitsLoadsAndFencesStaleRevisions() {
        StrataSettings s = settings();
        try (ExecutorService io = s.newStoreExecutor(); var store = StateStores.create(s, io)) {
            assertInstanceOf(JdbcStateStore.class, store);
            assertTrue(store.load("counter", "a").toCompletableFuture().join().isEmpty());
            assertEquals(1L, store.save("counter", "a", 0, "1".getBytes()).toCompletableFuture().join());
            assertEquals(2L, store.save("counter", "a", 1, "2".getBytes()).toCompletableFuture().join());

            var stale = assertThrows(CompletionException.class, () -> store.save("counter", "a", 1, "x".getBytes()).toCompletableFuture().join());
            assertInstanceOf(StaleRevisionException.class, stale.getCause());
            var dup = assertThrows(CompletionException.class, () -> store.save("counter", "a", 0, "x".getBytes()).toCompletableFuture().join());
            assertInstanceOf(StaleRevisionException.class, dup.getCause());

            var loaded = store.load("counter", "a").toCompletableFuture().join().orElseThrow();
            assertEquals(2L, loaded.revision());
            assertEquals("2", new String(loaded.state()));
        }
        // A new store over the same database (a restarted process) sees the committed state.
        try (ExecutorService io = s.newStoreExecutor(); var again = StateStores.create(s, io)) {
            assertEquals(2L, again.load("counter", "a").toCompletableFuture().join().orElseThrow().revision());
        }
    }

    @Test
    void rejectsUnsafeTableNames() {
        var bad = StrataSettings.from(ConfigFactory.parseString("strata.store.kind = jdbc\nstrata.store.jdbc.table = \"x; drop table y\"")
                .withFallback(ConfigFactory.load()));
        try (ExecutorService io = bad.newStoreExecutor()) {
            assertThrows(IllegalArgumentException.class, () -> StateStores.create(bad, io));
        }
    }
}
