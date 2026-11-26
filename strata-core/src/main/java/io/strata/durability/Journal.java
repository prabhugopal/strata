package io.strata.durability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.strata.Codec;
import io.strata.StrataException;

import java.lang.reflect.Constructor;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * The service calls of one invocation. A call that was already recorded (we're resuming after a
 * crash) returns its recorded result, or throws its recorded exception, without running; a new
 * call runs, and its outcome is stored before the entity's code continues, so the effect is never
 * repeated. Bound to the thread running the invocation.
 */
public final class Journal {
    /** Stores one entry; must be durable when it returns. */
    @FunctionalInterface
    public interface Writer {
        void write(int seq, byte[] entry) throws Exception;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ThreadLocal<String> CALL_KEY = new ThreadLocal<>();

    /** The key of the recorded call running on this thread ({@code <invocation>/<call number>}), or null. */
    public static String currentCallKey() { return CALL_KEY.get(); }

    private final String invocationId;
    private final List<byte[]> recorded;
    private final Writer writer;
    private final Codec codec;
    private int seq;
    private int replayed;

    public Journal(String invocationId, List<byte[]> recorded, Writer writer, Codec codec) {
        this.invocationId = invocationId;
        this.recorded = recorded;
        this.writer = writer;
        this.codec = codec;
    }

    /** The journal of the invocation running on this thread, or null. */
    public static Journal current() {
        Invocation i = Invocation.current();
        return i == null ? null : i.journal();
    }

    public String invocationId() { return invocationId; }

    /** What a recorded call does when it runs for real; gets the call's number (1-based). */
    @FunctionalInterface
    public interface Real {
        Object run(int seq) throws Exception;
    }

    /** How many calls were answered from the journal rather than run. */
    public int replayed() { return replayed; }

    /** How many calls this invocation made. */
    public int calls() { return seq; }

    /** One service call, by name (e.g. "Payments.charge"), returning {@code resultType}. */
    public Object call(String name, Type resultType, Callable<Object> real) throws Throwable {
        return call(name, resultType, (Real) n -> real.call());
    }

    /** As above; the real call gets its number, e.g. to make an idempotency key for it. */
    public Object call(String name, Type resultType, Real real) throws Throwable {
        int n = ++seq;
        if (n <= recorded.size()) {
            replayed++;
            JsonNode e = JSON.readTree(recorded.get(n - 1));
            if (!name.equals(e.path("call").asText())) {
                throw new ReplayDivergedException("Replay diverged at call " + n + ": the journal has "
                        + e.path("call").asText() + " but the code now calls " + name + ". Did the entity's code change while a command was running?");
            }
            if (e.has("error")) throw rebuild(e.get("error"));
            return resultType == void.class || resultType == Void.class ? null : codec.convert(e.get("ok"), resultType);
        }
        ObjectNode entry = JSON.createObjectNode().put("call", name);
        Object result;
        Exception failure = null;
        String outer = CALL_KEY.get();
        CALL_KEY.set(invocationId + "/" + n);
        try {
            result = real.run(n);
            entry.set("ok", JSON.readTree(codec.encode(result)));
        } catch (Exception ex) {
            result = null;
            failure = ex;
            ObjectNode err = entry.putObject("error").put("type", ex.getClass().getName()).put("message", ex.getMessage());
            if (ex instanceof StrataException se) err.put("kind", se.kind().name());
        } finally {
            if (outer == null) CALL_KEY.remove(); else CALL_KEY.set(outer);
        }
        try {
            writer.write(n, JSON.writeValueAsBytes(entry));
        } catch (Exception e) {
            throw new JournalWriteException("Couldn't record call " + n + " (" + name + ")", e);
        }
        if (failure != null) throw failure;
        return result;
    }

    /** The recorded exception again: same class when it has a (String) constructor, else a StrataException. */
    private static Exception rebuild(JsonNode error) {
        String type = error.path("type").asText();
        String message = error.path("message").isNull() ? null : error.path("message").asText();
        if (error.has("kind")) return new StrataException(StrataException.Kind.parse(error.get("kind").asText()), message);
        try {
            Class<?> c = Class.forName(type, false, Thread.currentThread().getContextClassLoader());
            if (Exception.class.isAssignableFrom(c)) {
                Constructor<?> k = c.getConstructor(String.class);
                return (Exception) k.newInstance(message);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // fall through
        }
        return new StrataException(StrataException.Kind.FAILED, type + ": " + message);
    }
}
