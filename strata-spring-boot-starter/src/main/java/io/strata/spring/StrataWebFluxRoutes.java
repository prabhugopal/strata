package io.strata.spring;

import io.strata.StrataRuntime;
import io.strata.http.LiveRoutes;
import io.strata.http.RouteTable;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

/**
 * Serves entity routes (@Entity mount) on Spring WebFlux, from the same {@link RouteTable} as
 * strata-http, so routes behave the same with or without Spring. Off when strata.http.enabled is false.
 */
@AutoConfiguration(after = StrataAutoConfiguration.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnClass(RouterFunction.class)
@ConditionalOnBean(StrataRuntime.class)
public class StrataWebFluxRoutes {

    @Bean
    public RouterFunction<ServerResponse> strataEntityRoutes(StrataRuntime runtime) {
        var http = runtime.settings().config().getConfig("strata.http");
        if (!http.getBoolean("enabled")) return request -> Mono.empty();
        String basePath = http.getString("base-path");
        int maxBodyBytes = Math.toIntExact(http.getBytes("max-body-bytes"));
        LiveRoutes routes = new LiveRoutes(runtime, basePath);
        return request -> {
            Optional<RouteTable.Match> match = routes.current().match(request.method().name(), request.path());
            return Mono.justOrEmpty(match.<HandlerFunction<ServerResponse>>map(m -> req -> handle(runtime, m, req, maxBodyBytes)));
        };
    }

    private static Mono<ServerResponse> handle(StrataRuntime runtime, RouteTable.Match m, ServerRequest req, int maxBodyBytes) {
        Mono<byte[]> body = m.takesArgument()
                ? DataBufferUtils.join(req.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class), maxBodyBytes).map(buf -> {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    DataBufferUtils.release(buf);
                    return bytes;
                })
                : Mono.empty();
        return body.map(Optional::of).defaultIfEmpty(Optional.empty())
                .flatMap(arg -> Mono.fromCompletionStage(runtime.ref(m.type(), m.id())
                        .callJson(m.command(), arg.filter(a -> a.length > 0).orElse(null))))
                .flatMap(result -> ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(result))
                .onErrorResume(err -> ServerResponse.status(HttpStatus.valueOf(RouteTable.status(err)))
                        .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("error", RouteTable.message(err))));
    }

}
