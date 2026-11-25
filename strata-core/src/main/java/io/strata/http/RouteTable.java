package io.strata.http;

import io.strata.EntityType;
import io.strata.StrataException;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The HTTP routes of a set of entity types ({@code mount} + each route's path), matched without
 * any web framework so every HTTP layer (Pekko HTTP, Spring WebFlux, …) serves the same routes.
 */
public final class RouteTable {

    /** A request matched to an entity and command. */
    public record Match(EntityType<?> type, String id, String command, boolean takesArgument) {}

    private record Entry(String method, Pattern pattern, EntityType<?> type, String command, boolean takesArgument) {}

    private final List<Entry> entries;
    private final int typeCount;

    private RouteTable(List<Entry> entries, int typeCount) {
        this.entries = entries;
        this.typeCount = typeCount;
    }

    /** Routes of the given types, under {@code basePath} (empty for none). */
    public static RouteTable of(Collection<EntityType<?>> types, String basePath) {
        String base = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        List<Entry> entries = new ArrayList<>();
        for (EntityType<?> t : types) {
            if (t.mount() == null) continue;
            String idVar = "{" + t.idVariable() + "}";
            int at = t.mount().indexOf(idVar);
            String prefix = base + t.mount().substring(0, at);
            String suffix = t.mount().substring(at + idVar.length());
            for (EntityType.Route r : t.routes()) {
                Pattern p = Pattern.compile(Pattern.quote(prefix) + "([^/]+)" + Pattern.quote(suffix + r.path()));
                entries.add(new Entry(r.method().toUpperCase(Locale.ROOT), p, t, r.command(), t.command(r.command()).argType() != Void.class));
            }
        }
        return new RouteTable(List.copyOf(entries), types.size());
    }

    /** How many types this table was built from; HTTP layers rebuild when the runtime has more. */
    public int typeCount() { return typeCount; }

    public boolean isEmpty() { return entries.isEmpty(); }

    /** The entity and command for a request, if any route matches its method and path. */
    public Optional<Match> match(String method, String path) {
        for (Entry e : entries) {
            if (!e.method().equalsIgnoreCase(method)) continue;
            Matcher m = e.pattern().matcher(path);
            if (m.matches()) {
                return Optional.of(new Match(e.type(), URLDecoder.decode(m.group(1), StandardCharsets.UTF_8), e.command(), e.takesArgument()));
            }
        }
        return Optional.empty();
    }

    /** Whether some route has this path with another method (so the answer is 405, not 404). */
    public boolean pathExists(String path) {
        return entries.stream().anyMatch(e -> e.pattern().matcher(path).matches());
    }

    /** The HTTP status for a Strata failure. */
    public static int status(Throwable error) {
        Throwable t = error;
        while (!(t instanceof StrataException) && t.getCause() != null && t.getCause() != t) t = t.getCause();
        if (!(t instanceof StrataException se)) return 500;
        return switch (se.kind()) {
            case INVALID_ARGUMENT -> 400;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case FAILED -> 422;
            case UNAVAILABLE -> 503;
            case INTERNAL -> 500;
        };
    }

    /** The message of the Strata failure inside {@code error}. */
    public static String message(Throwable error) {
        Throwable t = error;
        while (!(t instanceof StrataException) && t.getCause() != null && t.getCause() != t) t = t.getCause();
        return String.valueOf(t.getMessage());
    }
}
