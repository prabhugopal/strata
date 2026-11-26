package io.strata.pekko;

import com.typesafe.config.ConfigFactory;
import io.strata.Strata;
import io.strata.StrataRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Two nodes in one cluster: entities spread over both, calls cross nodes, a node can leave. */
class MultiNodeTest {
    @TempDir Path dir;

    static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    StrataRuntime node(int port, int seedPort) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("strata.pekko.cluster-mode", "seed-nodes");
        cfg.put("strata.pekko.seed-nodes", "pekko://strata@127.0.0.1:" + seedPort);
        cfg.put("strata.pekko.system.pekko.remote.artery.canonical.port", port);
        cfg.put("strata.store.kind", "jdbc");
        cfg.put("strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db") + ";AUTO_SERVER=FALSE");
        return Strata.start(ConfigFactory.parseMap(cfg).withFallback(ConfigFactory.load()).resolve());
    }

    @Test
    void entitiesSpreadAcrossNodesAndSurviveANodeLeaving() throws Exception {
        int pa = freePort(), pb = freePort();
        var startA = CompletableFuture.supplyAsync(() -> node(pa, pa));
        var startB = CompletableFuture.supplyAsync(() -> node(pb, pa));
        StrataRuntime b = startB.get(60, TimeUnit.SECONDS);
        try (StrataRuntime a = startA.get(60, TimeUnit.SECONDS)) {
            assertEquals(2, a.introspection().cluster().toCompletableFuture().get().members().size());

            // Called from b, each cart lives on whichever node owns its shard.
            for (int i = 0; i < 40; i++) {
                assertEquals(i + 1, CartClient.of(b, "cart-" + i).add("apple", i + 1).toCompletableFuture().get(10, TimeUnit.SECONDS));
            }
            var where = a.introspection().cluster().toCompletableFuture().get().entitiesByTypeAndNode().get("cart");
            assertEquals(2, where.size(), "both nodes hold carts: " + where);
            assertEquals(40, where.values().stream().mapToInt(Integer::intValue).sum());
            // A rejected command on a remote entity carries its kind across the wire.
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> CartClient.of(a, "cart-1").add("apple", -1).toCompletableFuture().get(10, TimeUnit.SECONDS));
            assertEquals(io.strata.StrataException.Kind.FAILED, ((io.strata.StrataException) failed.getCause()).kind());

            // b leaves; its carts move to a and load their committed state.
            b.close(); // leaves the cluster gracefully
            for (int i = 0; i < 40; i++) {
                assertEquals(i + 1, CartClient.of(a, "cart-" + i).quantity("apple").toCompletableFuture().get(30, TimeUnit.SECONDS),
                        "cart-" + i + " after its node left");
            }
        } finally {
            b.close();
        }
    }
}
