package io.strata.examples.services;

import io.strata.Ctx;
import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Inject;
import io.strata.annotation.Query;

import java.time.Instant;

/** Uses only Ctx: runs inline (nothing to interrupt), with real time and randomness. */
@Entity
public class Stamp {
    private Instant at;
    private String who;
    private long nonce;

    @Inject Ctx ctx;

    @Command
    public String stamp() {
        at = ctx.now();
        who = ctx.entityType() + "/" + ctx.entityId();
        nonce = ctx.randomLong();
        return who;
    }

    @Query
    public Instant at() { return at; }
}
