package io.strata;

/** A failure reported by Strata (unknown command, handler error, store failure, timeout). */
public class StrataException extends RuntimeException {
    public StrataException(String message) { super(message); }
    public StrataException(String message, Throwable cause) { super(message, cause); }
}
