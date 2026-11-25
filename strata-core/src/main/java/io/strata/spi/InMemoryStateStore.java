package io.strata.spi;

import io.strata.StrataSettings;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/** strata.store.kind = "memory": state kept in this JVM. For tests and ephemeral use. */
public final class InMemoryStateStore implements StateStore {
    private final ConcurrentHashMap<String, StoredState> data = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> updated = new ConcurrentHashMap<>();

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
            updated.put(key, Instant.now());
            return CompletableFuture.completedFuture(written[0]);
        } catch (StaleRevisionException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<List<StoredEntry>> list(String type, String afterId, int limit) {
        String prefix = type + "/";
        List<StoredEntry> out = data.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(e -> new StoredEntry(e.getKey().substring(prefix.length()), e.getValue().revision(), updated.get(e.getKey())))
                .filter(e -> afterId == null || e.id().compareTo(afterId) > 0)
                .sorted(java.util.Comparator.comparing(StoredEntry::id))
                .limit(limit)
                .toList();
        return CompletableFuture.completedFuture(out);
    }

    public static final class Provider implements StateStoreProvider {
        @Override public String kind() { return "memory"; }
        @Override public StateStore create(StrataSettings settings, Executor io) { return new InMemoryStateStore(); }
    }
}
