package io.strata;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Where entities get their collaborators (payment clients, AI clients, repositories…): fields
 * marked {@link io.strata.annotation.Inject} (or {@code jakarta.inject.Inject}). Whoever hosts
 * Strata provides it: a plain app registers instances, the Spring starter looks up beans.
 * Lookups happen once per entity type at registration, never per command.
 */
public interface Services {
    /** The service of this type (and name, when given), if there is one. */
    <T> Optional<T> find(Class<T> type, String name);

    /** No services: fine for entities without {@code @Inject} fields. */
    Services NONE = new Services() {
        @Override public <T> Optional<T> find(Class<T> type, String name) { return Optional.empty(); }
    };

    /** These services, then {@code fallback}'s for what these don't have. */
    default Services orElse(Services fallback) {
        Services first = this;
        return new Services() {
            @Override
            public <T> Optional<T> find(Class<T> type, String name) {
                Optional<T> s = first.find(type, name);
                return s.isPresent() ? s : fallback.find(type, name);
            }
        };
    }

    /**
     * Classes marked {@link io.strata.annotation.Provides} (listed by strata-processor in
     * META-INF/strata/services) plus {@code extra} class names: each created once, offered for
     * every interface it implements and its own class, and under its name if it has one.
     */
    static Services discover(ClassLoader loader, java.util.Collection<String> extra) {
        return discover(loader, extra, true);
    }

    static Services discover(ClassLoader loader, java.util.Collection<String> extra, boolean listed) {
        java.util.Set<String> names = new java.util.LinkedHashSet<>();
        if (listed) try {
            var urls = loader.getResources("META-INF/strata/services");
            while (urls.hasMoreElements()) {
                try (var in = urls.nextElement().openStream()) {
                    for (String line : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
                        if (!line.isBlank() && !line.startsWith("#")) names.add(line.trim());
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new StrataException("Couldn't read META-INF/strata/services", e);
        }
        names.addAll(extra);
        Builder b = builder();
        Map<Class<?>, String> providedBy = new HashMap<>();
        for (String name : names) {
            Object instance;
            Class<?> cls;
            try {
                cls = Class.forName(name, true, loader);
                instance = cls.getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new StrataException("Couldn't create service " + name + " (it needs a public no-argument constructor)", e);
            }
            java.util.Set<Class<?>> offered = new java.util.LinkedHashSet<>();
            offered.add(cls);
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) interfaces(c, offered);
            for (Class<?> type : offered) {
                String other = providedBy.putIfAbsent(type, name);
                if (other != null) {
                    throw new StrataException("Both " + other + " and " + name + " provide " + type.getName()
                            + ": keep one, give them names (@Provides(name = …)), or pass the one you want to Strata.start");
                }
                addUnchecked(b, type, instance);
            }
            var p = cls.getAnnotation(io.strata.annotation.Provides.class);
            if (p != null && !p.name().isEmpty()) addNamedUnchecked(b, p.name(), cls, instance);
        }
        return b.build();
    }

    private static void interfaces(Class<?> c, java.util.Set<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) if (out.add(i)) interfaces(i, out);
    }

    @SuppressWarnings("unchecked")
    private static <T> void addUnchecked(Builder b, Class<T> type, Object instance) { b.add(type, (T) instance); }

    @SuppressWarnings("unchecked")
    private static <T> void addNamedUnchecked(Builder b, String name, Class<T> type, Object instance) { b.add(name, type, (T) instance); }

    /** A fixed set of services, for apps that don't use a DI container. */
    static Builder builder() { return new Builder(); }

    final class Builder {
        private final Map<Class<?>, Object> byType = new HashMap<>();
        private final Map<String, Object> byName = new HashMap<>();

        public <T> Builder add(Class<T> type, T service) {
            if (byType.putIfAbsent(type, service) != null) throw new IllegalArgumentException("Two services of type " + type.getName() + "; name them");
            return this;
        }

        public <T> Builder add(String name, Class<T> type, T service) {
            if (byName.putIfAbsent(name, type.cast(service)) != null) throw new IllegalArgumentException("Two services named " + name);
            return this;
        }

        public Services build() {
            Map<Class<?>, Object> types = Map.copyOf(byType);
            Map<String, Object> names = Map.copyOf(byName);
            return new Services() {
                @Override
                public <T> Optional<T> find(Class<T> type, String name) {
                    Object s = name == null || name.isEmpty() ? types.get(type) : names.get(name);
                    return type.isInstance(s) ? Optional.of(type.cast(s)) : Optional.empty();
                }
            };
        }
    }
}
