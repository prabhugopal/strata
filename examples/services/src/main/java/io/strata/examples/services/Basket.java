package io.strata.examples.services;

import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Inject;
import io.strata.annotation.Query;

import java.util.ArrayList;
import java.util.List;

/** A basket that prices items through a service. Only {@code items} and {@code total} are state. */
@Entity
public class Basket {
    private List<String> items = new ArrayList<>();
    private long total;

    @Inject Pricing pricing;                 // by type
    @Inject(name = "discounts") Pricing discounts;   // by name

    @Command
    public long add(String item) {
        items.add(item);
        total += pricing.price(item) - discounts.price(item);
        return total;
    }

    @Query
    public long total() { return total; }

    @Query
    public List<String> items() { return items; }
}
