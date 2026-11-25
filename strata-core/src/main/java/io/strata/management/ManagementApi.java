package io.strata.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strata.Codec;
import io.strata.EntityType;
import io.strata.StrataException;
import io.strata.StrataRuntime;
import io.strata.http.RouteTable;
import io.strata.spi.StoredEntry;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * The management API, independent of any web framework (strata-http and the Spring starter both
 * serve it). All paths are under {@code strata.management.base-path}:
 *
 * <pre>
 * GET  /info                                   node, version, settings summary
 * GET  /cluster                                members and entities per node
 * GET  /types                                  entity types, commands, schemas, routes
 * GET  /types/{type}/entities?after=&limit=    stored and live entities
 * GET  /types/{type}/entities/{id}             state, revision, whether it is in memory
 * POST /types/{type}/entities/{id}/{command}   run a command (JSON argument → JSON result)
 * GET  /events?after=&limit=&type=&id=         recent events on this node
 * GET  /metrics                                Prometheus text format
 * </pre>
 */
public final class ManagementApi {

    /** An HTTP response, for whichever framework serves it. */
    public record Response(int status, String contentType, byte[] body) {}

    private static final String JSON = "application/json";
    private static final String PROMETHEUS = "text/plain; version=0.0.4; charset=utf-8";

    private final StrataRuntime runtime;
    private final ManagementSettings settings;
    private final Codec codec;
    private final ObjectMapper json = new ObjectMapper();

    public ManagementApi(StrataRuntime runtime) {
        this.runtime = runtime;
        this.settings = ManagementSettings.from(runtime.settings().config());
        this.codec = new Codec(runtime.settings());
    }

    public boolean enabled() { return settings.enabled(); }

    /** Whether {@code path} belongs to the management API. */
    public boolean handles(String path) {
        return settings.enabled() && (path.equals(settings.basePath()) || path.startsWith(settings.basePath() + "/"));
    }

    public CompletionStage<Response> handle(String method, String path, Map<String, String> query, byte[] body, String authorization) {
        try {
            if (!authorized(authorization)) return done(error(401, "management API needs \"Authorization: Bearer <token>\" (strata.management.token)"));
            List<String> seg = segments(path.substring(settings.basePath().length()));
            boolean get = method.equalsIgnoreCase("GET");
            if (get && seg.equals(List.of("info"))) return done(ok(info()));
            if (get && seg.equals(List.of("cluster"))) return cluster();
            if (get && seg.equals(List.of("types"))) return done(ok(types()));
            if (get && seg.equals(List.of("events"))) return done(ok(events(query)));
            if (get && seg.equals(List.of("metrics"))) {
                return done(new Response(200, PROMETHEUS, runtime.introspection().observability().metrics().render().getBytes(StandardCharsets.UTF_8)));
            }
            if (seg.size() >= 3 && seg.get(0).equals("types") && seg.get(2).equals("entities")) {
                EntityType<?> type = type(seg.get(1));
                if (get && seg.size() == 3) return entities(type, query);
                if (get && seg.size() == 4) return entity(type, seg.get(3));
                if (method.equalsIgnoreCase("POST") && seg.size() == 5) return invoke(type, seg.get(3), seg.get(4), body);
            }
            return done(error(404, "no management endpoint " + method + " " + path));
        } catch (StrataException e) {
            return done(error(RouteTable.status(e), e.getMessage()));
        } catch (IllegalArgumentException e) {
            return done(error(400, e.getMessage()));
        }
    }

    // ---- endpoints ----------------------------------------------------------------------------

    private ObjectNode info() {
        var in = runtime.introspection();
        var s = runtime.settings();
        ObjectNode o = json.createObjectNode();
        o.put("product", "strata");
        o.put("version", Optional.ofNullable(ManagementApi.class.getPackage().getImplementationVersion()).orElse("dev"));
        o.put("node", in.node());
        o.put("backend", s.backend());
        o.put("startedAt", in.startedAt().toString());
        o.put("uptimeSeconds", Duration.between(in.startedAt(), Instant.now()).toSeconds());
        o.put("store", s.storeKind());
        o.put("defaultDurability", s.defaultDurability().name().toLowerCase());
        o.put("callTimeoutMs", s.callTimeout().toMillis());
        o.put("passivateAfterMs", s.passivateAfter().toMillis());
        o.put("allowInvoke", settings.allowInvoke());
        o.put("lastEventSeq", in.observability().events().lastSeq());
        o.put("types", runtime.types().size());
        return o;
    }

