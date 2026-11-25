package io.strata;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.strata.spi.EntityTypeProvider;
import io.strata.spi.RuntimeProvider;
import io.strata.spi.StrataExtension;

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
        StrataRuntime runtime = startRuntime(settings);
        try {
            if (settings.discoverEntities()) {
                ServiceLoader.load(EntityTypeProvider.class).forEach(p -> runtime.register(p.entityType()));
            }
            ServiceLoader.load(StrataExtension.class).forEach(e -> e.start(runtime));
        } catch (RuntimeException e) {
            runtime.close();
            throw e;
        }
        return runtime;
    }

    private static StrataRuntime startRuntime(StrataSettings settings) {
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
