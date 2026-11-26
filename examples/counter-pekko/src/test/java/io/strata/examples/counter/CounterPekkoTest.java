package io.strata.examples.counter;

import com.typesafe.config.ConfigFactory;
import io.strata.Strata;
import io.strata.StrataRuntime;
import io.strata.http.pekko.PekkoHttpExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterPekkoTest {
    @TempDir Path dir;
    HttpClient http = HttpClient.newHttpClient();

    StrataRuntime start() {
        http = HttpClient.newHttpClient(); // a new node may reuse the old one's port
        return Strata.start(ConfigFactory.parseMap(Map.of(
                "strata.http.port", 0,
                "strata.pekko.system.pekko.remote.artery.canonical.port", 0,
                "strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db"))).withFallback(ConfigFactory.load()).resolve());
    }

    HttpResponse<String> request(StrataRuntime strata, String method, String path, String body) throws Exception {
        var address = PekkoHttpExtension.boundAddress(strata);
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + address.getPort() + path))
                .header("content-type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    String sendKeyed(StrataRuntime strata, String path, String key) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + PekkoHttpExtension.boundAddress(strata).getPort() + path))
                .header("Idempotency-Key", key).POST(HttpRequest.BodyPublishers.noBody()).build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return res.body();
    }

    String send(StrataRuntime strata, String method, String path, String body) throws Exception {
        var res = request(strata, method, path, body);
        assertEquals(200, res.statusCode(), res.body());
        return res.body();
    }

    @Test
    void servesAnnotatedEntityWithoutSpringAndKeepsState() throws Exception {
        try (var strata = start()) {
            assertEquals("1", send(strata, "POST", "/counters/alice/increment", null));
            assertEquals("11", send(strata, "POST", "/counters/alice/add", "10"));
            assertEquals("0", send(strata, "GET", "/counters/bob", null));
            assertEquals(422, request(strata, "POST", "/counters/alice/add", "-1").statusCode());
            assertEquals(400, request(strata, "POST", "/counters/alice/add", "\"ten\"").statusCode());
            assertEquals(405, request(strata, "GET", "/counters/alice/increment", null).statusCode());
            assertEquals(404, request(strata, "GET", "/nothing/here", null).statusCode());
            // An idempotency key: the retry gets the first answer, and the counter moves once.
            assertEquals("12", sendKeyed(strata, "/counters/alice/increment", "retry-1"));
            assertEquals("12", sendKeyed(strata, "/counters/alice/increment", "retry-1"));
            assertEquals("12", send(strata, "GET", "/counters/alice", null));
            assertEquals("13", sendKeyed(strata, "/counters/alice/increment", "retry-2"));
            // The generated typed client, in-process.
            assertEquals(18L, CounterClient.of(strata, "alice").add(5).toCompletableFuture().get());
        }
        try (var strata = start()) {
            assertEquals("18", send(strata, "GET", "/counters/alice", null), "state committed by the previous process");
        }
    }
}
