package io.strata.spi;

import java.time.Instant;
import java.util.List;

/**
 * Everything a command's commit writes, in one transaction: the new state (null: unchanged), the
 * end of its recorded invocation, its outcome under an idempotency key, the timers it scheduled
 * and cancelled, and how long kept outcomes live.
 */
public record Commit(byte[] state, String invocationId, String key, byte[] outcome, Instant keepAfter,
                     List<Timer> schedule, List<String> cancel) {
    public Commit {
        schedule = schedule == null ? List.of() : List.copyOf(schedule);
        cancel = cancel == null ? List.of() : List.copyOf(cancel);
    }

    public static Commit state(byte[] state) { return new Commit(state, null, null, null, null, List.of(), List.of()); }

    /** Nothing beyond a plain state write (the fast path stores can take). */
    public boolean plain() { return invocationId == null && key == null && schedule.isEmpty() && cancel.isEmpty(); }
}
