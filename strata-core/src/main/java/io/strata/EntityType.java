package io.strata;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
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
 */
public final class EntityType<S> {

    /** One command: its argument type ({@code Void.class} for none) and handler. */
    public record CommandDef<S>(String name, Class<?> argType, boolean readOnly, CommandHandler<S, Object, Object> handler) {}

    private final String name;
    private final Class<S> stateType;
    private final Supplier<S> initial;
    private final Durability durability;
    private final Map<String, CommandDef<S>> commands;

    private EntityType(Builder<S> b) {
        this.name = b.name;
        this.stateType = b.stateType;
        this.initial = Objects.requireNonNull(b.initial, "initial state is required for entity type " + b.name);
        this.durability = b.durability;
        this.commands = Collections.unmodifiableMap(new LinkedHashMap<>(b.commands));
    }

    public static <S> Builder<S> builder(String name, Class<S> stateType) {
        return new Builder<>(name, stateType);
    }

    public String name() { return name; }
    public Class<S> stateType() { return stateType; }
    public S initialState() { return initial.get(); }
    public Map<String, CommandDef<S>> commands() { return commands; }

    /** This type's durability, or the configured default when the type doesn't set one. */
    public Durability durability(StrataSettings settings) {
        return durability != null ? durability : settings.defaultDurability();
    }

    public CommandDef<S> command(String name) {
        CommandDef<S> c = commands.get(name);
        if (c == null) {
            throw new StrataException("Entity type \"" + this.name + "\" has no command \"" + name
                    + "\" (has: " + String.join(", ", commands.keySet()) + ")");
        }
        return c;
    }

    public static final class Builder<S> {
        private final String name;
        private final Class<S> stateType;
        private Supplier<S> initial;
        private Durability durability;
        private final Map<String, CommandDef<S>> commands = new LinkedHashMap<>();

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

        @SuppressWarnings("unchecked")
        public <A, R> Builder<S> command(String name, Class<A> argType, CommandHandler<S, A, R> handler) {
            return add(new CommandDef<>(name, argType, false, (s, a) -> (Reply<S, Object>) handler.handle(s, (A) a)));
        }

        @SuppressWarnings("unchecked")
        public <R> Builder<S> command(String name, NoArgHandler<S, R> handler) {
            return add(new CommandDef<>(name, Void.class, false, (s, a) -> (Reply<S, Object>) handler.handle(s)));
        }

        /** A read-only command: never changes state, so it never writes to the store. */
        public <R> Builder<S> query(String name, Function<S, R> read) {
            return add(new CommandDef<>(name, Void.class, true, (s, a) -> Reply.read(read.apply(s))));
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
