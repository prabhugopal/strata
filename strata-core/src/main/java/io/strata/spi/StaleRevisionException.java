package io.strata.spi;

import io.strata.StrataException;

/**
 * The stored revision isn't the one the writer expected: someone else wrote this entity.
 * This fences a stale owner (e.g. after a cluster split); it must reload, not overwrite.
 */
public class StaleRevisionException extends StrataException {
    public StaleRevisionException(String type, String id, long expected) {
        super("Entity " + type + "/" + id + " changed concurrently (expected revision " + expected + ")");
    }
}
