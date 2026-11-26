package io.strata.spi;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Where persistent entity state is committed. Implementations must be safe for concurrent use. */
public interface StateStore extends AutoCloseable {
    CompletionStage<Optional<StoredState>> load(String type, String id);

    /**
     * Write {@code state} if the stored revision is still {@code expectedRevision} (0 = not yet
     * stored). Completes with the new revision, or fails with {@link StaleRevisionException}.
     */
    CompletionStage<Long> save(String type, String id, long expectedRevision, byte[] state);

    // ---- durable invocations (entities that use services) and idempotency keys --------------
    // A store that implements these lets Strata resume a command after a crash and answer a
    // repeated request from its stored outcome. Memory and JDBC stores do.

    /** Record that an invocation is starting; {@code header} says what it is (command, argument, key). */
    default CompletionStage<Void> begin(String type, String id, String invocationId, byte[] header) {
        return unsupported("record invocations");
    }

    /** Append the result of the invocation's {@code seq}-th service call (1-based). */
    default CompletionStage<Void> record(String type, String id, String invocationId, int seq, byte[] entry) {
        return unsupported("record invocations");
    }

    /** The entity's unfinished invocation, if a node stopped in the middle of one. */
    default CompletionStage<Optional<Pending>> pending(String type, String id) {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }

    /** The stored outcome of an earlier request with this idempotency key. */
    default CompletionStage<Optional<byte[]>> completed(String type, String id, String key) {
        return unsupported("keep idempotency keys");
    }

    /**
     * Finish an invocation in one transaction: write {@code state} (null: unchanged) if the stored
     * revision is still {@code expectedRevision}, drop the invocation's journal, keep
     * {@code outcome} under {@code key} (when given) and forget outcomes older than {@code keepAfter}.
     * Completes with the revision now stored.
     */
    default CompletionStage<Long> commit(String type, String id, long expectedRevision, byte[] state,
                                         String invocationId, String key, byte[] outcome, java.time.Instant keepAfter) {
        if (invocationId == null && key == null && state != null) return save(type, id, expectedRevision, state);
        if (invocationId == null && key == null) return java.util.concurrent.CompletableFuture.completedFuture(expectedRevision);
        return unsupported("record invocations");
    }

    private <T> CompletionStage<T> unsupported(String what) {
        return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException(
                getClass().getSimpleName() + " can't " + what + "; use strata.store.kind = memory or jdbc"));
    }

    /**
     * Stored entities of a type in id order, after {@code afterId} (null for the start), at most
     * {@code limit}. For management tools; stores that can't list return an empty list.
     */
    default CompletionStage<List<StoredEntry>> list(String type, String afterId, int limit) {
        return java.util.concurrent.CompletableFuture.completedFuture(List.of());
    }

    @Override
    default void close() {}
}
