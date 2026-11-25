package io.strata.spi;

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

    @Override
    default void close() {}
}
