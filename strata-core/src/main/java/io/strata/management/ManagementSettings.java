package io.strata.management;

import com.typesafe.config.Config;

import java.time.Duration;
import java.util.List;

/** Typed view of strata.management and strata.metrics; defaults in strata-core's reference.conf. */
public record ManagementSettings(boolean enabled, String basePath, String token, boolean allowInvoke,
                                 int defaultListLimit, int maxListLimit, int eventCapacity, boolean eventsIncludeQueries,
                                 int maxEventsPerRequest, int schemaMaxDepth, List<Duration> latencyBuckets) {

    public static ManagementSettings from(Config root) {
        Config c = root.getConfig("strata.management");
        String base = c.getString("base-path");
        if (!base.startsWith("/") || base.endsWith("/")) {
            throw new IllegalArgumentException("strata.management.base-path must start with '/' and not end with one: " + base);
        }
        return new ManagementSettings(c.getBoolean("enabled"), base, c.getString("token"), c.getBoolean("allow-invoke"),
                c.getInt("list.default-limit"), c.getInt("list.max-limit"),
                c.getInt("events.capacity"), c.getBoolean("events.include-queries"), c.getInt("events.max-per-request"),
                c.getInt("schema-max-depth"), root.getDurationList("strata.metrics.latency-buckets"));
    }
}
