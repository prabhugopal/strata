package io.strata.pekko;

import io.strata.Codec;
import io.strata.Durability;
import io.strata.EntityType;
import io.strata.Reply;
import io.strata.StrataException;
import io.strata.StrataException.Kind;
import io.strata.spi.StaleRevisionException;
import io.strata.spi.StateStore;
import io.strata.spi.StoredState;
import io.strata.pekko.Protocol.*;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.apache.pekko.actor.typed.javadsl.StashBuffer;
import org.apache.pekko.cluster.sharding.typed.javadsl.ClusterSharding;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * One entity. Owns its state and applies commands one at a time (single writer). A persistent
 * entity loads its state when it starts and commits each change before replying; commands that
 * arrive meanwhile wait in the stash. It passivates after an idle period and reloads on next use.
 */
final class EntityActor<S> {
    record Deps(Codec codec, StateStore store, Durability durability, Duration passivateAfter, int stashCapacity) {}

    private final EntityType<S> type;
    private final String id;
    private final Deps deps;
    private final ActorContext<Msg> ctx;
    private final StashBuffer<Msg> stash;
    private final ActorRef<ClusterSharding.ShardCommand> shard;

    private EntityActor(EntityType<S> type, String id, Deps deps, ActorContext<Msg> ctx, StashBuffer<Msg> stash,
                        ActorRef<ClusterSharding.ShardCommand> shard) {
        this.type = type; this.id = id; this.deps = deps; this.ctx = ctx; this.stash = stash; this.shard = shard;
    }

    static <S> Behavior<Msg> create(EntityType<S> type, String id, Deps deps, ActorRef<ClusterSharding.ShardCommand> shard) {
        return Behaviors.withStash(deps.stashCapacity(), stash -> Behaviors.setup(ctx -> {
            ctx.setReceiveTimeout(deps.passivateAfter(), Idle.INSTANCE);
            return new EntityActor<>(type, id, deps, ctx, stash, shard).start();
        }));
    }

    private Behavior<Msg> start() {
        if (deps.durability() == Durability.EPHEMERAL) return ready(type.initialState(), 0);
        ctx.pipeToSelf(deps.store().load(type.name(), id), Loaded::new);
        return loading();
    }

    private Behavior<Msg> loading() {
        return Behaviors.receive(Msg.class)
                .onMessage(Loaded.class, l -> {
                    if (l.error() != null) {
                        ctx.getLog().error("Couldn't load {}/{}; stopping (it reloads on next use)", type.name(), id, l.error());
                        failStashed(Kind.UNAVAILABLE, "Couldn't load " + type.name() + "/" + id + ": " + rootMessage(l.error()));
                        return Behaviors.stopped();
                    }
                    S state = l.state().map(s -> deps.codec().decode(s.state(), type.stateType())).orElseGet(type::initialState);
                    long rev = l.state().map(StoredState::revision).orElse(0L);
                    return stash.unstashAll(ready(state, rev));
                })
                .onMessage(Msg.class, m -> { stash.stash(m); return Behaviors.same(); })
                .build();
    }

    private Behavior<Msg> ready(S state, long revision) {
        return Behaviors.receive(Msg.class)
                .onMessage(Invoke.class, inv -> invoke(state, revision, inv))
                .onMessage(GetState.class, g -> { g.replyTo().tell(Result.ok(deps.codec().encode(state))); return Behaviors.same(); })
                .onMessage(Idle.class, i -> { shard.tell(new ClusterSharding.Passivate<>(ctx.getSelf())); return Behaviors.same(); })
                .onMessage(Stop.class, s -> Behaviors.stopped())
                .build();
    }

    private Behavior<Msg> invoke(S state, long revision, Invoke inv) {
        Reply<S, Object> reply;
        EntityType.CommandDef<S> def;
        Object arg;
        try {
            def = type.command(inv.command());
            arg = def.argType() == Void.class || inv.arg() == null ? null : deps.codec().decode(inv.arg(), def.argType());
        } catch (StrataException e) {
            inv.replyTo().tell(Result.failed(e.kind(), type.name() + "." + inv.command() + ": " + e.getMessage()));
            return Behaviors.same();
        }
        try {
            // Mutable state is changed in place, so the handler works on a copy: if it throws,
            // the committed state is untouched.
            S working = type.mutableState() ? deps.codec().copy(state, type.stateType()) : state;
            reply = def.handler().handle(working, arg);
            if (reply == null) throw new IllegalStateException("command handler returned null; return Reply.update(...) or Reply.read(...)");
        } catch (Exception e) {
            Kind kind = e instanceof StrataException se ? se.kind() : Kind.FAILED;
            inv.replyTo().tell(Result.failed(kind, type.name() + "." + inv.command() + " failed: " + rootMessage(e)));
            return Behaviors.same();
        }
        byte[] result = deps.codec().encode(reply.result());
        if (!reply.changed()) {
            inv.replyTo().tell(Result.ok(result));
            return Behaviors.same();
        }
        if (deps.durability() == Durability.EPHEMERAL) {
            inv.replyTo().tell(Result.ok(result));
            return ready(reply.state(), revision);
        }
        ctx.pipeToSelf(deps.store().save(type.name(), id, revision, deps.codec().encode(reply.state())), Saved::new);
        return saving(state, revision, reply.state(), result, inv.replyTo());
    }

    private Behavior<Msg> saving(S before, long revision, S after, byte[] result, ActorRef<Result> replyTo) {
        return Behaviors.receive(Msg.class)
                .onMessage(Saved.class, saved -> {
                    if (saved.error() == null) {
                        replyTo.tell(Result.ok(result));
                        return stash.unstashAll(ready(after, saved.revision()));
                    }
                    Throwable cause = saved.error() instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : saved.error();
                    if (cause instanceof StaleRevisionException) {
                        // Someone else wrote this entity: never overwrite; reload, then continue.
                        replyTo.tell(Result.failed(Kind.CONFLICT, cause.getMessage() + "; the command was not applied"));
                        ctx.pipeToSelf(deps.store().load(type.name(), id), Loaded::new);
                        return loading();
                    }
                    replyTo.tell(Result.failed(Kind.UNAVAILABLE, "Couldn't commit " + type.name() + "/" + id + ": " + rootMessage(cause)));
                    return stash.unstashAll(ready(before, revision));
                })
                .onMessage(Msg.class, m -> { stash.stash(m); return Behaviors.same(); })
                .build();
    }

    private void failStashed(Kind kind, String why) {
        stash.forEach(m -> {
            if (m instanceof Invoke i) i.replyTo().tell(Result.failed(kind, why));
            else if (m instanceof GetState g) g.replyTo().tell(Result.failed(kind, why));
        });
        stash.clear();
    }

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }
}
