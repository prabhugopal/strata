package io.strata.http.pekko;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import io.strata.StrataException;
import io.strata.StrataRuntime;
import io.strata.http.LiveRoutes;
import io.strata.http.RouteTable;
import io.strata.management.ManagementApi;
import io.strata.pekko.PekkoStrataRuntime;
import io.strata.spi.StrataExtension;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.ServerBinding;
import org.apache.pekko.http.javadsl.model.ContentTypes;
import org.apache.pekko.http.javadsl.model.HttpEntities;
import org.apache.pekko.http.javadsl.model.HttpRequest;
import org.apache.pekko.http.javadsl.model.HttpResponse;
import org.apache.pekko.http.javadsl.model.StatusCodes;
import org.apache.pekko.stream.Materializer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Serves the routes of every registered entity type with a mount, on Pekko HTTP. Starts with the
 * runtime when this module is on the classpath and {@code strata.http.enabled} is on.
 */
public final class PekkoHttpExtension implements StrataExtension {

    record Settings(boolean enabled, String host, int port, String basePath, long maxBodyBytes,
                    Duration bindTimeout, Duration shutdownTimeout, Duration bodyTimeout, String idempotencyHeader) {
        static Settings from(Config root) {
            Config c = root.getConfig("strata.http");
            return new Settings(c.getBoolean("enabled"), c.getString("host"), c.getInt("port"), c.getString("base-path"),
                    c.getBytes("max-body-bytes"), c.getDuration("bind-timeout"), c.getDuration("shutdown-timeout"),
                    c.getDuration("body-timeout"), c.getString("idempotency-header"));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void start(StrataRuntime runtime) {
        Settings s = Settings.from(runtime.settings().config());
        if (!s.enabled()) return;
        if (!(runtime instanceof PekkoStrataRuntime pekko)) {
            throw new StrataException("strata-http serves the pekko runtime; strata.runtime.backend is " + runtime.settings().backend());
        }
        ActorSystem<Void> system = pekko.actorSystem();
        Server server = new Server(runtime, s, Materializer.matFromSystem(system));
        ServerBinding binding;
        try {
            binding = Http.get(system).newServerAt(s.host(), s.port()).bind(server::handle)
                    .toCompletableFuture().get(s.bindTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new StrataException("Couldn't serve HTTP on " + s.host() + ":" + s.port() + " (strata.http.host/port): " + e.getMessage(), e);
        }
        system.log().info("Strata HTTP on {}", binding.localAddress());
        BOUND.put(runtime, binding);
        runtime.closeWith(() -> {
            BOUND.remove(runtime);
            binding.terminate(s.shutdownTimeout()).toCompletableFuture().get(s.shutdownTimeout().toMillis() * 2, TimeUnit.MILLISECONDS);
        });
    }

    /** The address a runtime's HTTP server is bound to (useful with {@code port = 0}). */
    public static java.net.InetSocketAddress boundAddress(StrataRuntime runtime) {
        ServerBinding b = BOUND.get(runtime);
        if (b == null) throw new IllegalStateException("No Strata HTTP server for this runtime");
        return b.localAddress();
    }

    private static final Map<StrataRuntime, ServerBinding> BOUND = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static final class Server {
        private final StrataRuntime runtime;
        private final Settings s;
        private final Materializer mat;
        private final LiveRoutes routes;
        private final ManagementApi management;

        Server(StrataRuntime runtime, Settings s, Materializer mat) {
            this.runtime = runtime; this.s = s; this.mat = mat;
            this.routes = new LiveRoutes(runtime, s.basePath());
            this.management = new ManagementApi(runtime);
        }

        private CompletionStage<HttpResponse> manage(HttpRequest request, String path) {
            String query = request.getUri().rawQueryString().orElse("");
            String auth = request.getHeader("Authorization").map(h -> h.value()).orElse(null);
            return request.entity().toStrict(s.bodyTimeout().toMillis(), s.maxBodyBytes(), mat)
                    .thenCompose(e -> management.handle(request.method().value(), path, ManagementApi.parseQuery(query), e.getData().toArray(), auth,
                            request.getHeader(s.idempotencyHeader()).map(h -> h.value()).orElse(null)))
                    .thenApply(r -> HttpResponse.create().withStatus(StatusCodes.get(r.status())).withEntity(HttpEntities.create(
                            r.contentType().startsWith("application/json") ? ContentTypes.APPLICATION_JSON : ContentTypes.TEXT_PLAIN_UTF8, r.body())))
                    .exceptionally(err -> error(RouteTable.status(err), RouteTable.message(err)));
        }

        CompletionStage<HttpResponse> handle(HttpRequest request) {
            String path = request.getUri().path();
            String method = request.method().value();
            if (management.handles(path)) return manage(request, path);
            RouteTable t = routes.current();
            var match = t.match(method, path);
            if (match.isEmpty()) {
                request.discardEntityBytes(mat);
                return CompletableFuture.completedFuture(t.pathExists(path)
                        ? error(405, "No " + method + " route for " + path)
                        : error(404, "No route for " + method + " " + path));
            }
            var m = match.get();
            CompletionStage<byte[]> body = m.takesArgument()
                    ? request.entity().toStrict(s.bodyTimeout().toMillis(), s.maxBodyBytes(), mat).thenApply(e -> e.getData().toArray())
                    : request.discardEntityBytes(mat).completionStage().thenApply(done -> null);
            return body
                    .thenCompose(arg -> {
                        var ref = runtime.ref(m.type(), m.id());
                        var key = request.getHeader(s.idempotencyHeader()).map(h -> h.value()).filter(v -> !v.isBlank());
                        return (key.isPresent() ? ref.withIdempotencyKey(key.get()) : ref).callJson(m.command(), arg == null || arg.length == 0 ? null : arg);
                    })
                    .thenApply(result -> HttpResponse.create().withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, result)))
                    .exceptionally(err -> error(RouteTable.status(err), RouteTable.message(err)));
        }

        private static HttpResponse error(int status, String message) {
            byte[] body;
            try { body = JSON.writeValueAsBytes(Map.of("error", message)); } catch (Exception e) { body = new byte[0]; }
            return HttpResponse.create().withStatus(StatusCodes.get(status)).withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, body));
        }
    }
}
