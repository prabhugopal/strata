package io.strata.examples.counter;

import io.strata.Strata;
import io.strata.StrataRuntime;

/**
 * Starts a Strata node. @Entity classes are found and registered, and strata-http serves their
 * routes; settings come from application.conf (and environment variables).
 */
public final class Main {
    public static void main(String[] args) {
        StrataRuntime strata = Strata.start();
        Runtime.getRuntime().addShutdownHook(new Thread(strata::close, "strata-shutdown"));
    }
}
