package io.strata;

import java.time.Instant;
import java.util.UUID;

/**
 * What a command may know about its world: the time, randomness and which entity it is. Inject it
 * ({@code @Inject Ctx ctx}). Inside a recorded invocation (entities that use services) each value
 * is recorded, so a command resumed after a crash sees the same values; elsewhere they're simply
 * the real ones. The seal refuses the JVM's own clock and random sources in entity code: use these.
 */
public interface Ctx {
    Instant now();

    long randomLong();

    /** In [0, 1). */
    double randomDouble();

    UUID randomUuid();

    /** The id of the entity handling the current command. */
    String entityId();

    /** The type name of the entity handling the current command. */
    String entityType();
}
