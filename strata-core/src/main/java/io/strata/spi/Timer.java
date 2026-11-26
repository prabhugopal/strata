package io.strata.spi;

import java.time.Instant;

/** A command to run on an entity at a time: {@code arg} is its JSON argument (null for none). */
public record Timer(String id, String type, String entityId, String command, byte[] arg, Instant due) {}
