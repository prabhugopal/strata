package io.strata.annotation;

/** Shared constants for the annotations' HTTP attributes. */
public final class Http {
    private Http() {}

    /**
     * Marks an HTTP attribute as unset. (An empty string is a real path: the mount itself.)
     * A NUL character can never appear in a URL path, so it can't clash with one.
     */
    public static final String NONE = "\0";
}
