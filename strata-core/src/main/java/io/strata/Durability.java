package io.strata;

import java.util.Locale;

/** How much an entity's state survives. Durable steps (journaled workflows) come in M1. */
public enum Durability {
    /** State lives in memory only; lost when the entity stops. */
    EPHEMERAL,
    /** State is committed to the store before each reply and reloaded after restarts. */
    PERSISTENT;

    public static Durability parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown durability \"" + value + "\" (ephemeral | persistent)", e);
        }
    }
}
