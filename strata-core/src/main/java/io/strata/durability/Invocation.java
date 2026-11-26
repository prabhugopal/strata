package io.strata.durability;

import java.util.concurrent.Callable;

/**
 * The command running on this thread: which entity, and its journal when the invocation is
 * recorded (null otherwise). Set by the runtime around every command.
 */
public record Invocation(String type, String id, Journal journal) {
    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();

    public static Invocation current() { return CURRENT.get(); }

    public <T> T run(Callable<T> body) throws Exception {
        Invocation outer = CURRENT.get();
        CURRENT.set(this);
        try {
            return body.call();
        } finally {
            if (outer == null) CURRENT.remove(); else CURRENT.set(outer);
        }
    }
}
