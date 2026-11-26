package io.strata.examples.shop;

import io.strata.Ctx;
import io.strata.Entities;
import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Inject;
import io.strata.annotation.Query;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * An order: place it (stock is reserved), pay within the payment window, or it expires by itself
 * and the stock goes back. Only business logic: no wiring, no threads, no retries. Strata keeps
 * it consistent across crashes: each stock reservation, charge and email happens exactly once.
 *
 * POST /orders/{id}/place, /pay, /expire; GET /orders/{id}/status, /summary.
 */
@Entity(mount = "/orders/{id}", expose = true)
public class Order {
    public record Line(String sku, int quantity) {}

    public enum Status { NEW, AWAITING_PAYMENT, PAID, EXPIRED }

    private Status status = Status.NEW;
    private List<Line> lines = new ArrayList<>();
    private long total;
    private Instant placedAt;
    private String receipt;
    private String expiryTimer;

    @Inject Ctx ctx;               // time, randomness, own id: recorded, so replays see the same
    @Inject Entities entities;     // other entities (Stock), each call exactly once
    @Inject Payments payments;     // the outside world: only through services, every call recorded
    @Inject Mailer mailer;
    @Inject ShopPolicy policy;

    @Command
    public Status place(List<Line> order) {
        if (status != Status.NEW) throw new IllegalStateException("already " + status);
        List<Line> reserved = new ArrayList<>();
        try {
            for (Line l : order) {
                StockClient.of(entities, l.sku()).blocking().reserve(l.quantity());
                reserved.add(l);
            }
        } catch (RuntimeException outOfStock) {
            release(reserved);          // compensate what was already reserved, then report
            throw outOfStock;
        }
        lines = new ArrayList<>(order);
        total = order.stream().mapToLong(l -> policy.price(l.sku()) * l.quantity()).sum();
        placedAt = ctx.now();
        status = Status.AWAITING_PAYMENT;
        // A durable timer: expire() runs by itself if nobody pays in time, even if this node dies.
        expiryTimer = OrderClient.of(entities, ctx.entityId()).later(policy.paymentWindow()).expire();
        return status;
    }

    @Command
    public String pay(String card) throws Payments.Declined {
        if (status != Status.AWAITING_PAYMENT) throw new IllegalStateException("can't pay an order that's " + status);
        receipt = payments.charge(ctx.entityId(), total, card);
        entities.cancel(expiryTimer);
        mailer.send("Order " + ctx.entityId() + " paid: " + total + " cents, receipt " + receipt);
        status = Status.PAID;
        return receipt;
    }

    /** Runs by itself when the payment window ends (or call it to give up early). */
    @Command
    public Status expire() {
        if (status != Status.AWAITING_PAYMENT) return status;
        release(lines);
        status = Status.EXPIRED;
        return status;
    }

    @Query
    public Status status() { return status; }

    @Query
    public String summary() {
        return status + ": " + lines.size() + " lines, " + total + " cents" + (receipt == null ? "" : ", receipt " + receipt);
    }

    private void release(List<Line> ls) {
        for (Line l : ls) StockClient.of(entities, l.sku()).blocking().release(l.quantity());
    }
}