    private CompletionStage<Response> cluster() {
        return runtime.introspection().cluster().thenApply(c -> ok(json.valueToTree(c)))
                .exceptionally(err -> error(RouteTable.status(err), RouteTable.message(err)));
    }

    private ArrayNode types() {
        ArrayNode out = json.createArrayNode();
        int depth = settings.schemaMaxDepth();
        for (EntityType<?> t : runtime.types()) {
            ObjectNode o = out.addObject();
            o.put("name", t.name());
            o.put("durability", t.durability(runtime.settings()).name().toLowerCase());
            o.put("mount", t.mount());
            o.put("idVariable", t.idVariable());
            o.put("stateType", t.stateType().getName());
            o.set("stateSchema", json.valueToTree(codec.schema(t.stateType(), depth)));
            o.put("live", runtime.introspection().observability().live(t.name()).size());
            ArrayNode cmds = o.putArray("commands");
            for (var c : t.commands().values()) {
                ObjectNode co = cmds.addObject();
                co.put("name", c.name());
                co.put("readOnly", c.readOnly());
                co.set("argument", c.argType() == Void.class ? null : json.valueToTree(codec.schema(c.argType(), depth)));
                if (c.params() != null) {
                    ArrayNode ps = co.putArray("params");
                    for (var p : c.params()) {
                        ObjectNode po = ps.addObject();
                        po.put("name", p.name());
                        po.put("javaType", p.type().getTypeName());
                        po.set("schema", json.valueToTree(codec.schema(p.type(), depth)));
                    }
                }
                if (c.resultType() != null) co.set("result", json.valueToTree(codec.schema(c.resultType(), depth)));
                t.routes().stream().filter(r -> r.command().equals(c.name())).findFirst().ifPresent(r -> {
                    ObjectNode ro = co.putObject("route");
                    ro.put("method", r.method());
                    ro.put("path", t.mount() + r.path());
                });
            }
        }
        return out;
    }

    private CompletionStage<Response> entities(EntityType<?> type, Map<String, String> query) {
        int limit = limit(query);
        String after = query.get("after");
        var obs = runtime.introspection().observability();
        TreeSet<String> live = new TreeSet<>(obs.live(type.name()));
        return runtime.introspection().store().list(type.name(), after, limit).thenApply(stored -> {
            ArrayNode items = json.createArrayNode();
            TreeSet<String> ids = new TreeSet<>();
            Map<String, StoredEntry> byId = new java.util.HashMap<>();
            for (StoredEntry e : stored) { ids.add(e.id()); byId.put(e.id(), e); }
            // Live entities not yet stored (or ephemeral) within this page's id range.
            String last = stored.size() == limit ? stored.getLast().id() : null;
            for (String id : live) {
                if ((after == null || id.compareTo(after) > 0) && (last == null || id.compareTo(last) <= 0)) ids.add(id);
            }
            List<String> page = new ArrayList<>(ids).subList(0, Math.min(limit, ids.size()));
            for (String id : page) {
                ObjectNode o = items.addObject();
                o.put("id", id);
                o.put("live", live.contains(id));
                StoredEntry e = byId.get(id);
                if (e != null) {
                    o.put("revision", e.revision());
                    if (e.updatedAt() != null) o.put("updatedAt", e.updatedAt().toString());
                }
            }
            ObjectNode out = json.createObjectNode();
            out.put("type", type.name());
            out.set("items", items);
            out.put("next", page.size() == limit ? page.getLast() : null);
            return ok(out);
        }).exceptionally(err -> error(RouteTable.status(err), RouteTable.message(err)));
    }

    private CompletionStage<Response> entity(EntityType<?> type, String id) {
        boolean live = runtime.introspection().observability().isLive(type.name(), id);
        boolean persistent = type.durability(runtime.settings()) == io.strata.Durability.PERSISTENT;
        CompletionStage<ObjectNode> read;
        if (persistent) {
            read = runtime.introspection().store().load(type.name(), id).thenApply(stored -> {
                if (stored.isEmpty() && !live) throw new StrataException(StrataException.Kind.NOT_FOUND, type.name() + "/" + id + " has no stored state");
                ObjectNode o = json.createObjectNode();
                o.put("revision", stored.map(s -> s.revision()).orElse(0L));
                o.set("state", stored.map(s -> tree(s.state())).orElse(null));
                return o;
            });
        } else if (live) {
            read = runtime.ref(type, id).state().thenApply(state -> {
                ObjectNode o = json.createObjectNode();
                o.set("state", tree(codec.encode(state)));
                return o;
            });
        } else {
            return done(error(404, type.name() + "/" + id + " is ephemeral and not in memory"));
        }
        return read.thenApply(o -> {
            o.put("type", type.name());
            o.put("id", id);
            o.put("live", live);
            o.put("durability", type.durability(runtime.settings()).name().toLowerCase());
            return ok(o);
        }).exceptionally(err -> error(RouteTable.status(err), RouteTable.message(err)));
    }

