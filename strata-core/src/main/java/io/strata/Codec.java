package io.strata;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.util.Locale;

/**
 * Encodes state, arguments and results as JSON so they can cross JVMs and be stored. Jackson
 * modules on the classpath (Kotlin, Scala, Java time…) are picked up automatically.
 */
public final class Codec {
    private final ObjectMapper mapper;

    public Codec(StrataSettings settings) {
        this.mapper = new ObjectMapper()
                .findAndRegisterModules()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, settings.failOnUnknownProperties())
                .setVisibility(PropertyAccessor.FIELD, visibility(settings.fieldVisibility()));
    }

    private static JsonAutoDetect.Visibility visibility(String value) {
        try {
            return JsonAutoDetect.Visibility.valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("strata.codec.field-visibility must be any, non-private, protected-and-public, "
                    + "public-only, none or default, not \"" + value + "\"", e);
        }
    }

    public byte[] encode(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new StrataException(StrataException.Kind.INTERNAL,
                    "Can't encode " + (value == null ? "null" : value.getClass().getName()) + " as JSON", e);
        }
    }

    public <T> T decode(byte[] bytes, Class<T> type) {
        if (type == Void.class) return null;
        return decode(bytes, mapper.constructType(type));
    }

    public <T> T decode(byte[] bytes, TypeRef<T> type) {
        return decode(bytes, mapper.constructType(type.type()));
    }

    public <T> T decode(byte[] bytes, Type type) {
        return decode(bytes, mapper.constructType(type));
    }

    private <T> T decode(byte[] bytes, JavaType type) {
        if (type.getRawClass() == Void.class) return null;
        try {
            return mapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new StrataException(StrataException.Kind.INVALID_ARGUMENT,
                    "Can't decode JSON as " + type.toCanonical() + ": " + message(e), e);
        }
    }

    private static String message(Exception e) {
        return e instanceof com.fasterxml.jackson.core.JsonProcessingException j ? j.getOriginalMessage() : String.valueOf(e.getMessage());
    }

    /** Convert already-decoded data (e.g. a {@code JsonNode} field) to {@code type}. */
    public <T> T convert(Object value, Type type) {
        try {
            return mapper.convertValue(value, mapper.constructType(type));
        } catch (IllegalArgumentException e) {
            throw new StrataException(StrataException.Kind.INVALID_ARGUMENT, "Can't convert argument to " + type.getTypeName() + ": " + e.getMessage(), e);
        }
    }

    /** A deep copy through JSON: what the store would hold. */
    public <T> T copy(T value, Class<T> type) {
        return decode(encode(value), type);
    }
}
