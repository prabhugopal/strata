package io.strata;

/** A running Strata node. Register entity types, then get refs to entities by id. */
public interface StrataRuntime extends AutoCloseable {
    <S> void register(EntityType<S> type);

    <S> EntityRef<S> ref(EntityType<S> type, String id);

    StrataSettings settings();

    /** Stop gracefully: entities finish their current command and hand over. */
    @Override
    void close();
}
