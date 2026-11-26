package io.strata.durability;

import io.strata.StrataException;

/**
 * Resuming an interrupted command, the code didn't make the calls the record says it made (it
 * changed in between). Continuing could repeat or skip effects, so by default the entity stalls
 * with its record kept (strata.durability.on-divergence).
 */
public final class ReplayDivergedException extends StrataException {
    public ReplayDivergedException(String message) { super(Kind.UNAVAILABLE, message); }
}
