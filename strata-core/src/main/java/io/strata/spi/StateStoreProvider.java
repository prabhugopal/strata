package io.strata.spi;

import io.strata.StrataSettings;

import java.util.concurrent.Executor;

/** A StateStore implementation, found with ServiceLoader and chosen by strata.store.kind. */
public interface StateStoreProvider {
    String kind();

    /** @param io executor for blocking I/O, configured by strata.store.executor */
    StateStore create(StrataSettings settings, Executor io);
}
