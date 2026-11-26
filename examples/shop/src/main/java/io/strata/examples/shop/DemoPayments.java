package io.strata.examples.shop;

import io.strata.Strata;
import io.strata.annotation.Provides;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A stand-in payment provider: cards ending in 0000 are declined. Found at start (@Provides).
 * Like a real provider it takes an idempotency key ({@link Strata#callKey()}): a charge retried
 * after a crash gets the first receipt back instead of charging again.
 */
@Provides
public class DemoPayments implements Payments {
    private final Map<String, String> receipts = new ConcurrentHashMap<>();

    @Override
    public String charge(String order, long cents, String card) throws Declined {
        if (card.endsWith("0000")) throw new Declined("card declined");
        String key = Strata.callKey().orElse(UUID.randomUUID().toString());
        return receipts.computeIfAbsent(key, k -> "R-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
