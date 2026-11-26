package io.strata.pekko;

import com.typesafe.config.Config;

import java.time.Duration;

/** Typed view of strata.pekko. Defaults live in this module's reference.conf. */
record PekkoSettings(String clusterMode, Duration startupTimeout, Duration startupPollInterval, int stashCapacity, String systemName, Config systemConfig) {

    static PekkoSettings from(Config root) {
        Config c = root.getConfig("strata.pekko");
        // strata.pekko.system.* wins over plain pekko.* so Strata's required defaults apply.
        Config system = c.getConfig("system").withoutPath("name").withFallback(root);
        java.util.List<String> seeds = java.util.Arrays.stream(c.getString("seed-nodes").split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).toList();
        if (!seeds.isEmpty()) {
            system = com.typesafe.config.ConfigFactory.parseMap(java.util.Map.of("pekko.cluster.seed-nodes", seeds)).withFallback(system);
        }
        system = tls(c.getConfig("tls")).withFallback(system);
        system = splitBrain(c.getConfig("split-brain")).withFallback(system);
        // strata.entity.passivate-after is the one idle setting (the entity actor applies it and knows
        // when it's busy waiting); Pekko's own idle passivation would be a second, unaware one.
        system = com.typesafe.config.ConfigFactory.parseMap(java.util.Map.of(IDLE_PASSIVATION, "off")).withFallback(system);
        String mode = c.getString("cluster-mode");
        if (!mode.equals("single") && !mode.equals("seed-nodes")) {
            throw new IllegalArgumentException("strata.pekko.cluster-mode must be \"single\" or \"seed-nodes\", not \"" + mode + "\"");
        }
        return new PekkoSettings(mode, c.getDuration("startup-timeout"), c.getDuration("startup-poll-interval"), c.getInt("stash-capacity"), c.getString("system.name"), system);
    }

    /** strata.pekko.tls as Pekko Artery settings; empty when TLS is off. Missing files or passwords fail here, at start. */
    static Config tls(Config t) {
        String mode = t.getString("mode").trim();
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        String ssl = "pekko.remote.artery.ssl.";
        switch (mode) {
            case "off" -> { return com.typesafe.config.ConfigFactory.empty(); }
            case "keystore" -> {
                Config k = t.getConfig("keystore");
                String keyStore = existing(k.getString("key-store"), "strata.pekko.tls.keystore.key-store (STRATA_TLS_KEY_STORE)");
                String trustStore = existing(k.getString("trust-store"), "strata.pekko.tls.keystore.trust-store (STRATA_TLS_TRUST_STORE)");
                String keyStorePassword = required(k.getString("key-store-password"), "strata.pekko.tls.keystore.key-store-password (STRATA_TLS_KEY_STORE_PASSWORD)");
                String trustStorePassword = required(k.getString("trust-store-password"), "strata.pekko.tls.keystore.trust-store-password (STRATA_TLS_TRUST_STORE_PASSWORD)");
                String keyPassword = k.getString("key-password").isEmpty() ? keyStorePassword : k.getString("key-password");
                m.put(ssl + "ssl-engine-provider", "org.apache.pekko.remote.artery.tcp.ConfigSSLEngineProvider");
                m.put(ssl + "config-ssl-engine.key-store", keyStore);
                m.put(ssl + "config-ssl-engine.key-store-password", keyStorePassword);
                m.put(ssl + "config-ssl-engine.key-password", keyPassword);
                m.put(ssl + "config-ssl-engine.trust-store", trustStore);
                m.put(ssl + "config-ssl-engine.trust-store-password", trustStorePassword);
                m.put(ssl + "config-ssl-engine.protocol", t.getString("protocol"));
                m.put(ssl + "config-ssl-engine.require-mutual-authentication", true);
            }
            case "pem" -> {
                Config p = t.getConfig("pem");
                String dir = p.getString("directory");
                for (String f : java.util.List.of("tls.key", "tls.crt", "ca.crt")) {
                    existing(dir + "/" + f, "strata.pekko.tls.pem.directory (STRATA_TLS_DIR) must contain " + f + ";");
                }
                m.put(ssl + "ssl-engine-provider", "org.apache.pekko.remote.artery.tcp.ssl.RotatingKeysSSLEngineProvider");
                m.put(ssl + "rotating-keys-engine.key-file", dir + "/tls.key");
                m.put(ssl + "rotating-keys-engine.cert-file", dir + "/tls.crt");
                m.put(ssl + "rotating-keys-engine.ca-cert-file", dir + "/ca.crt");
                m.put(ssl + "rotating-keys-engine.ssl-context-cache-ttl", t.getDuration("pem.reload-after").toMillis() + "ms");
                m.put(ssl + "rotating-keys-engine.protocol", t.getString("protocol"));
                // The keys live only in memory: protect that keystore with a random password, not Pekko's well-known default.
                byte[] pw = new byte[24];
                new java.security.SecureRandom().nextBytes(pw);
                m.put(ssl + "rotating-keys-engine.keystore-password", java.util.HexFormat.of().formatHex(pw));
            }
            default -> throw new IllegalArgumentException("strata.pekko.tls.mode must be off, keystore or pem, not \"" + mode + "\"");
        }
        m.put("pekko.remote.artery.transport", "tls-tcp");
        return com.typesafe.config.ConfigFactory.parseMap(m);
    }

    private static String required(String v, String what) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException("TLS is on but " + what + " isn't set");
        return v;
    }

    private static String existing(String path, String what) {
        required(path, what);
        if (!java.nio.file.Files.isReadable(java.nio.file.Path.of(path))) throw new IllegalArgumentException("TLS is on but " + what + " " + path + " isn't a readable file");
        return path;
    }

    /** Lease config path Pekko loads StoreLease from (inside the actor system's config). */
    static final String IDLE_PASSIVATION = "pekko.cluster.sharding.passivation.default-idle-strategy.idle-entity.timeout";
    static final String LEASE_CONFIG_PATH = "strata-store-lease";

    /** strata.pekko.split-brain as Pekko's split-brain resolver settings. */
    static Config splitBrain(Config sb) {
        String strategy = sb.getString("strategy").trim();
        if (!java.util.Set.of("keep-majority", "lease-majority", "keep-oldest", "static-quorum").contains(strategy)) {
            throw new IllegalArgumentException("strata.pekko.split-brain.strategy must be keep-majority, lease-majority, keep-oldest or static-quorum, not \"" + strategy + "\"");
        }
        String r = "pekko.cluster.split-brain-resolver.";
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put(r + "active-strategy", strategy);
        m.put(r + "stable-after", sb.getDuration("stable-after").toMillis() + "ms");
        if (strategy.equals("lease-majority")) {
            Config l = sb.getConfig("lease");
            m.put(r + "lease-majority.lease-implementation", LEASE_CONFIG_PATH);
            m.put(r + "lease-majority.acquire-lease-delay-for-minority", l.getDuration("acquire-delay-for-minority").toMillis() + "ms");
            m.put(LEASE_CONFIG_PATH + ".lease-class", StoreLease.class.getName());
            m.put(LEASE_CONFIG_PATH + ".heartbeat-interval", l.getDuration("heartbeat-interval").toMillis() + "ms");
            m.put(LEASE_CONFIG_PATH + ".heartbeat-timeout", l.getDuration("heartbeat-timeout").toMillis() + "ms");
            m.put(LEASE_CONFIG_PATH + ".lease-operation-timeout", l.getDuration("operation-timeout").toMillis() + "ms");
        }
        return com.typesafe.config.ConfigFactory.parseMap(m);
    }
}
