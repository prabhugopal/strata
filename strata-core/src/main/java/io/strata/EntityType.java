package io.strata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An entity type: a name, an initial state and named commands. Built from plain functions so
 * Java, Kotlin, Scala and Clojure define entities the same way:
 *
 * <pre>{@code
 * EntityType<Long> counter = EntityType.builder("counter", Long.class)
 *     .initial(() -> 0L)
 *     .command("increment", s -> Reply.update(s + 1, s + 1))
 *     .command("add", Long.class, (s, n) -> Reply.update(s + n, s + n))
 *     .query("get", s -> s)
 *     .build();
 * }</pre>
 *
 * Classes annotated with {@link io.strata.annotation.Entity} get their entity type generated.
 */
public final class EntityType<S> {

    /** One command: its argument type ({@code Void.class} for none; may be generic) and handler. */
    public record CommandDef<S>(String name, java.lang.reflect.Type argType, boolean readOnly, CommandHandler<S, Object, Object> handler,
                                List<Param> params, java.lang.reflect.Type resultType) {
        CommandDef(String name, java.lang.reflect.Type argType, boolean readOnly, CommandHandler<S, Object, Object> handler) {
            this(name, argType, readOnly, handler, null, null);
        }
    }

    /**
     * A named parameter, for tools that build forms. One parameter is sent as the value itself;
     * several as a JSON object by name.
     */
    public record Param(String name, java.lang.reflect.Type type) {}

    /** An HTTP route to a command, relative to the type's {@link #mount()}. */
    public record Route(String method, String path, String command) {}

