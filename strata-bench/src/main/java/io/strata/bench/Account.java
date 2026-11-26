package io.strata.bench;

import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Query;

import java.util.ArrayList;
import java.util.List;

/** A realistic small entity: a number and a bounded history list (so copies aren't trivial). */
@Entity
public class Account {
    private long balance;
    private List<Long> recent = new ArrayList<>();

    @Command
    public long deposit(long amount) {
        balance += amount;
        recent.add(amount);
        if (recent.size() > 10) recent.removeFirst();
        return balance;
    }

    @Query
    public long balance() { return balance; }
}
