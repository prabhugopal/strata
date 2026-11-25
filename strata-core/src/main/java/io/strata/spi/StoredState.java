package io.strata.spi;

/** An entity's committed state and its revision (incremented on every write). */
public record StoredState(long revision, byte[] state) {}
