package io.strata.management;

import io.strata.StrataException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Collects what entities report on this node into metrics, the event log and the set of live
 * (in-memory) entities. Runtime-independent: any runtime feeds it through {@link EntityObserver}.
 */
public final class Observability implements EntityObserver {
    private final ManagementSettings settings;
    private final Supplier<String> node;
    private final Metrics metrics;
    private final EventLog events;
    private final Map<String, Set<String>> live = new ConcurrentHashMap<>();

    private static final List<String> TYPE = List.of("type");
    private static final List<String> TYPE_COMMAND = List.of("type", "command");
    private static final List<String> TYPE_COMMAND_OUTCOME = List.of("type", "command", "outcome");

    public Observability(ManagementSettings settings, Supplier<String> node) {
        this.settings = settings;
        this.node = node;
        this.metrics = new Metrics(settings.latencyBuckets());
        this.events = new EventLog(settings.eventCapacity());
        metrics.gauge("strata_live_entities", "Entities in memory on this node", TYPE, () -> {
            Map<List<String>, Double> out = new LinkedHashMap<>();
            live.forEach((t, ids) -> out.put(List.of(t), (double) ids.size()));
            return out;
        });
    }

    public Metrics metrics() { return metrics; }
    public EventLog events() { return events; }

    /** Ids of entities of {@code type} in memory on this node. */
    public Set<String> live(String type) { return Set.copyOf(live.getOrDefault(type, Set.of())); }

    public boolean isLive(String type, String id) { return live.getOrDefault(type, Set.of()).contains(id); }

    private void event(String kind, String type, String id, String command, Long micros, Long revision, StrataException.Kind errorKind, String error) {
        events.append(seq -> new Event(seq, Instant.now(), node.get(), kind, type, id, command, micros, revision,
                errorKind == null ? null : errorKind.name(), error));
    }

    @Override
    public void activated(String type, String id) {
        live.computeIfAbsent(type, t -> ConcurrentHashMap.newKeySet()).add(id);
        metrics.counter("strata_activations_total", "Entities loaded into memory", TYPE, List.of(type), 1);
        event("activated", type, id, null, null, null, null, null);
    }

    @Override
    public void stopped(String type, String id) {
        Set<String> ids = live.get(type);
        if (ids == null || !ids.remove(id)) return; // already reported (stop paths may overlap)
        metrics.counter("strata_stops_total", "Entities stopped (passivated, handed over or failed)", TYPE, List.of(type), 1);
        event("stopped", type, id, null, null, null, null, null);
    }

    @Override
    public void command(String type, String id, String command, boolean readOnly, StrataException.Kind failure, String error, Long revision, long nanos) {
        String outcome = failure == null ? "ok" : failure.name().toLowerCase(java.util.Locale.ROOT);
        metrics.counter("strata_commands_total", "Commands and queries handled, by outcome", TYPE_COMMAND_OUTCOME, List.of(type, command, outcome), 1);
        metrics.observe("strata_command_duration_seconds", "Time to handle a command, including its commit", TYPE_COMMAND, List.of(type, command), nanos);
        if (readOnly && failure == null && !settings.eventsIncludeQueries()) return;
        event(failure != null ? "failed" : readOnly ? "query" : "command", type, id, command, nanos / 1000, revision, failure, error);
    }

    @Override
    public void saved(String type, String id, long revision, long nanos) {
        metrics.counter("strata_saves_total", "State commits", TYPE, List.of(type), 1);
        metrics.observe("strata_save_duration_seconds", "Time to commit state", TYPE, List.of(type), nanos);
    }

    @Override
    public void loadFailed(String type, String id, String error) {
        metrics.counter("strata_load_failures_total", "State loads that failed", TYPE, List.of(type), 1);
        event("load-failed", type, id, null, null, null, StrataException.Kind.UNAVAILABLE, error);
    }
}
