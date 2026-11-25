package io.strata;

/** Handles a command without an argument. */
@FunctionalInterface
public interface NoArgHandler<S, R> {
    Reply<S, R> handle(S state) throws Exception;
}
