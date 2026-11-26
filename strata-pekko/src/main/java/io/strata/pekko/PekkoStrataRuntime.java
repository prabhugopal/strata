package io.strata.pekko;

import io.strata.Codec;
import io.strata.EntityRef;
import io.strata.Durability;
import io.strata.EntityType;
import io.strata.durability.Recording;
import io.strata.Services;
import io.strata.StrataException;
import io.strata.StrataRuntime;
import io.strata.StrataSettings;
import io.strata.TypeRef;
import io.strata.StrataException.Kind;
import io.strata.management.Introspection;
import io.strata.management.ManagementSettings;
import io.strata.management.Observability;
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

import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/** Strata on Apache Pekko: one actor system per node, one sharded entity type per registration. */
public final class PekkoStrataRuntime implements StrataRuntime {
    private final StrataSettings settings;
    private final Services services;
    private final io.strata.seal.Seal seal;
    private final PekkoSettings pekko;
    private final Codec codec;
    private final ExecutorService io;
    /** Runs commands of entities that use services (they may block): one virtual thread each. */
    private final ExecutorService commands = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    private final StateStore store;
    private final ActorSystem<Void> system;
    private final ClusterSharding sharding;
    private final Map<String, Registered<?>> types = new LinkedHashMap<>(); // guarded by itself
    private final java.time.Instant startedAt = java.time.Instant.now();
    private final Observability observability;
    private final String node;
    private final java.util.Deque<AutoCloseable> closeWith = new java.util.concurrent.ConcurrentLinkedDeque<>();

    private record Registered<S>(EntityType<S> type, EntityTypeKey<Msg> key) {}

    public PekkoStrataRuntime(StrataSettings settings, Services services) {
        this.settings = settings;
        this.services = services;
        this.seal = new io.strata.seal.Seal(settings.config());
        this.pekko = PekkoSettings.from(settings.config());
        this.codec = new Codec(settings);
        this.io = settings.newStoreExecutor();
        this.store = StateStores.create(settings, io);
        this.system = ActorSystem.create(Behaviors.empty(), pekko.systemName(), pekko.systemConfig());
        this.sharding = ClusterSharding.get(system);
        Cluster cluster = Cluster.get(system);
        this.node = cluster.selfMember().address().toString();
        this.observability = new Observability(ManagementSettings.from(settings.config()), () -> node);
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
        synchronized (types) {
            Registered<?> already = types.get(type.name());
            if (already != null && already.type() == type) return;
        }
        seal.apply(type);                                 // refuse unsealed entity code (strata.seal)
        // Services are looked up once; interface services of persistent entities are wrapped so
        // every call is recorded (a class can't be wrapped: its calls aren't recorded, and we say so).
        boolean recorded = type.durability(settings) == Durability.PERSISTENT;
        Services forType = new Services() {
            @Override
            public <T> java.util.Optional<T> find(Class<T> cls, String name) {
                return services.find(cls, name).map(svc -> {
                    if (!recorded) return svc;
                    if (cls.isInterface()) return Recording.wrap(cls, svc);
                    system.log().warn("{}: injected {} is a class, so its calls aren't recorded and may repeat after a crash; inject an interface",
                            type.name(), cls.getName());
                    return svc;
                });
            }
        };
        EntityType.Bound<S> check = type.bind(forType); // fail before registering if a service is missing
        synchronized (types) {
            Registered<?> existing = types.putIfAbsent(type.name(), new Registered<>(type, key));
            if (existing != null) {
                if (existing.type() == type) return; // e.g. discovered and also declared as a Spring bean
                throw new StrataException("A different entity type named \"" + type.name() + "\" is already registered");
            }
        }
        // Services were looked up once above; a missing one failed registration (and so startup).
        EntityType.Bound<S> bound = check;
        var deps = new EntityActor.Deps<>(codec, store, type.durability(settings), settings.passivateAfter(), pekko.stashCapacity(), observability, bound,
                commands, settings.config().getDuration("strata.durability.result-retention"));
        sharding.init(Entity.of(key, ctx -> EntityActor.create(type, ctx.getEntityId(), deps, ctx.getShard()))
                .withStopMessage(Stop.INSTANCE));
    }

