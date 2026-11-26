package io.strata.examples.services;

import io.strata.Ctx;
import io.strata.Entities;
import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Inject;
import io.strata.annotation.Query;

/** Moves money between two wallets: two other entities, each called exactly once. */
@Entity
public class Transfer {
    public record Request(String from, String to, long cents) {}

    private String status = "new";

    @Inject Entities entities;
    @Inject Ctx ctx;
    @Inject Notifier notifier;

    @Command
    public String run(Request r) {
        WalletClient.of(entities, r.from()).blocking().withdraw(r.cents());
        notifier.notify("withdrew " + r.cents() + " from " + r.from());
        WalletClient.of(entities, r.to()).blocking().deposit(r.cents());
        status = "done " + ctx.entityId() + " at " + ctx.now();
        return status;
    }

    /** Would wait for itself forever: refused. */
    @Command
    public String askMyself() {
        return TransferClient.of(entities, ctx.entityId()).blocking().status();
    }

    @Query
    public String status() { return status; }
}
