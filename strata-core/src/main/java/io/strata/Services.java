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
