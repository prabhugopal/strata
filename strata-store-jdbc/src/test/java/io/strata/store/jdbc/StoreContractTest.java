package io.strata.store.jdbc;

import com.typesafe.config.ConfigFactory;
import io.strata.StrataSettings;
import io.strata.spi.Commit;
import io.strata.spi.StaleRevisionException;
import io.strata.spi.StateStore;
import io.strata.spi.StateStores;
import io.strata.spi.Timer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What every JDBC database must do for Strata, run on H2 always and on PostgreSQL when
 * STRATA_TEST_POSTGRES_URL is set (CI sets it; also STRATA_TEST_POSTGRES_USER / _PASSWORD).
 * Each run uses fresh table names, so a shared database is fine.
 */
class StoreContractTest {
    @TempDir Path dir;
    final String run = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);

    StrataSettings settings(String db) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
                "strata.store.kind", "jdbc",
                "strata.store.jdbc.table", run + "_state",
                "strata.store.jdbc.journal-table", run + "_journal",
                "strata.store.jdbc.results-table", run + "_results",
                "strata.store.jdbc.timers-table", run + "_timers"));
        if (db.equals("postgres")) {
            String url = System.getenv("STRATA_TEST_POSTGRES_URL");
            Assumptions.assumeTrue(url != null && !url.isBlank(), "set STRATA_TEST_POSTGRES_URL to run against PostgreSQL");
            cfg.put("strata.store.jdbc.url", url);
            cfg.put("strata.store.jdbc.user", System.getenv().getOrDefault("STRATA_TEST_POSTGRES_USER", "postgres"));
            cfg.put("strata.store.jdbc.password", System.getenv().getOrDefault("STRATA_TEST_POSTGRES_PASSWORD", "postgres"));
        } else {
            cfg.put("strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db"));
        }
        return StrataSettings.from(ConfigFactory.parseMap(cfg).withFallback(ConfigFactory.load()));
    }

    interface Body { void run(StateStore store) throws Exception; }

    void withStore(String db, Body body) throws Exception {
        StrataSettings s = settings(db);
        try (ExecutorService io = s.newStoreExecutor(); var store = StateStores.create(s, io)) {
            body.run(store);
        }
    }

    static <T> T join(java.util.concurrent.CompletionStage<T> s) { return s.toCompletableFuture().join(); }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgres"})
    void nodesStartingTogetherCreateTheSchemaOnce(String db) throws Exception {
        StrataSettings s = settings(db);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<CompletableFuture<Void>> starts = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                starts.add(CompletableFuture.runAsync(() -> {
                    try (ExecutorService io = s.newStoreExecutor(); var store = StateStores.create(s, io)) {
                        join(store.load("x", "y"));
                    }
                }, pool));
            }
            CompletableFuture.allOf(starts.toArray(CompletableFuture[]::new)).join();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgres"})
    void revisionsFenceStaleWriters(String db) throws Exception {
        withStore(db, store -> {
            assertEquals(1L, join(store.save("c", "a", 0, "1".getBytes())));
            assertEquals(2L, join(store.save("c", "a", 1, "2".getBytes())));
            var stale = assertThrows(CompletionException.class, () -> join(store.save("c", "a", 1, "x".getBytes())));
            assertInstanceOf(StaleRevisionException.class, stale.getCause());
            join(store.save("c", "b", 0, "x".getBytes()));
            var dup = assertThrows(CompletionException.class, () -> join(store.save("c", "b", 0, "x".getBytes())));
            assertInstanceOf(StaleRevisionException.class, dup.getCause());
            assertEquals("2", new String(join(store.load("c", "a")).orElseThrow().state()));
            // A large state (BYTEA on PostgreSQL).
            byte[] big = new byte[512 * 1024];
            assertEquals(3L, join(store.save("c", "a", 2, big)));
            assertEquals(big.length, join(store.load("c", "a")).orElseThrow().state().length);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgres"})
    void anInvocationIsRecordedThenCommittedInOneTransaction(String db) throws Exception {
        withStore(db, store -> {
            join(store.begin("o", "1", "inv-1", "{\"command\":\"pay\"}".getBytes()));
            join(store.record("o", "1", "inv-1", 1, "{\"call\":\"Payments.charge\"}".getBytes()));
            var stale = assertThrows(CompletionException.class, () -> join(store.record("o", "1", "inv-OTHER", 2, "x".getBytes())));
            assertInstanceOf(StaleRevisionException.class, stale.getCause(), "only the owning invocation appends");
            var pending = join(store.pending("o", "1")).orElseThrow();
            assertEquals("inv-1", pending.invocationId());
            assertEquals(1, pending.entries().size());

            Timer t = new Timer("t-1", "o", "1", "expire", null, Instant.now().plusSeconds(3600));
            long rev = join(store.commit("o", "1", 0, new Commit("{\"s\":1}".getBytes(), "inv-1", "key-1",
                    "{\"ok\":1}".getBytes(), Instant.now().minusSeconds(60), List.of(t), List.of())));
            assertEquals(1L, rev);
            assertTrue(join(store.pending("o", "1")).isEmpty(), "the record is gone with the commit");
            assertEquals("{\"ok\":1}", new String(join(store.completed("o", "1", "key-1")).orElseThrow()));
            assertEquals(1, join(store.timers("o", "1", 10)).size(), "the timer came with the commit");

            // A commit that loses the revision race writes nothing at all: no key, no timer.
            var lost = assertThrows(CompletionException.class, () -> join(store.commit("o", "1", 0, new Commit("{}".getBytes(), null, "key-2",
                    "{}".getBytes(), null, List.of(new Timer("t-2", "o", "1", "expire", null, Instant.now())), List.of()))));
            assertInstanceOf(StaleRevisionException.class, lost.getCause());
            assertTrue(join(store.completed("o", "1", "key-2")).isEmpty());
            assertEquals(1, join(store.timers("o", "1", 10)).size());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"h2", "postgres"})
    void twoNodesClaimingTimersAtOnceNeverClaimTheSameOne(String db) throws Exception {
        withStore(db, store -> {
            Instant due = Instant.now().minusSeconds(1);
            for (int i = 0; i < 40; i++) join(store.schedule(new Timer("t" + i, "o", "e" + i, "expire", null, due)));
            Set<String> claimed = ConcurrentHashMap.newKeySet();
            List<String> duplicates = new ArrayList<>();
            Instant now = Instant.now();
            var a = CompletableFuture.supplyAsync(() -> join(store.claimDue(List.of("o"), now, 40, "node-a", now.plusSeconds(30))));
            var b = CompletableFuture.supplyAsync(() -> join(store.claimDue(List.of("o"), now, 40, "node-b", now.plusSeconds(30))));
            for (Timer t : a.join()) if (!claimed.add(t.id())) duplicates.add(t.id());
            for (Timer t : b.join()) if (!claimed.add(t.id())) duplicates.add(t.id());
            assertEquals(List.of(), duplicates, "no timer claimed by both");
            assertEquals(40, claimed.size(), "between them they claimed all");
            assertTrue(join(store.claimDue(List.of("o"), now, 40, "node-c", now.plusSeconds(30))).isEmpty(), "live claims are respected");
            // A claim that runs out (its node died) can be taken again.
            assertEquals(40, join(store.claimDue(List.of("o"), now.plusSeconds(31), 40, "node-c", now.plusSeconds(60))).size());
            assertTrue(join(store.cancelTimer("t0")));
            assertFalse(join(store.cancelTimer("t0")));
        });
    }
}
