package io.strata.pekko;

import io.strata.spi.StateStore;
import org.apache.pekko.actor.ExtendedActorSystem;
import org.apache.pekko.coordination.lease.LeaseSettings;
import org.apache.pekko.coordination.lease.javadsl.Lease;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A Pekko lease held in Strata's store (one row): used by the split-brain resolver
 * (strata.pekko.split-brain.strategy = lease-majority) to decide which side of a partitioned cluster
 * keeps running. The side that can't reach the store couldn't commit anything anyway. While held,
 * it's renewed every heartbeat-interval for heartbeat-timeout; a failed renewal means it's lost.
 */
public final class StoreLease extends Lease {
    private final LeaseSettings settings;
    private final ExtendedActorSystem system;
    private final AtomicBoolean held = new AtomicBoolean();
    private final ScheduledExecutorService ticks = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "strata-lease");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> heartbeat;

    /** Created by Pekko's LeaseProvider. */
    public StoreLease(LeaseSettings settings, ExtendedActorSystem system) {
        this.settings = settings;
        this.system = system;
    }

    /**
     * The node's store, looked up when first needed: Pekko may create the lease while the actor
     * system starts, before Strata has registered the store; the lease is used only later.
     */
    private StateStore store() {
        return PekkoStrataRuntime.storeOf(system)
                .orElseThrow(() -> new IllegalStateException("StoreLease needs a running Strata node in actor system " + system.name()));
    }

    @Override public LeaseSettings getSettings() { return settings; }

    @Override public CompletionStage<Boolean> acquire() { return acquire(lost -> { }); }

    @Override
    public CompletionStage<Boolean> acquire(Consumer<Optional<Throwable>> leaseLostCallback) {
        Duration ttl = settings.timeoutSettings().getHeartbeatTimeout();
        return store().acquireLease(settings.leaseName(), settings.ownerName(), ttl).thenApply(got -> {
            held.set(got);
            if (got) startHeartbeat(ttl, leaseLostCallback);
            return got;
        });
    }

    private synchronized void startHeartbeat(Duration ttl, Consumer<Optional<Throwable>> lost) {
        if (heartbeat != null) return;
        long every = settings.timeoutSettings().getHeartbeatInterval().toMillis();
        heartbeat = ticks.scheduleWithFixedDelay(() -> {
            if (!held.get()) return;
            try {
                boolean still = store().acquireLease(settings.leaseName(), settings.ownerName(), ttl).toCompletableFuture()
                        .get(settings.timeoutSettings().getOperationTimeout().toMillis(), TimeUnit.MILLISECONDS);
                if (!still) loseIt(lost, Optional.empty());
            } catch (Exception e) {
                loseIt(lost, Optional.of(e));
            }
        }, every, every, TimeUnit.MILLISECONDS);
    }

    private void loseIt(Consumer<Optional<Throwable>> lost, Optional<Throwable> why) {
        if (held.getAndSet(false)) lost.accept(why);
    }

    @Override
    public CompletionStage<Boolean> release() {
        held.set(false);
        ScheduledFuture<?> h = heartbeat;
        if (h != null) h.cancel(false);
        heartbeat = null;
        return store().releaseLease(settings.leaseName(), settings.ownerName());
    }

    @Override public boolean checkLease() { return held.get(); }
}