    @Override
    public Collection<EntityType<?>> types() {
        synchronized (types) {
            return types.values().stream().<EntityType<?>>map(Registered::type).toList();
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <S> EntityRef<S> ref(EntityType<S> type, String id) {
        Registered<?> r;
        synchronized (types) { r = types.get(type.name()); }
        if (r == null) throw new StrataException(Kind.NOT_FOUND, "Entity type \"" + type.name() + "\" isn't registered; call register() first");
        if (id == null || id.isBlank() || id.length() > settings.maxIdLength()) {
            throw new StrataException(Kind.INVALID_ARGUMENT, "Entity id must be 1-" + settings.maxIdLength() + " characters (strata.entity.max-id-length): " + id);
        }
        return new Ref<>((EntityType<S>) r.type(), id, sharding.entityRefFor(r.key(), id));
    }

    @Override
    public StrataSettings settings() { return settings; }

    @Override
    public Services services() { return services; }

    @Override
    public Introspection introspection() { return introspection; }

    private final Introspection introspection = new Introspection() {
        @Override public String node() { return node; }
        @Override public java.time.Instant startedAt() { return startedAt; }
        @Override public Observability observability() { return observability; }
        @Override public StateStore store() { return store; }
        @Override public boolean ready() { return Cluster.get(system).selfMember().status() == MemberStatus.up(); }

        @Override
        public CompletionStage<ClusterView> cluster() {
            var state = Cluster.get(system).state();
            var unreachable = new java.util.HashSet<org.apache.pekko.cluster.Member>();
            state.getUnreachable().forEach(unreachable::add);
            List<Member> members = new java.util.ArrayList<>();
            state.getMembers().forEach(m -> members.add(new Member(m.address().toString(), m.status().toString(),
                    java.util.Set.copyOf(m.getRoles()), !unreachable.contains(m))));
            String leader = state.getLeader() == null ? null : state.getLeader().toString();
            List<Registered<?>> regs;
            synchronized (types) { regs = List.copyOf(types.values()); }
            Map<String, Map<String, Integer>> byType = new java.util.concurrent.ConcurrentHashMap<>();
            var asks = regs.stream().map(r -> AskPattern.<org.apache.pekko.cluster.sharding.typed.ClusterShardingQuery, org.apache.pekko.cluster.sharding.ShardRegion.ClusterShardingStats>ask(
                            sharding.shardState(),
                            replyTo -> new org.apache.pekko.cluster.sharding.typed.GetClusterShardingStats(r.key(), settings.callTimeout(), replyTo),
                            settings.callTimeout(), system.scheduler())
                    .thenAccept(stats -> {
                        Map<String, Integer> perNode = new java.util.TreeMap<>();
                        stats.getRegions().forEach((address, region) ->
                                perNode.put(address.toString(), region.getStats().values().stream().mapToInt(v -> ((Number) v).intValue()).sum()));
                        byType.put(r.type().name(), perNode);
                    }).toCompletableFuture())
                    .toArray(java.util.concurrent.CompletableFuture[]::new);
            return java.util.concurrent.CompletableFuture.allOf(asks)
                    .thenApply(v -> new ClusterView(node, leader, members, new java.util.TreeMap<>(byType)));
        }
    };

    /** The node's actor system, for modules built on Pekko (e.g. strata-http). */
    public ActorSystem<Void> actorSystem() { return system; }

    @Override
    public void closeWith(AutoCloseable resource) { closeWith.push(resource); }

    @Override
    public void close() {
        for (AutoCloseable r; (r = closeWith.poll()) != null; ) {
            try { r.close(); } catch (Exception e) { system.log().warn("Couldn't close {}", r, e); }
        }
        system.terminate();
        try {
            system.getWhenTerminated().toCompletableFuture().get(pekko.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            // Terminating anyway; the store and executor are closed below.
        } finally {
            commands.shutdownNow();
            store.close();
            io.close();
        }
    }

    private final class Ref<S> implements EntityRef<S> {
        private final EntityType<S> type;
        private final String id;
        private final org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef<Msg> target;
        private final String key;

        Ref(EntityType<S> type, String id, org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef<Msg> target) {
            this(type, id, target, null);
        }

        private Ref(EntityType<S> type, String id, org.apache.pekko.cluster.sharding.typed.javadsl.EntityRef<Msg> target, String key) {
            this.type = type; this.id = id; this.target = target; this.key = key;
        }

        @Override
        public EntityRef<S> withIdempotencyKey(String key) {
            int max = settings.config().getInt("strata.durability.max-key-length");
            if (key == null || key.isBlank() || key.length() > max) {
                throw new StrataException(Kind.INVALID_ARGUMENT, "An idempotency key must be 1-" + max + " characters (strata.durability.max-key-length)");
            }
            return new Ref<>(type, id, target, key);
        }

        @Override public String id() { return id; }

        @Override
        public <R> CompletionStage<R> call(String command, Class<R> resultType) {
            return call(command, null, resultType);
        }

        @Override
        public <R> CompletionStage<R> call(String command, Object arg, Class<R> resultType) {
            byte[] encoded = arg == null ? null : codec.encode(arg);
            return ask(replyTo -> new Invoke(command, encoded, key, replyTo), resultType);
        }

        @Override
        public <R> CompletionStage<R> call(String command, Object arg, TypeRef<R> resultType) {
            byte[] encoded = arg == null ? null : codec.encode(arg);
            return ask(replyTo -> new Invoke(command, encoded, key, replyTo), resultType.type());
        }

        @Override
        public CompletionStage<byte[]> callJson(String command, byte[] jsonArg) {
            return askRaw(replyTo -> new Invoke(command, jsonArg, key, replyTo));
        }

        @Override
        public CompletionStage<S> state() {
            return ask(GetState::new, type.stateType());
        }

        private <R> CompletionStage<R> ask(Function<org.apache.pekko.actor.typed.ActorRef<Result>, Msg> msg, Type resultType) {
            return askRaw(msg).thenApply(bytes -> codec.decode(bytes, resultType));
        }

        private CompletionStage<byte[]> askRaw(Function<org.apache.pekko.actor.typed.ActorRef<Result>, Msg> msg) {
            CompletionStage<Result> reply = AskPattern.ask(target, msg::apply, settings.callTimeout(), system.scheduler());
            return reply.handle((r, failure) -> {
                if (failure != null) {
                    Throwable cause = failure instanceof java.util.concurrent.CompletionException ce && ce.getCause() != null ? ce.getCause() : failure;
                    if (cause instanceof TimeoutException) {
                        throw new StrataException(Kind.UNAVAILABLE, type.name() + "/" + id + " didn't answer within "
                                + settings.callTimeout() + " (strata.call-timeout)", cause);
                    }
                    throw new StrataException(Kind.UNAVAILABLE, type.name() + "/" + id + ": " + cause.getMessage(), cause);
                }
                if (r.error() != null) throw new StrataException(Kind.parse(r.errorKind()), r.error());
                return r.value();
            });
        }
    }

    /** Registered with ServiceLoader; selected by strata.runtime.backend = "pekko". */
    public static final class Provider implements RuntimeProvider {
        @Override public String backend() { return "pekko"; }
        @Override public StrataRuntime start(StrataSettings settings, Services services) { return new PekkoStrataRuntime(settings, services); }
    }
}
