package io.strata.pekko;

import com.typesafe.config.ConfigFactory;
import io.strata.Strata;
import io.strata.StrataException;
import io.strata.StrataRuntime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileInputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Nodes talking over TLS with mutual authentication, using certificates made with the JDK's keytool. */
class TlsTest {
    @TempDir static Path certs;
    static final String PW = "test-password";

    static void keytool(String... args) throws Exception {
        String bin = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        var cmd = new java.util.ArrayList<>(List.of(bin));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(certs.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), "keytool " + String.join(" ", args) + ": " + out);
    }

    @BeforeAll
    static void makeCertificates() throws Exception {
        // Keystore mode: a key pair, and a trust store holding its certificate.
        keytool("-genkeypair", "-alias", "node", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=strata-test", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", "node.p12", "-storepass", PW, "-keypass", PW);
        keytool("-exportcert", "-alias", "node", "-keystore", "node.p12", "-storepass", PW, "-file", "node.cer");
        keytool("-importcert", "-noprompt", "-alias", "node", "-file", "node.cer", "-storetype", "PKCS12", "-keystore", "trust.p12", "-storepass", PW);

        // PEM mode, as cert-manager lays it out: a CA, a node certificate it signed, the key as PKCS#8.
        keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=strata-test-ca", "-ext", "bc:c", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", "ca.p12", "-storepass", PW, "-keypass", PW);
        keytool("-exportcert", "-rfc", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PW, "-file", "ca.crt");
        keytool("-genkeypair", "-alias", "pem", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=strata-pem", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", "pem.p12", "-storepass", PW, "-keypass", PW);
        keytool("-certreq", "-alias", "pem", "-keystore", "pem.p12", "-storepass", PW, "-file", "pem.csr");
        keytool("-gencert", "-alias", "ca", "-keystore", "ca.p12", "-storepass", PW, "-infile", "pem.csr", "-outfile", "tls.crt", "-rfc",
                "-ext", "san=dns:strata-pem", "-validity", "2");
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var in = new FileInputStream(certs.resolve("pem.p12").toFile())) { ks.load(in, PW.toCharArray()); }
        PrivateKey key = (PrivateKey) ks.getKey("pem", PW.toCharArray());
        Path dir = Files.createDirectories(certs.resolve("pem"));
        Files.writeString(dir.resolve("tls.key"), "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(key.getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        Files.copy(certs.resolve("tls.crt"), dir.resolve("tls.crt"));
        Files.copy(certs.resolve("ca.crt"), dir.resolve("ca.crt"));
    }

    static int freePort() throws Exception {
        try (var s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    static Map<String, Object> keystoreTls() {
        return Map.of("strata.pekko.tls.mode", "keystore",
                "strata.pekko.tls.keystore.key-store", certs.resolve("node.p12").toString(),
                "strata.pekko.tls.keystore.key-store-password", PW,
                "strata.pekko.tls.keystore.trust-store", certs.resolve("trust.p12").toString(),
                "strata.pekko.tls.keystore.trust-store-password", PW);
    }

    static Map<String, Object> pemTls() {
        return Map.of("strata.pekko.tls.mode", "pem", "strata.pekko.tls.pem.directory", certs.resolve("pem").toString());
    }

    static StrataRuntime node(int port, int seedPort, Map<String, Object> tls, Map<String, Object> extra) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
                "strata.pekko.cluster-mode", "seed-nodes",
                "strata.pekko.seed-nodes", "pekko://strata@127.0.0.1:" + seedPort,
                "strata.pekko.system.pekko.remote.artery.canonical.port", port));
        cfg.putAll(tls);
        cfg.putAll(extra);
        return Strata.start(ConfigFactory.parseMap(cfg).withFallback(ConfigFactory.load()).resolve());
    }

    static void clusterOfTwo(Map<String, Object> tls) throws Exception {
        int pa = freePort(), pb = freePort();
        var a = CompletableFuture.supplyAsync(() -> node(pa, pa, tls, Map.of()));
        var b = CompletableFuture.supplyAsync(() -> node(pb, pa, tls, Map.of()));
        try (var na = a.get(60, TimeUnit.SECONDS); var nb = b.get(60, TimeUnit.SECONDS)) {
            assertEquals(2, na.introspection().cluster().toCompletableFuture().get().members().size());
            // Entities on both nodes, called from one: their messages cross the encrypted connection.
            for (int i = 0; i < 20; i++) {
                assertEquals(1, CartClient.of(nb, "tls-" + i).add("tea", 1).toCompletableFuture().get(10, TimeUnit.SECONDS));
            }
            var where = na.introspection().cluster().toCompletableFuture().get().entitiesByTypeAndNode().get("cart");
            assertEquals(2, where.size(), "entities on both nodes: " + where);
        }
    }

    @Test
    void nodesWithKeystoresFormAnEncryptedCluster() throws Exception {
        assertEquals("tls-tcp", PekkoSettings.tls(ConfigFactory.parseMap(keystoreTls()).withFallback(ConfigFactory.load()).getConfig("strata.pekko.tls"))
                .getString("pekko.remote.artery.transport"));
        clusterOfTwo(keystoreTls());
    }

    @Test
    void nodesWithPemFilesFormAnEncryptedCluster() throws Exception {
        clusterOfTwo(pemTls());
    }

    @Test
    void aNodeWithoutTlsCantJoin() throws Exception {
        int pa = freePort(), pb = freePort();
        try (var secure = node(pa, pa, keystoreTls(), Map.of())) {
            var e = assertThrows(StrataException.class, () -> node(pb, pa, Map.of(), Map.of("strata.pekko.startup-timeout", "8s")));
            assertTrue(e.getMessage().contains("didn't join"), e.getMessage());
            assertEquals(1, secure.introspection().cluster().toCompletableFuture().get().members().size());
        }
    }

    @Test
    void misconfiguredTlsFailsAtStartWithAClearMessage() {
        var noPassword = assertThrows(IllegalArgumentException.class, () -> PekkoSettings.tls(ConfigFactory.parseMap(Map.of(
                "strata.pekko.tls.mode", "keystore",
                "strata.pekko.tls.keystore.key-store", certs.resolve("node.p12").toString(),
                "strata.pekko.tls.keystore.trust-store", certs.resolve("trust.p12").toString())).withFallback(ConfigFactory.load()).getConfig("strata.pekko.tls")));
        assertTrue(noPassword.getMessage().contains("STRATA_TLS_KEY_STORE_PASSWORD"), noPassword.getMessage());
        var noFiles = assertThrows(IllegalArgumentException.class, () -> PekkoSettings.tls(ConfigFactory.parseMap(Map.of(
                "strata.pekko.tls.mode", "pem", "strata.pekko.tls.pem.directory", "/nonexistent")).withFallback(ConfigFactory.load()).getConfig("strata.pekko.tls")));
        assertTrue(noFiles.getMessage().contains("tls.key"), noFiles.getMessage());
        assertThrows(IllegalArgumentException.class, () -> PekkoSettings.tls(ConfigFactory.parseMap(Map.of("strata.pekko.tls.mode", "on"))
                .withFallback(ConfigFactory.load()).getConfig("strata.pekko.tls")));
    }
}