    private CompletionStage<Response> invoke(EntityType<?> type, String id, String command, byte[] body) {
        if (!settings.allowInvoke()) return done(error(403, "running commands is turned off (strata.management.allow-invoke)"));
        type.command(command); // 404 for an unknown command, before activating the entity
        long start = System.nanoTime();
        return runtime.ref(type, id).callJson(command, body == null || body.length == 0 ? null : body)
                .thenApply(result -> {
                    ObjectNode o = json.createObjectNode();
                    o.set("result", tree(result));
                    o.put("durationMicros", (System.nanoTime() - start) / 1000);
                    return ok(o);
                })
                .exceptionally(err -> error(RouteTable.status(err), RouteTable.message(err)));
    }

    private ObjectNode events(Map<String, String> query) {
        long after = query.containsKey("after") ? Long.parseLong(query.get("after")) : 0;
        int limit = Math.min(query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : settings.maxEventsPerRequest(), settings.maxEventsPerRequest());
        String type = query.get("type"), id = query.get("id");
        var log = runtime.introspection().observability().events();
        var items = log.since(after, limit, e -> (type == null || type.equals(e.type())) && (id == null || id.equals(e.id())));
        ObjectNode out = json.createObjectNode();
        out.set("items", json.valueToTree(items.stream().map(e -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("seq", e.seq()); m.put("at", e.at().toString()); m.put("node", e.node()); m.put("kind", e.kind());
            m.put("type", e.type()); m.put("id", e.id());
            if (e.command() != null) m.put("command", e.command());
            if (e.durationMicros() != null) m.put("durationMicros", e.durationMicros());
            if (e.revision() != null) m.put("revision", e.revision());
            if (e.errorKind() != null) m.put("errorKind", e.errorKind());
            if (e.error() != null) m.put("error", e.error());
            return m;
        }).toList()));
        out.put("lastSeq", log.lastSeq());
        return out;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private EntityType<?> type(String name) {
        return runtime.types().stream().filter(t -> t.name().equals(name)).findFirst()
                .orElseThrow(() -> new StrataException(StrataException.Kind.NOT_FOUND, "no entity type \"" + name + "\""));
    }

    private int limit(Map<String, String> query) {
        int n = query.containsKey("limit") ? Integer.parseInt(query.get("limit")) : settings.defaultListLimit();
        if (n < 1) throw new IllegalArgumentException("limit must be at least 1");
        return Math.min(n, settings.maxListLimit());
    }

    private boolean authorized(String authorization) {
        if (settings.token().isEmpty()) return true;
        String expected = "Bearer " + settings.token();
        return authorization != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), authorization.getBytes(StandardCharsets.UTF_8));
    }

    private com.fasterxml.jackson.databind.JsonNode tree(byte[] bytes) {
        try {
            return bytes == null ? null : json.readTree(bytes);
        } catch (java.io.IOException e) {
            throw new StrataException("stored state isn't JSON", e);
        }
    }

    private static List<String> segments(String rest) {
        List<String> out = new ArrayList<>();
        for (String s : rest.split("/")) if (!s.isEmpty()) out.add(URLDecoder.decode(s, StandardCharsets.UTF_8));
        return out;
    }

    /** Query string → map (first value wins). */
    public static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new java.util.HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.putIfAbsent(k, v);
        }
        return out;
    }

    private Response ok(com.fasterxml.jackson.databind.JsonNode body) {
        try {
            return new Response(200, JSON, json.writeValueAsBytes(body));
        } catch (Exception e) {
            return error(500, e.getMessage());
        }
    }

    private Response error(int status, String message) {
        try {
            return new Response(status, JSON, json.writeValueAsBytes(Map.of("error", String.valueOf(message))));
        } catch (Exception e) {
            return new Response(status, JSON, new byte[0]);
        }
    }

    private static CompletionStage<Response> done(Response r) { return CompletableFuture.completedFuture(r); }
}
