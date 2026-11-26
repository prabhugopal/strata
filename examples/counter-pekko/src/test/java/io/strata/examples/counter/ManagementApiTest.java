package io.strata.examples.counter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The management API over HTTP, as the strata CLI and Stratum use it. */
class ManagementApiTest {
    @TempDir Path dir;
    HttpClient http = HttpClient.newHttpClient();
    final ObjectMapper json = new ObjectMapper();

    StrataRuntime start(Map<String, Object> extra) {
        Map<String, Object> cfg = new HashMap<>(Map.of(
                "strata.http.port", 0,
                "strata.pekko.system.pekko.remote.artery.canonical.port", 0,
                "strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db")));
        cfg.putAll(extra);
        // A fresh client per node: a new node can get the previous one's port, and a pooled
        // connection to the closed server would fail.
        http = HttpClient.newHttpClient();
        return Strata.start(ConfigFactory.parseMap(cfg).withFallback(ConfigFactory.load()).resolve());
    }

    HttpResponse<String> call(StrataRuntime strata, String method, String path, String body, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + PekkoHttpExtension.boundAddress(strata).getPort() + path))
                .header("content-type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode get(StrataRuntime strata, String path) throws Exception {
        var r = call(strata, "GET", path, null, null);
        assertEquals(200, r.statusCode(), r.body());
        return json.readTree(r.body());
    }

    @Test
    void describesRunsAndObservesEntities() throws Exception {
        try (var strata = start(Map.of())) {
            JsonNode info = get(strata, "/strata/info");
            assertEquals("strata", info.get("product").asText());
            assertEquals("jdbc", info.get("store").asText());
            assertTrue(info.get("node").asText().startsWith("pekko://strata@"));

            JsonNode types = get(strata, "/strata/types");
            JsonNode counter = types.get(0);
            assertEquals("counter", counter.get("name").asText());
            assertEquals("/counters/{id}", counter.get("mount").asText());
            assertEquals("integer", counter.at("/stateSchema/properties/count/type").asText());
            JsonNode add = null;
            for (JsonNode c : counter.get("commands")) if (c.get("name").asText().equals("add")) add = c;
            assertNotNull(add);
            assertEquals("amount", add.at("/params/0/name").asText());
            assertEquals("integer", add.at("/params/0/schema/type").asText());
            assertEquals("integer", add.at("/result/type").asText());
            assertEquals("/counters/{id}/add", add.at("/route/path").asText());

            // Run commands through the management API.
            var r = call(strata, "POST", "/strata/types/counter/entities/alice/add", "5", null);
            assertEquals(200, r.statusCode(), r.body());
            assertEquals(5, json.readTree(r.body()).get("result").asInt());
            call(strata, "POST", "/counters/bob/increment", null, null);
            assertEquals(422, call(strata, "POST", "/strata/types/counter/entities/alice/add", "-1", null).statusCode());
            assertEquals(404, call(strata, "POST", "/strata/types/counter/entities/alice/nope", null, null).statusCode());

            JsonNode list = get(strata, "/strata/types/counter/entities");
            assertEquals(2, list.get("items").size());
            assertEquals("alice", list.at("/items/0/id").asText());
            assertTrue(list.at("/items/0/live").asBoolean());
            assertEquals(1, list.at("/items/0/revision").asInt());
            JsonNode page = get(strata, "/strata/types/counter/entities?limit=1");
            assertEquals(1, page.get("items").size());
            assertEquals("bob", get(strata, "/strata/types/counter/entities?after=" + page.get("next").asText()).at("/items/0/id").asText());

            JsonNode alice = get(strata, "/strata/types/counter/entities/alice");
            assertEquals(5, alice.at("/state/count").asInt());
            assertEquals(1, alice.get("revision").asInt());
            assertEquals(404, call(strata, "GET", "/strata/types/counter/entities/nobody", null, null).statusCode());

            JsonNode events = get(strata, "/strata/events?type=counter&id=alice");
            var kinds = new java.util.ArrayList<String>();
            events.get("items").forEach(e -> kinds.add(e.get("kind").asText()));
            assertEquals(java.util.List.of("activated", "command", "failed"), kinds, "an unknown command is rejected before it reaches the entity");
            assertEquals(1, events.at("/items/1/revision").asInt());
            assertEquals("FAILED", events.at("/items/2/errorKind").asText());
            long last = events.get("lastSeq").asLong();
            assertEquals(0, get(strata, "/strata/events?after=" + last).get("items").size());

            JsonNode cluster = get(strata, "/strata/cluster");
            assertEquals("Up", cluster.at("/members/0/status").asText());
            assertEquals(cluster.get("self").asText(), cluster.get("oldest").asText(), "a single node is the oldest");
            assertEquals("off", cluster.get("tls").asText());
            assertEquals("keep-majority", cluster.at("/splitBrain/strategy").asText());
            assertTrue(cluster.at("/splitBrain/leaseHolder").isNull());
            var clusterEvents = new java.util.ArrayList<String>();
            get(strata, "/strata/events?type=cluster").get("items").forEach(e -> clusterEvents.add(e.get("kind").asText()));
            assertEquals(java.util.List.of("member-up"), clusterEvents, "membership changes are in the event feed");
            assertEquals(2, cluster.at("/entitiesByTypeAndNode/counter").elements().next().asInt());

            String metrics = call(strata, "GET", "/strata/metrics", null, null).body();
            assertTrue(metrics.contains("strata_commands_total{type=\"counter\",command=\"add\",outcome=\"ok\"} 1"), metrics);
            assertTrue(metrics.contains("strata_commands_total{type=\"counter\",command=\"add\",outcome=\"failed\"} 1"), metrics);
            assertTrue(metrics.contains("strata_live_entities{type=\"counter\"} 2"), metrics);
            assertTrue(metrics.contains("strata_command_duration_seconds_bucket{type=\"counter\",command=\"add\",le=\"+Inf\"} 2"), metrics);
            assertTrue(metrics.contains("strata_saves_total{type=\"counter\"} 2"), metrics);
        }
    }

    @Test
    void tokenAndReadOnlyAreConfigurable() throws Exception {
        try (var strata = start(Map.of("strata.management.token", "s3cret", "strata.management.allow-invoke", false))) {
            assertEquals(401, call(strata, "GET", "/strata/info", null, null).statusCode());
            assertEquals(200, call(strata, "GET", "/strata/health/live", null, null).statusCode(), "probes need no token");
            assertEquals(200, call(strata, "GET", "/strata/health/ready", null, null).statusCode());
            assertEquals(401, call(strata, "GET", "/strata/info", null, "wrong").statusCode());
            assertEquals(200, call(strata, "GET", "/strata/info", null, "s3cret").statusCode());
            assertEquals(403, call(strata, "POST", "/strata/types/counter/entities/a/increment", null, "s3cret").statusCode());
            // Entity routes are the app's own API and aren't affected.
            assertEquals(200, call(strata, "POST", "/counters/a/increment", null, null).statusCode());
        }
        try (var strata = start(Map.of("strata.management.enabled", false))) {
            assertEquals(404, call(strata, "GET", "/strata/info", null, null).statusCode());
        }
    }
}
