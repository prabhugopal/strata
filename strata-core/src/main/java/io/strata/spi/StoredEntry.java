package io.strata.spi;

import java.time.Instant;

/** One stored entity in a listing: its id, revision and when it was last committed. */
public record StoredEntry(String id, long revision, Instant updatedAt) {}
