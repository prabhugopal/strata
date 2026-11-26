package io.strata.durability;

import io.strata.Ctx;
import io.strata.StrataException;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** The {@link Ctx} every entity can inject: real values, recorded inside recorded invocations. */
public final class RuntimeCtx implements Ctx {
    public static final RuntimeCtx INSTANCE = new RuntimeCtx();

    private RuntimeCtx() {}

    private static Object recorded(String name, Class<?> type, java.util.concurrent.Callable<Object> real) {
        Journal j = Journal.current();
        if (j == null) {
            try { return real.call(); } catch (Exception e) { throw new StrataException("ctx." + name + " failed", e); }
        }
        try {
            return j.call("ctx." + name, type, real);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new StrataException("ctx." + name + " failed", t);
        }
    }

    @Override public Instant now() { return (Instant) recorded("now", Instant.class, Instant::now); }
    @Override public long randomLong() { return (Long) recorded("randomLong", Long.class, () -> ThreadLocalRandom.current().nextLong()); }
    @Override public double randomDouble() { return (Double) recorded("randomDouble", Double.class, () -> ThreadLocalRandom.current().nextDouble()); }
    @Override public UUID randomUuid() { return (UUID) recorded("randomUuid", UUID.class, UUID::randomUUID); }

    @Override public String entityId() { return here().id(); }
    @Override public String entityType() { return here().type(); }

    private static Invocation here() {
        Invocation i = Invocation.current();
        if (i == null) throw new StrataException(StrataException.Kind.INTERNAL, "ctx is only available while a command runs");
        return i;
    }
}
