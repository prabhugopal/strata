package io.strata;

/**
 * What a command handler returns: the result for the caller and, if the command changed the
 * entity, its new state. Use {@link #update} for changes and {@link #read} otherwise.
 */
public record Reply<S, R>(S state, R result, boolean changed) {
    public static <S, R> Reply<S, R> update(S newState, R result) {
        return new Reply<>(newState, result, true);
    }

    public static <S, R> Reply<S, R> read(R result) {
        return new Reply<>(null, result, false);
    }
}
