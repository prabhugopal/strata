package io.strata;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Encodes state, arguments and results as JSON so they can cross JVMs and be stored. Jackson
 * modules on the classpath (Kotlin, Scala, Java time…) are picked up automatically.
 */
public final class Codec {
    private final ObjectMapper mapper;

    public Codec(StrataSettings settings) {
        this.mapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, settings.failOnUnknownProperties());
    }

    public byte[] encode(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new StrataException("Can't encode " + (value == null ? "null" : value.getClass().getName()) + " as JSON", e);
        }
    }

    public <T> T decode(byte[] bytes, Class<T> type) {
        if (type == Void.class) return null;
        try {
            return mapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new StrataException("Can't decode JSON as " + type.getName(), e);
        }
    }
}
