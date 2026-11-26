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

    /**
     * Start a node the way an app's {@code main} wants: the application's config plus any
     * {@code --some.key=value} arguments (they win), and a shutdown hook that stops the node
     * gracefully (entities finish their command and hand over) when the process is told to stop.
     *
     * <pre>{@code
     * public static void main(String[] args) { Strata.run(args); }
     * }</pre>
     *
     * Or no main at all: {@link StrataMain} does exactly this (the Gradle plugin uses it by default).
     */
    public static StrataRuntime run(String... args) {
        return run(Services.NONE, args);
    }

    /** As {@link #run(String...)}, with services for entities' {@code @Inject} fields. */
    public static StrataRuntime run(Services services, String... args) {
        Config config = arguments(args).withFallback(ConfigFactory.load()).resolve();
        StrataRuntime runtime = start(config, services);
        if (config.getBoolean("strata.shutdown-hook")) {
            Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "strata-shutdown"));
        }
        return runtime;
    }

    /** {@code --key=value} arguments as config (values parsed as HOCON, so numbers and lists work). */
    static Config arguments(String... args) {
        StringBuilder hocon = new StringBuilder();
        for (String a : args) {
            if (!a.startsWith("--") || !a.contains("=")) {
                throw new IllegalArgumentException("Arguments look like --strata.http.port=9000, not " + a);
            }
            int eq = a.indexOf('=');
            hocon.append(a, 2, eq).append(" = ").append(a.substring(eq + 1)).append('\n');
        }
        return ConfigFactory.parseString(hocon.toString(), com.typesafe.config.ConfigParseOptions.defaults().setOriginDescription("command line"));
    }

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
