package io.strata.spi;

import io.strata.Services;
import io.strata.StrataRuntime;
import io.strata.StrataSettings;

/** A runtime implementation, found with ServiceLoader and chosen by strata.runtime.backend. */
public interface RuntimeProvider {
    String backend();

    StrataRuntime start(StrataSettings settings, Services services);
}
