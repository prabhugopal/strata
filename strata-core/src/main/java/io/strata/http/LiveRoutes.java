package io.strata.http;

import io.strata.StrataRuntime;

/**
 * The route table of a running runtime, rebuilt when entity types are registered after an HTTP
 * layer starts (e.g. by Spring after discovery).
 */
public final class LiveRoutes {
    private final StrataRuntime runtime;
    private final String basePath;
    private volatile RouteTable table;

    public LiveRoutes(StrataRuntime runtime, String basePath) {
        this.runtime = runtime;
        this.basePath = basePath;
        this.table = RouteTable.of(runtime.types(), basePath);
    }

    public RouteTable current() {
        RouteTable t = table;
        var types = runtime.types();
        if (t.typeCount() != types.size()) table = t = RouteTable.of(types, basePath);
        return t;
    }
}
