package io.strata.pekko;

import com.typesafe.config.ConfigFactory;
import io.strata.Strata;
import io.strata.StrataRuntime;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Two nodes; the one with the lower address crashes (stops without leaving the cluster). With
 * keep-majority the survivor sees a 1-1 tie, which goes to the lowest address: the crashed node,
 * so the survivor downs itself. With lease-majority the survivor takes the lease and keeps serving,
 * with every entity, including those that lived on the crashed node.
 */
class NodeCrashTest {
    @TempDir Path dir;

    StrataRuntime node(int port, int seedPort, String strategy) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
                "strata.pekko.cluster-mode", "seed-nodes",
                "strata.pekko.seed-nodes", "pekko://strata@127.0.0.1:" + seedPort,
                "strata.pekko.system.pekko.remote.artery.canonical.port", port,
                "strata.pekko.split-brain.strategy", strategy,
                "strata.pekko.split-brain.stable-after", "2s",
                "strata.store.kind", "jdbc",
                "strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db") + ";AUTO_SERVER=FALSE"));
        // Notice a silent node quickly, and let "close" behave like a crash: no leaving the cluster.
        cfg.put("strata.pekko.system.pekko.cluster.failure-detector.acceptable-heartbeat-pause", "2s");
        cfg.put("strata.pekko.system.pekko.coordinated-shutdown.run-by-actor-system-terminate", "off");
        return Strata.start(ConfigFactory.parseMap(cfg).withFallback(ConfigFactory.load()).resolve());
    }

    @ParameterizedTest
    @ValueSource(strings = {"keep-majority", "lease-majority"})
    void theSurvivorOfATwoNodeCluster(String strategy) throws Exception {
        int p1, p2;
        try (var x = new ServerSocket(0); var y = new ServerSocket(0)) { p1 = x.getLocalPort(); p2 = y.getLocalPort(); }
        int low = Math.min(p1, p2), high = Math.max(p1, p2);
        var startLow = CompletableFuture.supplyAsync(() -> node(low, low, strategy));
        var startHigh = CompletableFuture.supplyAsync(() -> node(high, low, strategy));
        StrataRuntime crashed = startLow.get(60, TimeUnit.SECONDS);
        try (StrataRuntime survivor = startHigh.get(60, TimeUnit.SECONDS)) {
            for (int i = 0; i < 20; i++) CartClient.of(survivor, "c" + i).add("tea", 1).toCompletableFuture().get(10, TimeUnit.SECONDS);
            var before = survivor.introspection().cluster().toCompletableFuture().get().entitiesByTypeAndNode().get("cart");
            assertEquals(2, before.size(), "carts on both nodes: " + before);

            crashed.close();   // the lower-address node dies without a word

            if (strategy.equals("keep-majority")) {
                // A 1-1 tie goes to the lowest address, the dead node: the survivor downs itself.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
                while (survivor.introspection().ready() && System.nanoTime() < deadline) Thread.sleep(200);
                assertFalse(survivor.introspection().ready(), "keep-majority: the survivor of a 2-node cluster stops itself");
                // And says so: its liveness probe fails, so Kubernetes restarts it and it can rejoin.
                long liveBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (survivor.introspection().live() && System.nanoTime() < liveBy) Thread.sleep(200);
                assertFalse(survivor.introspection().live(), "a removed node reports itself not live");
            } else {
                // The survivor takes the lease, downs the dead node, and every cart answers from it.
                // A call sent to the dead node as it died is lost, so callers retry (as real ones do).
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                for (int i = 0; i < 20; i++) {
                    while (true) {
                        try {
                            assertEquals(1, CartClient.of(survivor, "c" + i).quantity("tea").toCompletableFuture().get(10, TimeUnit.SECONDS), "c" + i);
                            break;
                        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException retry) {
                            if (System.nanoTime() > deadline) throw retry;
                        }
                    }
                }
                assertTrue(survivor.introspection().ready(), "lease-majority: the survivor keeps serving");
                assertTrue(survivor.introspection().live());
                assertEquals(1, survivor.introspection().cluster().toCompletableFuture().get().members().size());
            }
        } finally {
            crashed.close();
        }
    }
}
