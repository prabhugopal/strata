package io.strata.pekko;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strata.Codec;
import io.strata.Durability;
import io.strata.EntityType;
import io.strata.Reply;
import io.strata.StrataException;
import io.strata.StrataException.Kind;
import io.strata.durability.Invocation;
import io.strata.durability.Journal;
import io.strata.durability.JournalWriteException;
import io.strata.management.EntityObserver;
import io.strata.pekko.Protocol.*;
import io.strata.spi.Commit;
import io.strata.spi.Pending;
import io.strata.spi.Timer;
import io.strata.spi.StaleRevisionException;
import io.strata.spi.StateStore;
import io.strata.spi.StoredState;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.PostStop;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.StashBuffer;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

/**
 * One entity. Owns its state and handles one command at a time (single writer); others wait in
 * the stash. A persistent entity loads its state when it starts and commits each change before
 * replying.
 *
 * <p>Entities that use services run commands on a virtual thread (services may block), and a
 * command that changes state is recorded first, with each service call's outcome, so that if the
 * node stops mid-command the entity resumes it where it was when it's next loaded: recorded calls
 * return their recorded outcome, later ones run. Idempotency keys answer repeated requests.
 */
final class EntityActor<S> {
    record Deps<S>(Codec codec, StateStore store, Durability durability, Duration passivateAfter, int stashCapacity,
                   EntityObserver observer, EntityType.Bound<S> inject, Executor executor, Duration resultRetention, boolean offThread) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EntityType<S> type;
    private final String id;
    private final Deps<S> deps;
    private final ActorContext<Msg> ctx;
    private final StashBuffer<Msg> stash;
    private final org.apache.pekko.actor.typed.ActorRef<ClusterSharding.ShardCommand> shard;
    private final boolean persistent;
    /** Commands may block on services: run them off the actor's thread. */
    private final boolean offThread;

    private S state;
    private long revision;

    private EntityActor(EntityType<S> type, String id, Deps<S> deps, ActorContext<Msg> ctx, StashBuffer<Msg> stash,
                        org.apache.pekko.actor.typed.ActorRef<ClusterSharding.ShardCommand> shard) {
        this.type = type; this.id = id; this.deps = deps; this.ctx = ctx; this.stash = stash; this.shard = shard;
        this.persistent = deps.durability() == Durability.PERSISTENT;
        this.offThread = deps.offThread();
    }

    static <S> Behavior<Msg> create(EntityType<S> type, String id, Deps<S> deps, org.apache.pekko.actor.typed.ActorRef<ClusterSharding.ShardCommand> shard) {
        return Behaviors.withStash(deps.stashCapacity(), stash -> Behaviors.setup(ctx -> {
            ctx.setReceiveTimeout(deps.passivateAfter(), Idle.INSTANCE);
            deps.observer().activated(type.name(), id);
            return new EntityActor<>(type, id, deps, ctx, stash, shard).start();
        }));
    }

    // ---- loading and resuming -------------------------------------------------------------------

    private Behavior<Msg> start() {
        if (!persistent) {
            state = injected(type.initialState());
            return ready();
        }
        return load();
    }

    private Behavior<Msg> load() {
        return await(deps.store().load(type.name(), id), (stored, err) -> {
            if (err != null) return loadFailed(err);
            state = injected(stored.map(s -> deps.codec().decode(s.state(), type.stateType())).orElseGet(type::initialState));
            revision = stored.map(StoredState::revision).orElse(0L);
            return offThread ? resume() : unstashReady();
        });
    }

    /** Finish a command that was running when its node stopped: its recorded calls replay. */
    private Behavior<Msg> resume() {
        return await(deps.store().pending(type.name(), id), (found, err) -> {
            if (err != null) return loadFailed(err);
            if (found.isEmpty()) return unstashReady();
            Pending p = found.get();
            JsonNode h = tree(p.header());
            Invoke inv = new Invoke(h.path("command").asText(), h.hasNonNull("arg") ? bytes(h.get("arg")) : null,
                    h.hasNonNull("key") ? h.get("key").asText() : null, null);
            ctx.getLog().info("Resuming {} on {}/{} where it stopped ({} recorded calls)", inv.command(), type.name(), id, p.entries().size());
            return run(inv, p.invocationId(), p.entries(), System.nanoTime());
        });
    }

    private Behavior<Msg> loadFailed(Throwable err) {
        ctx.getLog().error("Couldn't load {}/{}; stopping (it reloads on next use)", type.name(), id, err);
        deps.observer().loadFailed(type.name(), id, rootMessage(err));
        failStashed(Kind.UNAVAILABLE, "Couldn't load " + type.name() + "/" + id + ": " + rootMessage(err));
        return stop();
    }

