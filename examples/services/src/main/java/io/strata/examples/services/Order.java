package io.strata.examples.services;

import io.strata.Ctx;
import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Inject;
import io.strata.annotation.Query;

/**
 * Checkout with two effects. No steps marked anywhere: the seal guarantees every effect goes
 * through these services, and Strata records each call, so a crash between the charge and the
 * email resumes without charging again.
 */
@Entity
public class Order {
    private String status = "new";
    private String receipt;
    private java.time.Instant paidAt;

    @Inject Payments payments;
    @Inject Mailer mailer;
    @Inject Ctx ctx;

    @Command
    public String checkout(long cents) throws Payments.PaymentDeclined {
        String r = payments.charge("order", cents);
        paidAt = ctx.now();                        // recorded: a resumed checkout keeps this time
        mailer.send("Thanks! Receipt " + r);
        status = "paid";
        receipt = r;
        return r;
    }

    /** A declined card isn't a failure here: the order waits for another card. */
    @Command
    public String checkoutOrWait(long cents) {
        String outcome;
        try {
            receipt = payments.charge("order", cents);
            outcome = "paid";
        } catch (Payments.PaymentDeclined e) {
            outcome = "waiting for a card (" + e.getMessage() + ")";
        }
        mailer.send("Order " + outcome);
        status = outcome;
        return outcome;
    }

    @Query
    public String status() { return status; }

    @Query
    public String receipt() { return receipt; }

    @Query
    public java.time.Instant paidAt() { return paidAt; }
}