    /** One {@code {variable}} in a path. */
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)}");

    private final String name;
    private final Class<S> stateType;
    private final Supplier<S> initial;
    private final Durability durability;
    private final boolean mutableState;
    private final String mount;
    private final String idVariable;
    private final Map<String, CommandDef<S>> commands;
    private final List<Route> routes;

    private EntityType(Builder<S> b) {
        this.name = b.name;
        this.stateType = b.stateType;
        this.initial = Objects.requireNonNull(b.initial, "initial state is required for entity type " + b.name);
        this.durability = b.durability;
        this.mutableState = b.mutableState;
        this.mount = b.mount;
        this.idVariable = b.mount == null ? null : idVariable(b.mount);
        this.commands = Collections.unmodifiableMap(new LinkedHashMap<>(b.commands));
        for (Route r : b.routes) command(r.command()); // every route must name a command
        if (!b.routes.isEmpty() && b.mount == null) {
            throw new IllegalArgumentException("Entity type " + name + " has routes but no mount");
        }
        this.routes = List.copyOf(b.routes);
    }

    /** The single id variable of a mount path, e.g. {@code /carts/{cartId}} → {@code cartId}. */
    public static String idVariable(String mount) {
        Matcher m = PATH_VARIABLE.matcher(mount);
        List<String> vars = new ArrayList<>();
        while (m.find()) vars.add(m.group(1));
        if (!mount.startsWith("/") || vars.size() != 1) {
            throw new IllegalArgumentException("A mount must start with '/' and contain exactly one {variable} for the entity id: " + mount);
        }
        return vars.getFirst();
    }

    public static <S> Builder<S> builder(String name, Class<S> stateType) {
        return new Builder<>(name, stateType);
    }

    public String name() { return name; }
    public Class<S> stateType() { return stateType; }
    public S initialState() { return initial.get(); }
    public Map<String, CommandDef<S>> commands() { return commands; }

    /**
     * Whether handlers change the state object in place (annotated classes do). The runtime
     * then gives each command a copy, so a command that throws leaves the committed state intact.
     */
    public boolean mutableState() { return mutableState; }

    /** HTTP path the entity is served under, or null for none. */
    public String mount() { return mount; }

    /** The mount's id variable, or null without a mount. */
    public String idVariable() { return idVariable; }

    public List<Route> routes() { return routes; }

    /** This type's durability, or the configured default when the type doesn't set one. */
    public Durability durability(StrataSettings settings) {
        return durability != null ? durability : settings.defaultDurability();
    }

    public CommandDef<S> command(String name) {
        CommandDef<S> c = commands.get(name);
        if (c == null) {
            throw new StrataException(StrataException.Kind.NOT_FOUND, "Entity type \"" + this.name + "\" has no command \"" + name
                    + "\" (has: " + String.join(", ", commands.keySet()) + ")");
        }
        return c;
    }

    public static final class Builder<S> {
        private final String name;
        private final Class<S> stateType;
        private Supplier<S> initial;
        private Durability durability;
        private boolean mutableState;
        private String mount;
        private final Map<String, CommandDef<S>> commands = new LinkedHashMap<>();
        private final List<Route> routes = new ArrayList<>();

        private Builder(String name, Class<S> stateType) {
            if (!EntityTypeNames.valid(name)) {
                throw new IllegalArgumentException("Entity type name must be 1-" + EntityTypeNames.MAX_LENGTH
                        + " letters, digits, '-' or '_', starting with a letter: " + name);
            }
            this.name = name;
            this.stateType = Objects.requireNonNull(stateType, "stateType");
        }

        public Builder<S> initial(Supplier<S> initial) { this.initial = initial; return this; }

        public Builder<S> durability(Durability durability) { this.durability = durability; return this; }

        /** Handlers mutate the state object in place; see {@link EntityType#mutableState()}. */
        public Builder<S> mutableState() { this.mutableState = true; return this; }

        /** Serve the entity over HTTP under this path; it must contain one {variable} for the id. */
        public Builder<S> mount(String mount) {
            idVariable(mount);
            this.mount = mount;
            return this;
        }

        /** Route an HTTP method and path (relative to the mount, may be empty) to a command. */
        public Builder<S> route(String method, String path, String command) {
            if (!path.isEmpty() && !path.startsWith("/")) throw new IllegalArgumentException("A route path must be empty or start with '/': " + path);
            if (PATH_VARIABLE.matcher(path).find()) throw new IllegalArgumentException("Route paths can't have variables (only the mount's id): " + path);
            routes.add(new Route(method, path, command));
            return this;
        }

        @SuppressWarnings("unchecked")
        public <A, R> Builder<S> command(String name, Class<A> argType, CommandHandler<S, A, R> handler) {
            return add(new CommandDef<>(name, argType, false, (s, a) -> (Reply<S, Object>) handler.handle(s, (A) a)));
        }

        /** A command whose argument has a generic type, e.g. {@code new TypeRef<List<Item>>() {}}. */
        @SuppressWarnings("unchecked")
        public <A, R> Builder<S> command(String name, TypeRef<A> argType, CommandHandler<S, A, R> handler) {
            return add(new CommandDef<>(name, argType.type(), false, (s, a) -> (Reply<S, Object>) handler.handle(s, (A) a)));
        }

        /** A command with an argument of any (possibly generic) type, for code that builds types reflectively. */
        @SuppressWarnings("unchecked")
        public Builder<S> command(String name, java.lang.reflect.Type argType, boolean readOnly, CommandHandler<S, Object, Object> handler) {
            return add(new CommandDef<>(name, argType, readOnly, handler));
        }

        @SuppressWarnings("unchecked")
        public <R> Builder<S> command(String name, NoArgHandler<S, R> handler) {
            return add(new CommandDef<>(name, Void.class, false, (s, a) -> (Reply<S, Object>) handler.handle(s)));
        }

        /** A read-only command: never changes state, so it never writes to the store. */
        public <R> Builder<S> query(String name, Function<S, R> read) {
            return add(new CommandDef<>(name, Void.class, true, (s, a) -> Reply.read(read.apply(s))));
        }

        /** A read-only command with an argument ({@code Void.class} for none). */
        @SuppressWarnings("unchecked")
        public <A, R> Builder<S> query(String name, Class<A> argType, QueryHandler<S, A, R> read) {
            return add(new CommandDef<>(name, argType, true, (s, a) -> Reply.read(read.read(s, (A) a))));
        }

        /** A read-only command whose argument has a generic type. */
        @SuppressWarnings("unchecked")
        public <A, R> Builder<S> query(String name, TypeRef<A> argType, QueryHandler<S, A, R> read) {
            return add(new CommandDef<>(name, argType.type(), true, (s, a) -> Reply.read(read.read(s, (A) a))));
        }

        /**
         * Describe a command's parameters and result for tools (forms, docs). Optional: commands
         * without a description are still callable; tools then show only the argument type.
         */
        public Builder<S> describe(String command, List<Param> params, java.lang.reflect.Type resultType) {
            CommandDef<S> c = commands.get(command);
            if (c == null) throw new IllegalArgumentException("describe(): no command \"" + command + "\" on entity type " + name);
            commands.put(command, new CommandDef<>(c.name(), c.argType(), c.readOnly(), c.handler(), List.copyOf(params), resultType));
            return this;
        }

        private Builder<S> add(CommandDef<S> def) {
            if (commands.putIfAbsent(def.name(), def) != null) {
                throw new IllegalArgumentException("Duplicate command \"" + def.name() + "\" on entity type " + name);
            }
            return this;
        }

        public EntityType<S> build() { return new EntityType<>(this); }
    }
}
