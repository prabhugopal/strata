package io.strata;

import java.util.concurrent.CompletionStage;

/**
 * A handle to one entity, by type and id. Calls are routed to wherever the entity lives and
 * applied one at a time; a persistent entity's state is committed before the call completes.
 */
public interface EntityRef<S> {
    String id();

    /** Call a command without an argument; the result is decoded to {@code resultType}. */
    <R> CompletionStage<R> call(String command, Class<R> resultType);

    /** Call a command with an argument; the result is decoded to {@code resultType}. */
    <R> CompletionStage<R> call(String command, Object arg, Class<R> resultType);

    /** Call a command; the result is decoded generically (numbers, strings, maps, lists). */
    default CompletionStage<Object> call(String command, Object arg) { return call(command, arg, Object.class); }

    default CompletionStage<Object> call(String command) { return call(command, Object.class); }

    /** The entity's current state. */
    CompletionStage<S> state();
}
