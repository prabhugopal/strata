package io.strata.examples.shop;

import java.time.Duration;

/** The shop's rules, from configuration. */
public interface ShopPolicy {
    long price(String sku);

    Duration paymentWindow();
}
