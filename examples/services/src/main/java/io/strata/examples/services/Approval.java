package io.strata.examples.services;

import io.strata.Ctx;
import io.strata.Entities;
import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Inject;
import io.strata.annotation.Query;

import java.time.Duration;

/**
 * A workflow as state + commands + a timer: request() waits for approve(), and expire() runs by
 * itself if nobody approves in time. Signals are just commands; the timeout is a durable timer.
 */
@Entity
public class Approval {
    private String status = "draft";
    private String expiry;

    @Inject Entities entities;
    @Inject Ctx ctx;

    @Command
    public String request(long waitMillis) {
        status = "waiting";
        expiry = ApprovalClient.of(entities, ctx.entityId()).later(Duration.ofMillis(waitMillis)).expire();
        return expiry;
    }

    /** Schedules, then fails: the timer must not survive the failed command. */
    @Command
    public String requestAndFail(long waitMillis) {
        ApprovalClient.of(entities, ctx.entityId()).later(Duration.ofMillis(waitMillis)).expire();
        throw new IllegalStateException("changed my mind");
    }

    @Command
    public String approve() {
        if (!status.equals("waiting")) return status;
        entities.cancel(expiry);
        status = "approved";
        return status;
    }

    @Command
    public String expire() {
        if (status.equals("waiting")) status = "expired";
        return status;
    }

    @Query
    public String status() { return status; }
}