    // ---- ready --------------------------------------------------------------------------------

    private Behavior<Msg> ready() {
        return Behaviors.receive(Msg.class)
                .onMessage(Invoke.class, this::invoke)
                .onMessage(GetState.class, g -> { g.replyTo().tell(Result.ok(deps.codec().encode(state))); return Behaviors.same(); })
                .onMessage(Idle.class, i -> { shard.tell(new ClusterSharding.Passivate<>(ctx.getSelf())); return Behaviors.same(); })
                .onMessage(Stop.class, s -> stop())
                .onSignal(PostStop.class, sig -> stopped())
                .build();
    }

    private Behavior<Msg> unstashReady() {
        return stash.unstashAll(ready());
    }

    private Behavior<Msg> invoke(Invoke inv) {
        long started = System.nanoTime();
        if (inv.key() != null && persistent) {
            return await(deps.store().completed(type.name(), id, inv.key()), (done, err) -> {
                if (err != null) return failed(inv, Kind.UNAVAILABLE, "Couldn't read idempotency key: " + rootMessage(err), started, null);
                if (done.isPresent()) return answerStored(inv, done.get(), started);
                return execute(inv, started);
            });
        }
        return execute(inv, started);
    }

    private Behavior<Msg> execute(Invoke inv, long started) {
        EntityType.CommandDef<S> def;
        Object arg;
        try {
            def = type.command(inv.command());
            arg = decodeArg(def, inv.arg());
        } catch (StrataException e) {
            String msg = type.name() + "." + inv.command() + ": " + e.getMessage();
            deps.observer().command(type.name(), id, inv.command(), false, e.kind(), msg, null, System.nanoTime() - started);
            tell(inv, Result.failed(e.kind(), msg));
            return unstashReady();
        }
        if (!offThread) {
            Reply<S, Object> reply;
            Invocation here = new Invocation(type.name(), id, null, def.readOnly());
            try {
                S working = working();
                reply = here.run(() -> handle(def, working, arg));
            } catch (Exception e) {
                return failed(inv, kindOf(e), type.name() + "." + inv.command() + " failed: " + rootMessage(e), started, null);
            }
            return completed(inv, def, reply, null, started, here);
        }
        if (def.readOnly() || !persistent) {
            S working = working();
            Invocation here = new Invocation(type.name(), id, null, def.readOnly());
            return await(CompletableFuture.supplyAsync(() -> unchecked(() -> here.run(() -> handle(def, working, arg))), deps.executor()), (reply, err) -> err != null
                    ? failed(inv, kindOf(err), type.name() + "." + inv.command() + " failed: " + rootMessage(err), started, null)
                    : completed(inv, def, reply, null, started, here));
        }
        // A command of an entity with services: record that it's starting, then run it.
        String invocationId = UUID.randomUUID().toString();
        return await(deps.store().begin(type.name(), id, invocationId, header(inv)), (v, err) -> err != null
                ? failed(inv, Kind.UNAVAILABLE, "Couldn't record " + type.name() + "." + inv.command() + ": " + rootMessage(err), started, null)
                : run(inv, invocationId, List.of(), started));
    }

