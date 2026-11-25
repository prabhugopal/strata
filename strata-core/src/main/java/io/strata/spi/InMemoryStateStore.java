package io.strata.spi;

import io.strata.StrataSettings;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** strata.store.kind = "memory": state kept in this JVM. For tests and ephemeral use. */
public final class InMemoryStateStore implements StateStore {
    private final ConcurrentHashMap<String, StoredState> data = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<Optional<StoredState>> load(String type, String id) {
        return CompletableFuture.completedFuture(Optional.ofNullable(data.get(type + "/" + id)));
    }

    @Override
    public CompletionStage<Long> save(String type, String id, long expectedRevision, byte[] state) {
        String key = type + "/" + id;
        long[] written = new long[1];
        try {
            data.compute(key, (k, current) -> {
                long rev = current == null ? 0 : current.revision();
                if (rev != expectedRevision) throw new StaleRevisionException(type, id, expectedRevision);
                written[0] = rev + 1;
                return new StoredState(rev + 1, state.clone());
            });
            return CompletableFuture.completedFuture(written[0]);
        } catch (StaleRevisionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public static final class Provider implements StateStoreProvider {
        @Override public String kind() { return "memory"; }
        @Override public StateStore create(StrataSettings settings, Executor io) { return new InMemoryStateStore(); }
    }
}
