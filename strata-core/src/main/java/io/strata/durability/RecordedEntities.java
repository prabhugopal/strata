package io.strata.durability;

import io.strata.Entities;
import io.strata.EntityRef;
import io.strata.EntityType;
import io.strata.StrataException;
import io.strata.TypeRef;

import java.lang.reflect.Type;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * The {@link Entities} entities inject. Inside a recorded invocation each call waits for its
 * result, is recorded, and carries the key {@code <invocation>/<call number>}, so the target
 * applies it once even if this invocation resumes after the call was sent but before it was
 * recorded. Elsewhere calls go straight through.
 */
public final class RecordedEntities implements Entities {
    private final Entities runtime;

    public RecordedEntities(Entities runtime) { this.runtime = runtime; }

    @Override
    public <S> EntityRef<S> ref(EntityType<S> type, String id) {
        Invocation here = Invocation.current();
        if (here != null && here.type().equals(type.name()) && here.id().equals(id)) {
            throw new StrataException(StrataException.Kind.INVALID_ARGUMENT,
                    type.name() + "/" + id + " can't call itself (it handles one command at a time, so it would wait forever)");
        }
        return new Ref<>(runtime.ref(type, id), type.name() + "/" + id);
    }

    private interface Send<S, R> { CompletionStage<R> send(EntityRef<S> ref); }

    private record Ref<S>(EntityRef<S> target, String name) implements EntityRef<S> {
        @Override public String id() { return target.id(); }
        @Override public EntityRef<S> withIdempotencyKey(String key) { return new Ref<>(target.withIdempotencyKey(key), name); }

        @Override public <R> CompletionStage<R> call(String command, Class<R> resultType) { return call(command, null, resultType); }

        @Override
        public <R> CompletionStage<R> call(String command, Object arg, Class<R> resultType) {
            return recorded(command, resultType, k -> k.call(command, arg, resultType));
        }

        @Override
        public <R> CompletionStage<R> call(String command, Object arg, TypeRef<R> resultType) {
            return recorded(command, resultType.type(), k -> k.call(command, arg, resultType));
        }

        @Override
        public CompletionStage<byte[]> callJson(String command, byte[] jsonArg) {
            return recorded(command, byte[].class, k -> k.callJson(command, jsonArg));
        }

        @Override
        public CompletionStage<S> state() { return target.state(); }


        @SuppressWarnings("unchecked")
        private <R> CompletionStage<R> recorded(String command, Type resultType, Send<S, R> send) {
            Journal j = Journal.current();
            if (j == null) return send.send(target);
            try {
                Object r = j.call("call " + name + "." + command, resultType, (Journal.Real) seq ->
                        join(send.send(target.withIdempotencyKey(j.invocationId() + "/" + seq))));
                return CompletableFuture.completedFuture((R) r);
            } catch (Throwable t) {
                return CompletableFuture.failedFuture(t);
            }
        }

        private static Object join(CompletionStage<?> s) throws Exception {
            try {
                return s.toCompletableFuture().join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof Exception ex) throw ex;
                throw e;
            }
        }
    }
}
