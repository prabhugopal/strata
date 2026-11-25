package io.strata;

/** Reads state, optionally with an argument; never changes it. */
@FunctionalInterface
public interface QueryHandler<S, A, R> {
    R read(S state, A arg) throws Exception;
}
