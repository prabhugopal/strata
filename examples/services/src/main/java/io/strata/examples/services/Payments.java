package io.strata.examples.services;

/** Charges money: an effect that must never happen twice. */
public interface Payments {
    String charge(String order, long cents) throws PaymentDeclined;

    class PaymentDeclined extends Exception {
        public PaymentDeclined(String message) { super(message); }
    }
}
