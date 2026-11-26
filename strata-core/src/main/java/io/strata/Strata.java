package io.strata;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.strata.spi.EntitySource;
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
        return start(config, Services.NONE);
    }

    /** Start with services for entities' {@code @Inject} fields. */
    public static StrataRuntime start(Services services) {
        return start(ConfigFactory.load(), services);
    }

    public static StrataRuntime start(Config config, Services services) {
        StrataSettings settings = StrataSettings.from(config);
        StrataRuntime runtime = startRuntime(settings, services);
        try {
            if (settings.discoverEntities()) {
                ServiceLoader.load(EntityTypeProvider.class).forEach(p -> runtime.register(p.entityType()));
            }
            Codec codec = new Codec(settings);
            for (EntitySource source : ServiceLoader.load(EntitySource.class)) {
                for (EntityType<?> t : source.entityTypes(settings, codec)) {
                    // A class listed in config whose type was also discovered (generated) is registered once.
                    boolean same = runtime.types().stream().anyMatch(r -> r.name().equals(t.name()) && r.stateType() == t.stateType());
                    if (!same) runtime.register(t);
                }
            }
            ServiceLoader.load(StrataExtension.class).forEach(e -> e.start(runtime));
        } catch (RuntimeException e) {
            runtime.close();
            throw e;
        }
        return runtime;
    }

    private static StrataRuntime startRuntime(StrataSettings settings, Services services) {
        ServiceLoader<RuntimeProvider> providers = ServiceLoader.load(RuntimeProvider.class);
        return providers.stream()
                .map(ServiceLoader.Provider::get)
                .filter(p -> p.backend().equals(settings.backend()))
                .findFirst()
                .orElseThrow(() -> new StrataException("No Strata runtime \"" + settings.backend() + "\" on the classpath (found: "
                        + providers.stream().map(p -> p.get().backend()).collect(Collectors.joining(", ")) + "). Add strata-pekko."))
                .start(settings, services);
    }
}
