package io.strata.pekko;

import com.typesafe.config.ConfigFactory;
import io.strata.Strata;
import io.strata.StrataRuntime;
import org.apache.pekko.coordination.lease.javadsl.Lease;
import org.apache.pekko.coordination.lease.javadsl.LeaseProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The split-brain lease, through Pekko's own LeaseProvider (as the resolver loads it), over one shared store. */
class SplitBrainLeaseTest {
    @TempDir Path dir;

    StrataRuntime node(Map<String, Object> extra) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
                "strata.pekko.split-brain.strategy", "lease-majority",
                "strata.pekko.split-brain.lease.heartbeat-interval", "300ms",
                "strata.pekko.split-brain.lease.heartbeat-timeout", "2s",
                "strata.pekko.system.pekko.remote.artery.canonical.port", 0,
                "strata.store.kind", "jdbc",
                "strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db") + ";AUTO_SERVER=FALSE"));
        cfg.putAll(extra);
        return Strata.start(ConfigFactory.parseMap(cfg).withFallback(ConfigFactory.load()).resolve());
    }

    static Lease lease(StrataRuntime node, String owner) {
        var classic = ((PekkoStrataRuntime) node).actorSystem().classicSystem();
        return LeaseProvider.get(classic).getLease("strata-sbr", PekkoSettings.LEASE_CONFIG_PATH, owner);
    }

    static boolean get(java.util.concurrent.CompletionStage<Boolean> s) throws Exception { return s.toCompletableFuture().get(10, TimeUnit.SECONDS); }

    @Test
    void oneSideHoldsTheLeaseUntilItsReleasedOrItsHolderGoesSilent() throws Exception {
        StrataRuntime a = node(Map.of());
        try (StrataRuntime b = node(Map.of())) {
            Lease la = lease(a, "node-a"), lb = lease(b, "node-b");
            assertInstanceOf(StoreLease.class, la, "Pekko loads Strata's lease from the split-brain settings");

            assertTrue(get(la.acquire()), "a takes the lease");
            assertTrue(la.checkLease());
            assertFalse(get(lb.acquire()), "b can't while a holds it");
            assertFalse(lb.checkLease());

            Thread.sleep(2500);            // longer than heartbeat-timeout: a's heartbeat keeps it
            assertFalse(get(lb.acquire()), "a renews it while it's alive");

            assertTrue(get(la.release()));
            assertTrue(get(lb.acquire()), "released: b takes it");
            assertTrue(get(lb.release()));

            // a takes it again and then goes silent (its node stops without releasing).
            assertTrue(get(la.acquire()));
            a.close();
            assertFalse(get(lb.acquire()), "not yet expired");
            Thread.sleep(2500);
            assertTrue(get(lb.acquire()), "a went silent: after heartbeat-timeout b takes it over");
        } finally {
            a.close();
        }
    }

    @Test
    void theStrategyIsChecked() {
        var e = assertThrows(IllegalArgumentException.class, () -> PekkoSettings.splitBrain(ConfigFactory.parseMap(Map.of(
                "strata.pekko.split-brain.strategy", "majority")).withFallback(ConfigFactory.load()).getConfig("strata.pekko.split-brain")));
        assertTrue(e.getMessage().contains("lease-majority"), e.getMessage());
        var lease = PekkoSettings.splitBrain(ConfigFactory.parseMap(Map.of("strata.pekko.split-brain.strategy", "lease-majority"))
                .withFallback(ConfigFactory.load()).getConfig("strata.pekko.split-brain"));
        assertEquals("lease-majority", lease.getString("pekko.cluster.split-brain-resolver.active-strategy"));
        assertEquals(StoreLease.class.getName(), lease.getString(PekkoSettings.LEASE_CONFIG_PATH + ".lease-class"));
    }
}
