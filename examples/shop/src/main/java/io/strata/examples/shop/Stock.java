package io.strata.examples.shop;

import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Query;

/** Stock of one product. POST /stock/{sku}/restock, /reserve, /release; GET /stock/{sku}/available. */
@Entity(mount = "/stock/{sku}", expose = true)
public class Stock {
    private int available;

    @Command
    public int restock(int quantity) { return available += quantity; }

    @Command
    public int reserve(int quantity) {
        if (quantity > available) throw new IllegalStateException("only " + available + " left");
        return available -= quantity;
    }

    @Command
    public int release(int quantity) { return available += quantity; }

    @Query
    public int available() { return available; }
}
