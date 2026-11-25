package io.strata;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.strata.spi.RuntimeProvider;

import java.util.ServiceLoader;
import java.util.stream.Collectors;

/** Entry point: starts the runtime named by {@code strata.runtime.backend}. */
public final class Strata {
    private Strata() {}

    /** Start with the application's config (application.conf, -D properties, environment). */
    public static StrataRuntime start() {
        return start(ConfigFactory.load());
    }

    public static StrataRuntime start(Config config) {
        StrataSettings settings = StrataSettings.from(config);
        ServiceLoader<RuntimeProvider> providers = ServiceLoader.load(RuntimeProvider.class);
        return providers.stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.backend().equals(settings.backend()))
                .findFirst()
                .orElseThrow(() -> new StrataException("No Strata runtime \"" + settings.backend() + "\" on the classpath (found: "
                        + providers.stream().map(p -> p.get().backend()).collect(Collectors.joining(", ")) + "). Add strata-pekko."))
                .start(settings);
    }
}
