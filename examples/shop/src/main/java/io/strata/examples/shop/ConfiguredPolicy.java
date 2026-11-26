package io.strata.examples.shop;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.strata.annotation.Provides;

import java.time.Duration;

/** Prices and the payment window from application.conf (shop.*). Services may read config; entities can't. */
@Provides
public class ConfiguredPolicy implements ShopPolicy {
    private final Config shop = ConfigFactory.load().getConfig("shop");

    @Override
    public long price(String sku) {
        if (!shop.hasPath("prices." + sku)) throw new IllegalArgumentException("unknown product " + sku);
        return shop.getLong("prices." + sku);
    }

    @Override
    public Duration paymentWindow() { return shop.getDuration("payment-window"); }
}
