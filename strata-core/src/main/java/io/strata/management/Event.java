package io.strata.management;

import java.time.Instant;

/**
 * Something that happened to an entity. {@code seq} increases by one per event on a node, so
 * tools poll with {@code after = last seq seen}.
 */
public record Event(long seq, Instant at, String node, String kind, String type, String id,
                    String command, Long durationMicros, Long revision, String errorKind, String error) {}
