package io.strata.spi;

import io.strata.StrataException;
import io.strata.StrataSettings;

import java.util.ServiceLoader;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/** Creates the StateStore named by strata.store.kind. */
public final class StateStores {
    private StateStores() {}

    public static StateStore create(StrataSettings settings, Executor io) {
        ServiceLoader<StateStoreProvider> providers = ServiceLoader.load(StateStoreProvider.class);
        return providers.stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.kind().equals(settings.storeKind()))
                .findFirst()
                .orElseThrow(() -> new StrataException("No state store \"" + settings.storeKind() + "\" on the classpath (found: "
                        + providers.stream().map(p -> p.get().kind()).collect(Collectors.joining(", ")) + ")"))
                .create(settings, io);
    }
}
