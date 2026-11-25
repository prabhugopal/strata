package io.strata.pekko;

import io.strata.Codec;
import io.strata.EntityRef;
import io.strata.EntityType;
import io.strata.StrataException;
import io.strata.StrataRuntime;
import io.strata.StrataSettings;
import io.strata.spi.RuntimeProvider;
import io.strata.spi.StateStore;
import io.strata.spi.StateStores;
import io.strata.pekko.Protocol.*;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.cluster.MemberStatus;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;
import org.apache.pekko.cluster.sharding.typed.javadsl.Entity;
import org.apache.pekko.cluster.sharding.typed.javadsl.EntityTypeKey;
import org.apache.pekko.cluster.typed.Cluster;
import org.apache.pekko.cluster.typed.Join;

import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Strata on Apache Pekko: one actor system per node, one sharded entity type per registration. */
public final class PekkoStrataRuntime implements StrataRuntime {
    private final StrataSettings settings;
    private final PekkoSettings pekko;
    private final Codec codec;
    private final ExecutorService io;
    private final StateStore store;
    private final ActorSystem<Void> system;
    private final ClusterSharding sharding;
    private final Map<String, Registered<?>> types = new ConcurrentHashMap<>();

    private record Registered<S>(EntityType<S> type, EntityTypeKey<Msg> key) {}

    public PekkoStrataRuntime(StrataSettings settings) {
        this.settings = settings;
        this.pekko = PekkoSettings.from(settings.config());
        this.codec = new Codec(settings);
        this.io = settings.newStoreExecutor();
        this.store = StateStores.create(settings, io);
        this.system = ActorSystem.create(Behaviors.empty(), pekko.systemName(), pekko.systemConfig());
        this.sharding = ClusterSharding.get(system);
        Cluster cluster = Cluster.get(system);
        if (pekko.clusterMode().equals("single")) {
            cluster.manager().tell(Join.create(cluster.selfMember().address()));
        }
        awaitUp(cluster);
    }

    private void awaitUp(Cluster cluster) {
        long deadline = System.nanoTime() + pekko.startupTimeout().toNanos();
        while (cluster.selfMember().status() != MemberStatus.up()) {
            if (System.nanoTime() > deadline) {
                close();
                throw new StrataException("Node didn't join a cluster within " + pekko.startupTimeout()
                        + " (strata.pekko.startup-timeout); cluster-mode = " + pekko.clusterMode());
            }
            try { Thread.sleep(pekko.startupPollInterval().toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new StrataException("Interrupted while starting", e); }
        }
    }

    @Override
    public <S> void register(EntityType<S> type) {
        EntityTypeKey<Msg> key = EntityTypeKey.create(Msg.class, type.name());
        if (types.putIfAbsent(type.name(), new Registered<>(type, key)) != null) {
            throw new StrataException("Entity type \"" + type.name() + "\" is already registered");
        }
        var deps = new EntityActor.Deps(codec, store, type.durability(settings), settings.passivateAfter(), pekko.stashCapacity());
        sharding.init(Entity.of(key, ctx -> EntityActor.create(type, ctx.getEntityId(), deps, ctx.getShard()))
                .withStopMessage(Stop.INSTANCE));
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> EntityRef<S> ref(EntityType<S> type, String id) {
        Registered<?> r = types.get(type.name());
        if (r == null) throw new StrataException("Entity type \"" + type.name() + "\" isn't registered; call register() first");
        if (id == null || id.isBlank() || id.length() > settings.maxIdLength()) {
            throw new StrataException("Entity id must be 1-" + settings.maxIdLength() + " characters (strata.entity.max-id-length): " + id);
        }
        return new Ref<>((EntityType<S>) r.type(), id, sharding.entityRefFor(r.key(), id));
    }

    @Override
    public StrataSettings settings() { return settings; }

    @Override
    public void close() {
        system.terminate();
        try {
            system.getWhenTerminated().toCompletableFuture().get(pekko.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Terminating anyway; the store and executor are closed below.
        } finally {
            store.close();
            io.close();
        }
    }

    private final class Ref<S> implements EntityRef<S> {
        private final EntityType<S> type;
        private final String id;
        private final org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef<Msg> target;

        Ref(EntityType<S> type, String id, org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef<Msg> target) {
            this.type = type; this.id = id; this.target = target;
        }

        @Override public String id() { return id; }

        @Override
        public <R> CompletionStage<R> call(String command, Class<R> resultType) {
            return call(command, null, resultType);
        }

        @Override
        public <R> CompletionStage<R> call(String command, Object arg, Class<R> resultType) {
            byte[] encoded = arg == null ? null : codec.encode(arg);
            return ask(replyTo -> new Invoke(command, encoded, replyTo), resultType);
        }

        @Override
        public CompletionStage<S> state() {
            return ask(GetState::new, type.stateType());
        }

        private <R> CompletionStage<R> ask(Function<org.apache.pekko.actor.typed.ActorRef<Result>, Msg> msg, Class<R> resultType) {
            CompletionStage<Result> reply = AskPattern.ask(target, msg::apply, settings.callTimeout(), system.scheduler());
            return reply.thenApply(r -> {
                if (r.error() != null) throw new StrataException(r.error());
                return codec.decode(r.value(), resultType);
            });
        }
    }

    /** Registered with ServiceLoader; selected by strata.runtime.backend = "pekko". */
    public static final class Provider implements RuntimeProvider {
        @Override public String backend() { return "pekko"; }
        @Override public StrataRuntime start(StrataSettings settings) { return new PekkoStrataRuntime(settings); }
    }
}
