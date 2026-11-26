package io.strata.examples.k8s;

import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Query;

import java.util.ArrayList;
import java.util.List;

/** A bank account: each one lives on exactly one node of the cluster at a time. */
@Entity(mount = "/accounts/{id}")
public class Account {
    private long balance;
    private List<String> history = new ArrayList<>();

    @Command(post = "/deposit")
    public long deposit(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
        balance += amount;
        history.add("deposit " + amount);
        return balance;
    }

    @Command(post = "/withdraw")
    public long withdraw(long amount) {
        if (amount > balance) throw new IllegalStateException("insufficient funds");
        balance -= amount;
        history.add("withdraw " + amount);
        return balance;
    }

    @Query(get = "")
    public long balance() { return balance; }

    @Query(get = "/history")
    public List<String> history() { return history; }
}
