package io.strata;

/** Handles a command with an argument. A plain function, so any JVM language can supply one. */
@FunctionalInterface
public interface CommandHandler<S, A, R> {
    Reply<S, R> handle(S state, A arg) throws Exception;
}