    /** Run a recorded invocation off-thread; service calls go through its journal. */
    private Behavior<Msg> run(Invoke inv, String invocationId, List<byte[]> recorded, long started) {
        EntityType.CommandDef<S> def;
        Object arg;
        try {
            def = type.command(inv.command());
            arg = decodeArg(def, inv.arg());
        } catch (StrataException e) {
            // E.g. the command no longer exists after a code change: drop the unfinished invocation.
            ctx.getLog().error("Dropping unfinished {} on {}/{}: {}", inv.command(), type.name(), id, e.getMessage());
            return failed(inv, e.kind(), e.getMessage(), started, invocationId);
        }
        S working = working();
        ClassLoader loader = type.stateType().getClassLoader();
        Journal journal = new Journal(invocationId, recorded, (seq, entry) ->
                deps.store().record(type.name(), id, invocationId, seq, entry).toCompletableFuture().join(), deps.codec());
        Invocation here = new Invocation(type.name(), id, journal, def.readOnly());
        return await(CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().setContextClassLoader(loader);
            return unchecked(() -> here.run(() -> handle(def, working, arg)));
        }, deps.executor()), (reply, err) -> {
            if (err == null) return completed(inv, def, reply, invocationId, started, here);
            Throwable cause = unwrap(err);
            if (cause instanceof JournalWriteException) {
                // Not recorded, so it may not be over: stop; it resumes when the entity next loads.
                tell(inv, Result.failed(Kind.UNAVAILABLE, type.name() + "." + inv.command() + ": " + rootMessage(cause) + "; it resumes when the entity is next used"));
                return stop();
            }
            return failed(inv, kindOf(cause), type.name() + "." + inv.command() + " failed: " + rootMessage(cause), started, invocationId);
        });
    }

    // ---- finishing --------------------------------------------------------------------------------

    private Behavior<Msg> completed(Invoke inv, EntityType.CommandDef<S> def, Reply<S, Object> reply, String invocationId, long started, Invocation here) {
        byte[] result = deps.codec().encode(reply.result());
        List<Timer> schedule = here == null ? List.of() : here.scheduled();
        List<String> cancel = here == null ? List.of() : here.cancelled();
        if (!persistent) {
            if (reply.changed()) state = reply.state();
            deps.observer().command(type.name(), id, inv.command(), def.readOnly(), null, null, null, System.nanoTime() - started);
            if (schedule.isEmpty() && cancel.isEmpty()) {
                tell(inv, Result.ok(result));
                return unstashReady();
            }
            // No commit for ephemeral entities: their timers are written after the command.
            CompletableFuture<?>[] writes = java.util.stream.Stream.concat(
                    schedule.stream().map(t -> deps.store().schedule(t).toCompletableFuture()),
                    cancel.stream().map(c -> deps.store().cancelTimer(c).toCompletableFuture())).toArray(CompletableFuture[]::new);
            return await(CompletableFuture.allOf(writes), (v, err) -> {
                tell(inv, err == null ? Result.ok(result) : Result.failed(Kind.UNAVAILABLE, "Couldn't keep timers: " + rootMessage(err)));
                return unstashReady();
            });
        }
        if (!reply.changed() && invocationId == null && inv.key() == null && schedule.isEmpty() && cancel.isEmpty()) {
            deps.observer().command(type.name(), id, inv.command(), def.readOnly(), null, null, null, System.nanoTime() - started);
            tell(inv, Result.ok(result));
            return unstashReady();
        }
        byte[] newState = reply.changed() ? deps.codec().encode(reply.state()) : null;
        byte[] outcome = inv.key() == null ? null : okOutcome(result);
        long commitStarted = System.nanoTime();
        return await(deps.store().commit(type.name(), id, revision, new Commit(newState, invocationId, inv.key(), outcome, keepAfter(), schedule, cancel)), (rev, err) -> {
            long now = System.nanoTime();
            if (err == null) {
                if (newState != null) {
                    deps.observer().saved(type.name(), id, rev, now - commitStarted);
                    state = reply.state();
                    revision = rev;
                }
                deps.observer().command(type.name(), id, inv.command(), def.readOnly(), null, null, newState != null ? rev : null, now - started);
                tell(inv, Result.ok(result));
                return unstashReady();
            }
            Throwable cause = unwrap(err);
            if (cause instanceof StaleRevisionException) {
                // Someone else wrote this entity: never overwrite; reload, then continue.
                String msg = cause.getMessage() + "; the command was not applied";
                deps.observer().command(type.name(), id, inv.command(), false, Kind.CONFLICT, msg, null, now - started);
                tell(inv, Result.failed(Kind.CONFLICT, msg));
                return load();
            }
            String msg = "Couldn't commit " + type.name() + "/" + id + ": " + rootMessage(cause);
            deps.observer().command(type.name(), id, inv.command(), false, Kind.UNAVAILABLE, msg, null, now - started);
            tell(inv, Result.failed(Kind.UNAVAILABLE, msg));
            // A recorded invocation stays recorded: stop, so it resumes when the entity next loads.
            return invocationId != null ? stop() : unstashReady();
        });
    }

    /**
     * A command failed: nothing changes. A recorded invocation is over, so its journal goes, and a
     * key keeps the failure, so a retry gets the same answer instead of repeating effects.
     */
    private Behavior<Msg> failed(Invoke inv, Kind kind, String msg, long started, String invocationId) {
        deps.observer().command(type.name(), id, inv.command(), false, kind, msg, null, System.nanoTime() - started);
        boolean keep = inv.key() != null && kind == Kind.FAILED;
        if (persistent && (invocationId != null || keep)) {
            return await(deps.store().commit(type.name(), id, revision, new Commit(null, invocationId, keep ? inv.key() : null,
                    keep ? errorOutcome(kind, msg) : null, keepAfter(), List.of(), List.of())), (rev, err) -> {
                tell(inv, Result.failed(kind, msg));
                return err == null ? unstashReady() : stop();
            });
        }
        tell(inv, Result.failed(kind, msg));
        return unstashReady();
    }

    /** A repeated request: answer with the first one's outcome. */
    private Behavior<Msg> answerStored(Invoke inv, byte[] outcome, long started) {
        JsonNode o = tree(outcome);
        if (o.has("ok")) {
            deps.observer().command(type.name(), id, inv.command(), true, null, null, null, System.nanoTime() - started);
            tell(inv, Result.ok(bytes(o.get("ok"))));
        } else {
            Kind kind = Kind.parse(o.path("kind").asText());
            tell(inv, Result.failed(kind, o.path("error").asText()));
        }
        return unstashReady();
    }

    // ---- waiting ------------------------------------------------------------------------------

    /** Wait for asynchronous work; other messages wait in the stash, and the entity isn't passivated meanwhile. */
    @SuppressWarnings("unchecked")
    private <T> Behavior<Msg> await(CompletionStage<T> work, BiFunction<T, Throwable, Behavior<Msg>> next) {
        ctx.pipeToSelf(work, Step::new);
        return Behaviors.receive(Msg.class)
                .onMessage(Step.class, st -> next.apply((T) st.value(), st.error()))
                .onMessage(Idle.class, i -> Behaviors.same())
                .onMessage(Msg.class, m -> { stash.stash(m); return Behaviors.same(); })
                .onSignal(PostStop.class, sig -> stopped())
                .build();
    }

    private Behavior<Msg> stop() {
        deps.observer().stopped(type.name(), id);
        return Behaviors.stopped();
    }

    private Behavior<Msg> stopped() {
        deps.observer().stopped(type.name(), id);
        return Behaviors.same();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private Reply<S, Object> handle(EntityType.CommandDef<S> def, S working, Object arg) throws Exception {
        Reply<S, Object> reply = def.handler().handle(working, arg);
        if (reply == null) throw new IllegalStateException("command handler returned null; return Reply.update(...) or Reply.read(...)");
        return reply;
    }

    /** What a command works on: a copy for mutable state, so a failing command changes nothing. */
    private S working() {
        return type.mutableState() ? injected(deps.codec().copy(state, type.stateType())) : state;
    }

    private Object decodeArg(EntityType.CommandDef<S> def, byte[] arg) {
        return def.argType() == Void.class || arg == null ? null : deps.codec().decode(arg, def.argType());
    }

    /** Fill @Inject fields (a few field writes; the services were resolved at registration). */
    private S injected(S instance) {
        if (instance != null) deps.inject().into(instance);
        return instance;
    }

    private void tell(Invoke inv, Result r) {
        if (inv.replyTo() != null) inv.replyTo().tell(r);
    }

    private Instant keepAfter() {
        return Instant.now().minus(deps.resultRetention());
    }

    private byte[] header(Invoke inv) {
        ObjectNode h = JSON.createObjectNode().put("command", inv.command());
        if (inv.arg() != null) h.set("arg", tree(inv.arg()));
        if (inv.key() != null) h.put("key", inv.key());
        return write(h);
    }

    private static byte[] okOutcome(byte[] result) {
        ObjectNode o = JSON.createObjectNode();
        o.set("ok", tree(result));
        return write(o);
    }

    private static byte[] errorOutcome(Kind kind, String msg) {
        return write(JSON.createObjectNode().put("error", msg).put("kind", kind.name()));
    }

    private static JsonNode tree(byte[] b) {
        try { return JSON.readTree(b); } catch (IOException e) { throw new StrataException("not JSON", e); }
    }

    private static byte[] bytes(JsonNode n) { return write(n); }

    private static byte[] write(JsonNode n) {
        try { return JSON.writeValueAsBytes(n); } catch (IOException e) { throw new StrataException("can't write JSON", e); }
    }

    private interface Body<T> { T call() throws Exception; }

    private static <T> T unchecked(Body<T> body) {
        try { return body.call(); } catch (RuntimeException e) { throw e; } catch (Exception e) { throw new CompletionException(e); }
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) t = t.getCause();
        return t;
    }

    private static Kind kindOf(Throwable t) {
        Throwable c = unwrap(t);
        return c instanceof StrataException se ? se.kind() : Kind.FAILED;
    }

    private void failStashed(Kind kind, String why) {
        stash.forEach(m -> {
            if (m instanceof Invoke i) tell(i, Result.failed(kind, why));
            else if (m instanceof GetState g) g.replyTo().tell(Result.failed(kind, why));
        });
        stash.clear();
    }

    private static String rootMessage(Throwable t) {
        t = unwrap(t);
        while (t.getCause() != null && t.getCause() != t && !(t instanceof StrataException)) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
