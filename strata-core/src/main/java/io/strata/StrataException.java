package io.strata;

/** A failure reported by Strata, with a {@link Kind} callers (and HTTP layers) can act on. */
public class StrataException extends RuntimeException {

    /** What went wrong, independent of the message. */
    public enum Kind {
        /** The argument couldn't be decoded or was rejected. */
        INVALID_ARGUMENT,
        /** The entity type has no such command, or the type isn't registered. */
        NOT_FOUND,
        /** The command's handler threw; state is unchanged. */
        FAILED,
        /** Another writer committed first; the command wasn't applied. */
        CONFLICT,
        /** The entity didn't answer in time, or its state couldn't be loaded or saved. */
        UNAVAILABLE,
        /** A bug or misconfiguration. */
        INTERNAL;

        /** Parse a kind sent over the wire; unknown values (from newer nodes) become INTERNAL. */
        public static Kind parse(String value) {
            if (value == null) return INTERNAL;
            try { return valueOf(value); } catch (IllegalArgumentException e) { return INTERNAL; }
        }
    }

    private final Kind kind;

    public StrataException(String message) { this(Kind.INTERNAL, message); }
    public StrataException(String message, Throwable cause) { this(Kind.INTERNAL, message, cause); }
    public StrataException(Kind kind, String message) { super(message); this.kind = kind; }
    public StrataException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }

    public Kind kind() { return kind; }
}
