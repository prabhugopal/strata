package io.strata.examples.counter;

import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Query;

/**
 * A durable counter: just business logic. Strata generates the entity type, registers it,
 * serves these routes and commits {@code count} before every reply.
 */
@Entity(mount = "/counters/{id}")
public class Counter {
    private long count;

    @Command(post = "/increment")
    public long increment() { return ++count; }

    @Command(post = "/add")
    public long add(long amount) {
        if (amount < 0) throw new IllegalArgumentException("amount must not be negative");
        return count += amount;
    }

    @Query(get = "")
    public long count() { return count; }
}
