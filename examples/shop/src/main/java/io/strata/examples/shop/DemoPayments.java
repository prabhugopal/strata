package io.strata.examples.shop;

import io.strata.annotation.Provides;

import java.util.UUID;

/** A stand-in payment provider: cards ending in 0000 are declined. Found at start (@Provides). */
@Provides
public class DemoPayments implements Payments {
    @Override
    public String charge(String order, long cents, String card) throws Declined {
        if (card.endsWith("0000")) throw new Declined("card declined");
        return "R-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
