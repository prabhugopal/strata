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
