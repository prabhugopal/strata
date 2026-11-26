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

    /**
     * Run {@code command} (with JSON-able {@code arg}, or null) on an entity at {@code at}.
     * Delivered exactly once, even across node failures. Inside a command the timer is created
     * only if that command commits. Returns the timer's id, for {@link #cancel}.
     * Generated clients offer the typed form: {@code WalletClient.of(entities, id).later(d).deposit(5)}.
     */
    String schedule(EntityType<?> type, String id, java.time.Instant at, String command, Object arg);

    /** As above, after {@code delay} (from Ctx's clock inside a command). */
    String schedule(EntityType<?> type, String id, java.time.Duration delay, String command, Object arg);

    /** Cancel a timer (inside a command: if that command commits). Cancelling a fired or unknown timer does nothing. */
    void cancel(String timerId);
}
