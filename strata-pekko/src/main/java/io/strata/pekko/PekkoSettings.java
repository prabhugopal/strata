package io.strata.pekko;

import com.typesafe.config.Config;

import java.time.Duration;

/** Typed view of strata.pekko. Defaults live in this module's reference.conf. */
record PekkoSettings(String clusterMode, Duration startupTimeout, Duration startupPollInterval, int stashCapacity, String systemName, Config systemConfig) {

    static PekkoSettings from(Config root) {
        Config c = root.getConfig("strata.pekko");
        // strata.pekko.system.* wins over plain pekko.* so Strata's required defaults apply.
        Config system = c.getConfig("system").withoutPath("name").withFallback(root);
        String mode = c.getString("cluster-mode");
        if (!mode.equals("single") && !mode.equals("seed-nodes")) {
            throw new IllegalArgumentException("strata.pekko.cluster-mode must be \"single\" or \"seed-nodes\", not \"" + mode + "\"");
        }
        return new PekkoSettings(mode, c.getDuration("startup-timeout"), c.getDuration("startup-poll-interval"), c.getInt("stash-capacity"), c.getString("system.name"), system);
    }
}
