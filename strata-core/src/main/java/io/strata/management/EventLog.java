package io.strata.management;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** The most recent events on this node, in a fixed-size ring (strata.management.events.capacity). */
public final class EventLog {
    private final Event[] ring;
    private long next = 1; // seq of the next event; guarded by this

    public EventLog(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("strata.management.events.capacity must be at least 1");
        this.ring = new Event[capacity];
    }

    public synchronized long append(java.util.function.LongFunction<Event> make) {
        long seq = next++;
        ring[(int) (seq % ring.length)] = make.apply(seq);
        return seq;
    }

    /** Events with seq > {@code after} that match, oldest first, at most {@code limit}. */
    public synchronized List<Event> since(long after, int limit, Predicate<Event> filter) {
        long first = Math.max(after + 1, next - ring.length);
        List<Event> out = new ArrayList<>();
        for (long seq = first; seq < next && out.size() < limit; seq++) {
            Event e = ring[(int) (seq % ring.length)];
            if (e != null && e.seq() == seq && filter.test(e)) out.add(e);
        }
        return out;
    }

    public synchronized long lastSeq() { return next - 1; }
}
