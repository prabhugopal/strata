package io.strata.reflect;

import com.fasterxml.jackson.databind.JsonNode;
import io.strata.Codec;
import io.strata.Durability;
import io.strata.EntityType;
import io.strata.EntityTypeNames;
import io.strata.Reply;
import io.strata.StrataException;
import io.strata.StrataException.Kind;
import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Http;
import io.strata.annotation.Query;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds an entity type from an {@link Entity} class at run time, by reflection. For languages
 * without Java annotation processing (Scala, or any class compiled without strata-processor);
 * Java and Kotlin (kapt) get the same type generated at compile time instead. Rules and wire
 * format match the generated code: no parameters → no argument; one → the value itself;
 * several → a JSON object by parameter name (needs parameter names in the class file) or a
 * JSON array in declaration order.
 */
public final class AnnotatedEntityTypes {
    private AnnotatedEntityTypes() {}

    public static <S> EntityType<S> of(Class<S> cls, Codec codec) {
        Entity ann = cls.getAnnotation(Entity.class);
        if (ann == null) throw invalid(cls, "isn't annotated @Entity");
        if (Modifier.isAbstract(cls.getModifiers()) || cls.isInterface()) throw invalid(cls, "can't be abstract");
        if (cls.getTypeParameters().length > 0) throw invalid(cls, "can't have type parameters");
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int m = f.getModifiers();
                if (Modifier.isFinal(m) && !Modifier.isStatic(m) && !Modifier.isTransient(m) && !f.isSynthetic()) {
                    throw invalid(cls, "state field \"" + f.getName() + "\" can't be final: Strata restores fields when it loads an entity"
                            + " (Kotlin/Scala: use var; mark it transient if it isn't state)");
                }
            }
        }
        Constructor<S> ctor;
        try {
            ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw invalid(cls, "needs a constructor without parameters (it creates the initial state)");
        }

        String name = ann.name().isEmpty() ? kebab(cls.getSimpleName()) : ann.name();
        if (!EntityTypeNames.valid(name)) throw invalid(cls, "type name \"" + name + "\" isn't valid; set @Entity(name = ...)");
        EntityType.Builder<S> b = EntityType.builder(name, cls).initial(() -> newInstance(ctor)).mutableState();
        if (!ann.durability().isEmpty()) b.durability(Durability.parse(ann.durability()));
        if (!ann.mount().isEmpty()) b.mount(ann.mount());

        Set<String> commands = new HashSet<>();
        Set<String> routes = new HashSet<>();
        for (Method m : annotatedMethods(cls)) {
            Command cmd = m.getAnnotation(Command.class);
            Query q = m.getAnnotation(Query.class);
            if (cmd != null && q != null) throw invalid(cls, m.getName() + " is either a @Command or a @Query, not both");
            if (Modifier.isStatic(m.getModifiers())) throw invalid(cls, m.getName() + " must be an instance method");
            String command = cmd != null ? cmd.name() : q.name();
            if (command.isEmpty()) command = m.getName();
            if (!commands.add(command)) throw invalid(cls, "duplicate command name \"" + command + "\"");
            if (q != null && m.getReturnType() == void.class) throw invalid(cls, "@Query " + m.getName() + " must return a value");
            m.setAccessible(true);

            Map<String, String> http = new LinkedHashMap<>();
            if (cmd != null) { http.put("POST", cmd.post()); http.put("PUT", cmd.put()); http.put("PATCH", cmd.patch()); http.put("DELETE", cmd.delete()); }
            else { http.put("GET", q.get()); http.put("POST", q.post()); }
            http.values().removeIf(Http.NONE::equals);
            if (http.size() > 1) throw invalid(cls, m.getName() + ": set at most one HTTP method");
            if (http.size() == 1) {
                var r = http.entrySet().iterator().next();
                if (ann.mount().isEmpty()) throw invalid(cls, m.getName() + " has an HTTP route; it needs @Entity(mount = \"/things/{id}\")");
                if (r.getKey().equals("GET") && m.getParameterCount() > 0) throw invalid(cls, m.getName() + ": a GET route can't take arguments; use post");
                if (!routes.add(r.getKey() + " " + r.getValue())) throw invalid(cls, "another method already has " + r.getKey() + " \"" + r.getValue() + "\"");
                b.route(r.getKey(), r.getValue(), command);
            }

            Parameter[] params = m.getParameters();
            Type[] types = m.getGenericParameterTypes();
            boolean readOnly = q != null;
            boolean returnsVoid = m.getReturnType() == void.class;
            String cmdName = command;
            Type argType = params.length == 0 ? Void.class : params.length == 1 ? types[0] : JsonNode.class;
            b.command(command, argType, readOnly, (state, arg) -> {
                Object[] args = arguments(cmdName, params, types, arg, codec);
                Object result = invoke(m, state, args);
                return readOnly ? Reply.read(result) : Reply.update(state, returnsVoid ? null : result);
            });
        }
        if (commands.isEmpty()) throw invalid(cls, "has no @Command or @Query methods");
        return b.build();
    }

    private static Object[] arguments(String command, Parameter[] params, Type[] types, Object arg, Codec codec) {
        if (params.length == 0) return new Object[0];
        if (arg == null) throw new StrataException(Kind.INVALID_ARGUMENT, command + " needs an argument");
        if (params.length == 1) return new Object[]{arg};
        JsonNode node = (JsonNode) arg;
        Object[] out = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            JsonNode v;
            if (node.isArray()) {
                v = node.get(i);
            } else if (node.isObject()) {
                if (!params[i].isNamePresent()) {
                    throw new StrataException(Kind.INVALID_ARGUMENT, command + " takes its arguments as a JSON array: its class has no parameter names "
                            + "(compile with -parameters, Kotlin -java-parameters)");
                }
                v = node.get(params[i].getName());
            } else {
                throw new StrataException(Kind.INVALID_ARGUMENT, command + " takes " + params.length + " arguments: send a JSON object or array");
            }
            if (v == null || v.isNull()) {
                if (types[i] instanceof Class<?> c && c.isPrimitive()) {
                    throw new StrataException(Kind.INVALID_ARGUMENT, command + " is missing argument " + (params[i].isNamePresent() ? params[i].getName() : "#" + (i + 1)));
                }
                out[i] = null;
            } else {
                out[i] = codec.convert(v, types[i]);
            }
        }
        return out;
    }

    private static List<Method> annotatedMethods(Class<?> cls) {
        Map<String, Method> found = new LinkedHashMap<>();
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.isBridge() || m.isSynthetic()) continue;
                if (m.getAnnotation(Command.class) == null && m.getAnnotation(Query.class) == null) continue;
                if (found.putIfAbsent(m.getName(), m) != null && m.getDeclaringClass() == cls) {
                    throw invalid(cls, "overloaded command methods aren't supported (" + m.getName() + ")");
                }
            }
        }
        return new ArrayList<>(found.values());
    }

    private static Object invoke(Method m, Object state, Object[] args) throws Exception {
        try {
            return m.invoke(state, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw e;
        }
    }

    private static <S> S newInstance(Constructor<S> ctor) {
        try {
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new StrataException("Couldn't create the initial state of " + ctor.getDeclaringClass().getName(), e);
        }
    }

    private static IllegalArgumentException invalid(Class<?> cls, String message) {
        return new IllegalArgumentException("@Entity " + cls.getName() + ": " + message);
    }

    /** {@code ShoppingCart} → {@code shopping-cart}; the same rule strata-processor uses. */
    public static String kebab(String name) {
        return name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2").replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }
}
