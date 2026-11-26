package io.strata.examples.shop;

/** Charges cards. In production a payment provider's client. */
public interface Payments {
    String charge(String order, long cents, String card) throws Declined;

    class Declined extends Exception {
        public Declined(String message) { super(message); }
    }
}
