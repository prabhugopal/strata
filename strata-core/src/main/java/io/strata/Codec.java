package io.strata;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
                .setVisibility(PropertyAccessor.FIELD, visibility(settings.fieldVisibility()))
                .setAnnotationIntrospector(new InjectedFieldsIgnored());
    }

    /** @Inject fields are collaborators, not state: never stored or sent. */
    private static final class InjectedFieldsIgnored extends com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector {
        @Override
        public boolean hasIgnoreMarker(com.fasterxml.jackson.databind.introspect.AnnotatedMember m) {
            if (m.hasAnnotation(io.strata.annotation.Inject.class)) return true;
            for (var a : m.getAnnotated() instanceof java.lang.reflect.AnnotatedElement e ? e.getAnnotations() : new java.lang.annotation.Annotation[0]) {
                if (a.annotationType().getName().equals("jakarta.inject.Inject")) return true;
            }
            return super.hasIgnoreMarker(m);
        }
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

    /**
     * A JSON-Schema-like description of {@code type} (type, items, properties, enum), for tools
     * that build forms. Deeper than {@code maxDepth}, or recursive, types are described as {@code {}} (anything).
     */
    public Map<String, Object> schema(Type type, int maxDepth) {
        return schema(mapper.constructType(type), maxDepth, new java.util.HashSet<>());
    }

    private Map<String, Object> schema(JavaType t, int depth, java.util.Set<Class<?>> seen) {
        Map<String, Object> out = new LinkedHashMap<>();
        Class<?> c = t.getRawClass();
        if (c == boolean.class || c == Boolean.class) out.put("type", "boolean");
        else if (c == int.class || c == long.class || c == short.class || c == byte.class || c == Integer.class || c == Long.class
                || c == Short.class || c == Byte.class || c == java.math.BigInteger.class) out.put("type", "integer");
        else if (c == double.class || c == float.class || c == Double.class || c == Float.class || c == java.math.BigDecimal.class) out.put("type", "number");
        else if (c == String.class || c == char.class || c == Character.class || CharSequence.class.isAssignableFrom(c)) out.put("type", "string");
        else if (c.isEnum()) {
            out.put("type", "string");
            out.put("enum", java.util.Arrays.stream(c.getEnumConstants()).map(Object::toString).toList());
        } else if (depth <= 0 || c == Object.class || com.fasterxml.jackson.databind.JsonNode.class.isAssignableFrom(c) || !seen.add(c)) {
            return out; // anything
        } else if (t.isArrayType() || t.isCollectionLikeType()) {
            out.put("type", "array");
            out.put("items", schema(t.getContentType(), depth - 1, seen));
        } else if (t.isMapLikeType()) {
            out.put("type", "object");
            out.put("additionalProperties", schema(t.getContentType(), depth - 1, seen));
        } else if (t.isReferenceType()) { // Optional<T>
            out.putAll(schema(t.getReferencedType(), depth - 1, seen));
            out.put("nullable", true);
        } else if (c.getName().startsWith("java.time.")) {
            out.put("type", "string");
            out.put("format", c.getSimpleName());
        } else {
            Map<String, Object> props = new LinkedHashMap<>();
            var bean = mapper.getDeserializationConfig().introspect(t);
            for (var p : bean.findProperties()) {
                if (p.couldDeserialize()) props.put(p.getName(), schema(p.getPrimaryType(), depth - 1, seen));
            }
            out.put("type", "object");
            out.put("properties", props);
        }
        seen.remove(c);
        return out;
    }

    /** A deep copy through JSON: what the store would hold. */
    public <T> T copy(T value, Class<T> type) {
        return decode(encode(value), type);
    }
}
