package io.strata;

import java.util.Collection;

/** A running Strata node. Register entity types, then get refs to entities by id. */
public interface StrataRuntime extends AutoCloseable {
    /** Register an entity type. Registering the same instance again does nothing. */
    <S> void register(EntityType<S> type);

    /** The registered entity types, in registration order. */
    Collection<EntityType<?>> types();

    <S> EntityRef<S> ref(EntityType<S> type, String id);

    StrataSettings settings();

    /** Close {@code resource} when the runtime closes (before the runtime itself), e.g. an HTTP server. */
    void closeWith(AutoCloseable resource);

    /** Stop gracefully: entities finish their current command and hand over. */
    @Override
    void close();
}
