package io.strata;

import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Waits for an answer and returns it, rethrowing a failure as itself (not wrapped). For generated clients' blocking views. */
public final class Await {
    private Await() {}

    public static <T> T result(CompletionStage<T> stage) {
        try {
            return stage.toCompletableFuture().join();
        } catch (CompletionException e) {
            Throwable c = e.getCause();
            if (c instanceof RuntimeException re) throw re;
            if (c instanceof Error err) throw err;
            throw new StrataException(StrataException.Kind.FAILED, String.valueOf(c), c);
        }
    }
}
