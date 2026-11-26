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
    private record Journal(String invocationId, byte[] header, java.util.List<byte[]> entries) {}
    private record Outcome(byte[] bytes, Instant at) {}
    private final ConcurrentHashMap<String, Journal> journals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Outcome> outcomes = new ConcurrentHashMap<>();

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
    public synchronized CompletionStage<Void> begin(String type, String id, String invocationId, byte[] header) {
        journals.put(type + "/" + id, new Journal(invocationId, header.clone(), new java.util.ArrayList<>()));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Void> record(String type, String id, String invocationId, int seq, byte[] entry) {
        Journal j = journals.get(type + "/" + id);
        if (j == null || !j.invocationId().equals(invocationId)) {
            return CompletableFuture.failedFuture(new StaleRevisionException(type, id, -1));
        }
        if (j.entries().size() != seq - 1) return CompletableFuture.failedFuture(new IllegalStateException("journal entry " + seq + " out of order"));
        j.entries().add(entry.clone());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Optional<Pending>> pending(String type, String id) {
        Journal j = journals.get(type + "/" + id);
        return CompletableFuture.completedFuture(j == null ? Optional.empty()
                : Optional.of(new Pending(j.invocationId(), j.header(), java.util.List.copyOf(j.entries()))));
    }

    @Override
    public CompletionStage<Optional<byte[]>> completed(String type, String id, String key) {
        Outcome o = outcomes.get(type + "/" + id + "\0" + key);
        return CompletableFuture.completedFuture(Optional.ofNullable(o).map(Outcome::bytes));
    }

    @Override
    public CompletionStage<Long> commit(String type, String id, long expectedRevision, Commit c) {
        // Plain commands take the lock-free path; only invocations, keys and timers need the lock.
        if (c.plain()) {
            return c.state() == null ? CompletableFuture.completedFuture(expectedRevision) : save(type, id, expectedRevision, c.state());
        }
        return commitLocked(type, id, expectedRevision, c);
    }

    private synchronized CompletionStage<Long> commitLocked(String type, String id, long expectedRevision, Commit c) {
        String k = type + "/" + id;
        StoredState current = data.get(k);
        long rev = current == null ? 0 : current.revision();
        if (rev != expectedRevision) return CompletableFuture.failedFuture(new StaleRevisionException(type, id, expectedRevision));
        if (c.invocationId() != null) {
            Journal j = journals.get(k);
            if (j != null && j.invocationId().equals(c.invocationId())) journals.remove(k);
        }
        long written = rev;
        if (c.state() != null) {
            written = rev + 1;
            data.put(k, new StoredState(written, c.state().clone()));
            updated.put(k, Instant.now());
        }
        if (c.key() != null) outcomes.put(k + "\0" + c.key(), new Outcome(c.outcome().clone(), Instant.now()));
        if (c.keepAfter() != null) outcomes.entrySet().removeIf(e -> e.getKey().startsWith(k + "\0") && e.getValue().at().isBefore(c.keepAfter()));
        for (Timer t : c.schedule()) timers.put(t.id(), new Claimed(t, null, null));
        for (String tid : c.cancel()) timers.remove(tid);
        return CompletableFuture.completedFuture(written);
    }

    // ---- leases ----

    private record Held(String owner, Instant until) {}
    private final ConcurrentHashMap<String, Held> leases = new ConcurrentHashMap<>();

    @Override
    public synchronized CompletionStage<Boolean> acquireLease(String name, String owner, java.time.Duration ttl) {
        Instant now = Instant.now();
        Held h = leases.get(name);
        if (h != null && !h.owner().equals(owner) && h.until().isAfter(now)) return CompletableFuture.completedFuture(false);
        leases.put(name, new Held(owner, now.plus(ttl)));
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletionStage<java.util.Optional<String>> leaseHolder(String name) {
        Held h = leases.get(name);
        return CompletableFuture.completedFuture(h != null && h.until().isAfter(Instant.now()) ? java.util.Optional.of(h.owner()) : java.util.Optional.empty());
    }

    @Override
    public synchronized CompletionStage<Boolean> releaseLease(String name, String owner) {
        Held h = leases.get(name);
        return CompletableFuture.completedFuture(h != null && h.owner().equals(owner) && leases.remove(name, h));
    }

    // ---- timers ----

    private record Claimed(Timer timer, String by, Instant until) {}
    private final ConcurrentHashMap<String, Claimed> timers = new ConcurrentHashMap<>();

    @Override
    public synchronized CompletionStage<Void> schedule(Timer timer) {
        timers.put(timer.id(), new Claimed(timer, null, null));
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletionStage<Boolean> cancelTimer(String timerId) {
        return CompletableFuture.completedFuture(timers.remove(timerId) != null);
    }

    @Override
    public synchronized CompletionStage<List<Timer>> claimDue(java.util.Collection<String> types, Instant now, int limit, String claimant, Instant claimUntil) {
        List<Timer> out = timers.values().stream()
                .filter(c -> types.contains(c.timer().type()) && !c.timer().due().isAfter(now) && (c.until() == null || c.until().isBefore(now)))
                .map(Claimed::timer).sorted(java.util.Comparator.comparing(Timer::due)).limit(limit).toList();
        for (Timer t : out) timers.put(t.id(), new Claimed(t, claimant, claimUntil));
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletionStage<List<Timer>> timers(String type, String entityId, int limit) {
        return CompletableFuture.completedFuture(timers.values().stream().map(Claimed::timer)
                .filter(t -> t.type().equals(type) && (entityId == null || t.entityId().equals(entityId)))
                .sorted(java.util.Comparator.comparing(Timer::due)).limit(limit).toList());
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
