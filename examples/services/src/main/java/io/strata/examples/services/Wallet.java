package io.strata.examples.services;

import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Query;

@Entity
public class Wallet {
    private long balance;

    @Command
    public long deposit(long cents) { return balance += cents; }

    @Command
    public long withdraw(long cents) {
        if (cents > balance) throw new IllegalStateException("insufficient funds");
        return balance -= cents;
    }

    @Query
    public long balance() { return balance; }
}
