package io.strata.management;

import io.strata.StrataException;

/**
 * What a runtime reports about its entities, for metrics, the event log and live-entity
 * listings. Runtimes call it from entity code, so implementations must be fast and thread-safe.
 */
public interface EntityObserver {
    void activated(String type, String id);

    void stopped(String type, String id);

    /**
     * A command or query finished; {@code failure} is null on success, {@code revision} is the
     * committed revision after it (null when nothing was written).
     */
    void command(String type, String id, String command, boolean readOnly, StrataException.Kind failure, String error, Long revision, long nanos);

    void saved(String type, String id, long revision, long nanos);

    void loadFailed(String type, String id, String error);

    EntityObserver NONE = new EntityObserver() {
        @Override public void activated(String type, String id) {}
        @Override public void stopped(String type, String id) {}
        @Override public void command(String type, String id, String command, boolean readOnly, StrataException.Kind failure, String error, Long revision, long nanos) {}
        @Override public void saved(String type, String id, long revision, long nanos) {}
        @Override public void loadFailed(String type, String id, String error) {}
    };
}
