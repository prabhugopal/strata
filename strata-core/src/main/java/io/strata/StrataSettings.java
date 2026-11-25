package io.strata;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Typed view of the {@code strata} config tree. All defaults live in reference.conf;
 * code reads settings from here and never repeats a default.
 */
public record StrataSettings(
        Config config,
        String backend,
        Duration callTimeout,
        Durability defaultDurability,
        Duration passivateAfter,
        int maxIdLength,
        boolean discoverEntities,
        String storeKind,
        String storeExecutor,
        int storeFixedThreads,
        boolean failOnUnknownProperties,
        String fieldVisibility) {

    /** Settings from the application's config (application.conf, system properties, env). */
    public static StrataSettings load() {
        return from(ConfigFactory.load());
    }

    public static StrataSettings from(Config root) {
        Config c = root.getConfig("strata");
        return new StrataSettings(
                root,
                c.getString("runtime.backend"),
                c.getDuration("call-timeout"),
                Durability.parse(c.getString("entity.default-durability")),
                c.getDuration("entity.passivate-after"),
                c.getInt("entity.max-id-length"),
                c.getBoolean("entity.discover"),
                c.getString("store.kind"),
                c.getString("store.executor"),
                c.getInt("store.fixed-threads"),
                c.getBoolean("codec.fail-on-unknown-properties"),
                c.getString("codec.field-visibility"));
    }

    /** The executor for blocking store I/O, as configured. Callers own and close it. */
    public ExecutorService newStoreExecutor() {
        return switch (storeExecutor) {
            case "virtual" -> Executors.newVirtualThreadPerTaskExecutor();
            case "fixed" -> Executors.newFixedThreadPool(storeFixedThreads);
            default -> throw new IllegalArgumentException(
                    "strata.store.executor must be \"virtual\" or \"fixed\", not \"" + storeExecutor + "\"");
        };
    }
}
