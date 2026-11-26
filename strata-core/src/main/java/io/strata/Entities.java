package io.strata;

/**
 * Entities by type and id. A {@link StrataRuntime} is one; inside an entity, inject one
 * ({@code @Inject Entities entities}) to call other entities: each call is recorded, and carries
 * an idempotency key made from the calling invocation, so the target applies it exactly once even
 * if the caller is interrupted and resumes. Generated clients take either:
 * {@code AccountClient.of(entities, "acct-1")}.
 */
public interface Entities {
    <S> EntityRef<S> ref(EntityType<S> type, String id);
}
