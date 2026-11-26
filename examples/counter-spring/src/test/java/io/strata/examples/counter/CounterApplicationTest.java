package io.strata.examples.counter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CounterApplicationTest {
    @TempDir Path dir;
    final HttpClient http = HttpClient.newHttpClient();

    ConfigurableApplicationContext start() {
        return SpringApplication.run(CounterApplication.class,
                "--server.port=0", "--strata.pekko.system.pekko.remote.artery.canonical.port=0",
                "--strata.store.jdbc.url=jdbc:h2:file:" + dir.resolve("db"));
    }

    HttpResponse<String> request(ConfigurableApplicationContext ctx, String method, String path, String body) throws Exception {
        String port = ctx.getEnvironment().getProperty("local.server.port");
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("content-type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    String send(ConfigurableApplicationContext ctx, String method, String path, String body) throws Exception {
        var res = request(ctx, method, path, body);
        assertEquals(200, res.statusCode(), res.body());
        return res.body();
    }

    @Test
    void countsOverHttpAndKeepsStateAcrossRestarts() throws Exception {
        try (var ctx = start()) {
            assertEquals("1", send(ctx, "POST", "/counters/alice/increment", null));
            assertEquals("11", send(ctx, "POST", "/counters/alice/add", "10"));
            assertEquals("0", send(ctx, "GET", "/counters/bob", null));
            // Errors map to HTTP statuses, and a failed command changes nothing.
            assertEquals(422, request(ctx, "POST", "/counters/alice/add", "-1").statusCode());
            assertEquals(400, request(ctx, "POST", "/counters/alice/add", "\"ten\"").statusCode());
            assertEquals(400, request(ctx, "POST", "/counters/alice/add", null).statusCode());
            assertEquals(404, request(ctx, "POST", "/counters/alice/nope", null).statusCode());
            assertEquals("11", send(ctx, "GET", "/counters/alice", null));
            // An entity using a Spring bean (@Inject Greeter).
            assertEquals("\"Hello Ada, visit #1\"", send(ctx, "POST", "/visitors/v1/visit", "\"Ada\""));
            assertEquals("\"Hello Ada, visit #2\"", send(ctx, "POST", "/visitors/v1/visit", "\"Ada\""));
        }
        try (var ctx = start()) {
            assertEquals("11", send(ctx, "GET", "/counters/alice", null), "state committed by the previous process");
        }
    }
}
