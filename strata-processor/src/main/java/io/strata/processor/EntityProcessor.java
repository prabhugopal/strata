package io.strata.processor;

import io.strata.Durability;
import io.strata.EntityType;
import io.strata.EntityTypeNames;
import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Http;
import io.strata.annotation.Query;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generates, for each {@link Entity} class {@code Foo}: {@code FooEntity} (its {@code EntityType}
 * as {@code FooEntity.TYPE}, discoverable through ServiceLoader) and {@code FooClient} (typed
 * calls). Mistakes in the annotations are compile errors on the offending element.
 *
 * <p>Options ({@code -A}): {@code strata.entitySuffix} (default {@value #DEFAULT_ENTITY_SUFFIX}),
 * {@code strata.clientSuffix} (default {@value #DEFAULT_CLIENT_SUFFIX}).
 */
@SupportedAnnotationTypes("io.strata.annotation.Entity")
@SupportedOptions({EntityProcessor.ENTITY_SUFFIX_OPTION, EntityProcessor.CLIENT_SUFFIX_OPTION})
public final class EntityProcessor extends AbstractProcessor {
    static final String ENTITY_SUFFIX_OPTION = "strata.entitySuffix";
    static final String CLIENT_SUFFIX_OPTION = "strata.clientSuffix";
    static final String DEFAULT_ENTITY_SUFFIX = "Entity";
    static final String DEFAULT_CLIENT_SUFFIX = "Client";
    private static final String PROVIDERS = "META-INF/services/io.strata.spi.EntityTypeProvider";
    /** Names the generated client uses for itself; entity methods can't take them. */
    private static final Set<String> CLIENT_RESERVED = Set.of("of", "entityId", "snapshot", "withIdempotencyKey", "blocking");

    private final Set<String> providers = new TreeSet<>();

    @Override
    public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }

    /** A rejected annotation, reported against {@code element}. */
    private static final class Invalid extends Exception {
        final Element element;
        Invalid(Element element, String message) { super(message); this.element = element; }
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
        for (Element e : round.getElementsAnnotatedWith(Entity.class)) {
            try {
                Model m = model(e);
                write(m.pkg, m.entityClass, entitySource(m), e);
                write(m.pkg, m.clientClass, clientSource(m), e);
                providers.add(qualified(m.pkg, m.entityClass));
            } catch (Invalid x) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "@Entity: " + x.getMessage(), x.element);
            }
        }
        if (round.processingOver() && !providers.isEmpty()) writeProviders();
        return true;
    }

    // ---- model ------------------------------------------------------------------------------

    private record Param(String name, TypeMirror type) {}

    private record Op(ExecutableElement method, String command, boolean query, List<Param> params,
                      TypeMirror returns, String httpMethod, String httpPath) {
        /** Several parameters travel as a JSON object (a generated record); one travels as itself. */
        boolean wrapsArgs() { return params.size() > 1; }
        String argsRecord() { return Character.toUpperCase(command.charAt(0)) + command.substring(1).replaceAll("[^A-Za-z0-9_]", "_") + "Args"; }
    }

    private record Injected(String field, TypeMirror type, String name) {}

    private record Model(TypeElement type, String pkg, String stateType, String entityClass, String clientClass,
                         String name, Durability durability, String mount, List<Op> ops, List<Injected> injected) {}

    /** The @Inject annotation on a field (Strata's or jakarta.inject's), or null. */
    private static String injectName(VariableElement f) {
        io.strata.annotation.Inject strata = f.getAnnotation(io.strata.annotation.Inject.class);
        if (strata != null) return strata.name();
        for (var a : f.getAnnotationMirrors()) {
            if (((TypeElement) a.getAnnotationType().asElement()).getQualifiedName().contentEquals("jakarta.inject.Inject")) return "";
        }
        return null;
    }

    private Model model(Element e) throws Invalid {
        if (e.getKind() != ElementKind.CLASS) throw new Invalid(e, "only classes can be entities");
        TypeElement type = (TypeElement) e;
        Set<Modifier> mods = type.getModifiers();
        if (mods.contains(Modifier.ABSTRACT)) throw new Invalid(e, "an entity class can't be abstract");
        if (mods.contains(Modifier.PRIVATE)) throw new Invalid(e, "an entity class can't be private");
        if (!type.getTypeParameters().isEmpty()) throw new Invalid(e, "an entity class can't have type parameters");
        if (type.getNestingKind() != NestingKind.TOP_LEVEL && !mods.contains(Modifier.STATIC)) {
            throw new Invalid(e, "a nested entity class must be static");
        }
        boolean hasNoArgCtor = ElementFilter.constructorsIn(type.getEnclosedElements()).stream()
                .anyMatch(c -> c.getParameters().isEmpty() && !c.getModifiers().contains(Modifier.PRIVATE));
        if (!hasNoArgCtor) throw new Invalid(e, "needs a non-private constructor without parameters (it creates the initial state)");

        List<Injected> injected = new ArrayList<>();
        for (VariableElement f : ElementFilter.fieldsIn(processingEnv.getElementUtils().getAllMembers(type))) {
            Set<Modifier> fm = f.getModifiers();
            String inject = injectName(f);
            if (inject != null) {
                if (fm.contains(Modifier.STATIC) || fm.contains(Modifier.FINAL) || fm.contains(Modifier.PRIVATE)) {
                    throw new Invalid(f, "@Inject field \"" + f.getSimpleName() + "\" must be a non-private, non-final instance field (Strata sets it)");
                }
                injected.add(new Injected(f.getSimpleName().toString(), f.asType(), inject));
                continue;
            }
            if (fm.contains(Modifier.FINAL) && !fm.contains(Modifier.STATIC) && !fm.contains(Modifier.TRANSIENT)) {
                throw new Invalid(f, "state field \"" + f.getSimpleName() + "\" can't be final: Strata restores fields when it loads "
                        + "an entity (mark it transient if it isn't state)");
            }
        }

        Entity ann = type.getAnnotation(Entity.class);
        String name = ann.name().isEmpty() ? kebab(type.getSimpleName().toString()) : ann.name();
        if (!EntityTypeNames.valid(name)) {
            throw new Invalid(e, "type name \"" + name + "\" must be 1-" + EntityTypeNames.MAX_LENGTH
                    + " letters, digits, '-' or '_', starting with a letter; set @Entity(name = ...)");
        }
        Durability durability = null;
        if (!ann.durability().isEmpty()) {
            try { durability = Durability.parse(ann.durability()); } catch (IllegalArgumentException x) { throw new Invalid(e, x.getMessage()); }
        }
        String mount = ann.mount().isEmpty() ? null : ann.mount();
        if (mount != null) {
            try { EntityType.idVariable(mount); } catch (IllegalArgumentException x) { throw new Invalid(e, x.getMessage()); }
        }

        List<Op> ops = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> routes = new HashSet<>();
        Set<String> javaNames = new HashSet<>();
        for (ExecutableElement m : ElementFilter.methodsIn(processingEnv.getElementUtils().getAllMembers(type))) {
            Command cmd = m.getAnnotation(Command.class);
            Query q = m.getAnnotation(Query.class);
            if (cmd == null && q == null) continue;
            if (cmd != null && q != null) throw new Invalid(m, "a method is either a @Command or a @Query, not both");
            if (m.getModifiers().contains(Modifier.PRIVATE) || m.getModifiers().contains(Modifier.STATIC)) {
                throw new Invalid(m, "commands and queries must be non-private instance methods");
            }
            if (!m.getTypeParameters().isEmpty()) throw new Invalid(m, "commands and queries can't have type parameters");
            String method = m.getSimpleName().toString();
            if (CLIENT_RESERVED.contains(method)) throw new Invalid(m, "\"" + method + "\" is used by the generated client; rename the method");
            if (!javaNames.add(method)) throw new Invalid(m, "overloaded command methods aren't supported; give each a different name");
            String command = (cmd != null ? cmd.name() : q.name());
            if (command.isEmpty()) command = method;
            if (!command.matches("[A-Za-z][A-Za-z0-9_-]*")) throw new Invalid(m, "command name \"" + command + "\" must be letters, digits, '-' or '_', starting with a letter");
            if (!names.add(command)) throw new Invalid(m, "duplicate command name \"" + command + "\"");
            if (q != null && m.getReturnType().getKind() == TypeKind.VOID) throw new Invalid(m, "a @Query must return a value");

            Map<String, String> http = new LinkedHashMap<>();
            if (cmd != null) {
                http.put("POST", cmd.post()); http.put("PUT", cmd.put()); http.put("PATCH", cmd.patch()); http.put("DELETE", cmd.delete());
            } else {
                http.put("GET", q.get()); http.put("POST", q.post());
            }
            http.values().removeIf(Http.NONE::equals);
            if (http.isEmpty() && ann.expose()) {
                if (mount == null) throw new Invalid(e, "expose = true needs a mount, e.g. @Entity(mount = \"/things/{id}\", expose = true)");
                String path = "/" + kebab(command);
                http.put(q != null && m.getParameters().isEmpty() ? "GET" : "POST", path);
            }
            if (http.size() > 1) throw new Invalid(m, "set at most one HTTP method (has " + String.join(", ", http.keySet()) + ")");
            String httpMethod = null, httpPath = null;
            if (http.size() == 1) {
                var route = http.entrySet().iterator().next();
                httpMethod = route.getKey();
                httpPath = route.getValue();
                if (mount == null) throw new Invalid(m, "an HTTP route needs @Entity(mount = \"/things/{id}\") on the class");
                if (!httpPath.isEmpty() && !httpPath.startsWith("/")) throw new Invalid(m, "route path must be empty or start with '/': " + httpPath);
                if (httpPath.contains("{")) throw new Invalid(m, "route paths can't have variables; arguments come from the request body");
                if (httpMethod.equals("GET") && !m.getParameters().isEmpty()) {
                    throw new Invalid(m, "a GET route can't take arguments (it has no body); use post = \"" + httpPath + "\"");
                }
                if (!routes.add(httpMethod + " " + httpPath)) throw new Invalid(m, "another method already has " + httpMethod + " \"" + httpPath + "\"");
            }
            List<Param> params = new ArrayList<>();
            for (VariableElement p : m.getParameters()) params.add(new Param(p.getSimpleName().toString(), p.asType()));
            ops.add(new Op(m, command, q != null, params, m.getReturnType(), httpMethod, httpPath));
        }
        if (ops.isEmpty()) throw new Invalid(e, "has no @Command or @Query methods");

        PackageElement pkg = processingEnv.getElementUtils().getPackageOf(type);
        String base = flatName(type);
        return new Model(type, pkg.isUnnamed() ? "" : pkg.getQualifiedName().toString(), type.getQualifiedName().toString(),
                base + option(ENTITY_SUFFIX_OPTION, DEFAULT_ENTITY_SUFFIX), base + option(CLIENT_SUFFIX_OPTION, DEFAULT_CLIENT_SUFFIX),
                name, durability, mount, ops, injected);
    }

    // ---- generated sources --------------------------------------------------------------------

    private String entitySource(Model m) {
        String s = m.stateType();
        StringBuilder b = header(m.pkg());
        b.append("/** The entity type generated from {@link ").append(s).append("}. */\n")
         .append("@javax.annotation.processing.Generated(\"").append(EntityProcessor.class.getName()).append("\")\n")
         .append("@SuppressWarnings(\"unchecked\")\n")
         .append("public final class ").append(m.entityClass()).append(" implements io.strata.spi.EntityTypeProvider {\n")
         .append("    public static final io.strata.EntityType<").append(s).append("> TYPE = io.strata.EntityType.builder(")
         .append(literal(m.name())).append(", ").append(s).append(".class)\n")
         .append("            .initial(").append(s).append("::new)\n")
         .append("            .source(").append(s).append(".class)\n")
         .append("            .mutableState()\n");
        if (m.durability() != null) b.append("            .durability(io.strata.Durability.").append(m.durability().name()).append(")\n");
        if (!m.injected().isEmpty()) {
            // Services are resolved once, at registration; each instance just gets field writes.
            b.append("            .inject(services -> {\n");
            for (int i = 0; i < m.injected().size(); i++) {
                Injected in = m.injected().get(i);
                b.append("                ").append(in.type()).append(" v").append(i).append(" = ")
                 .append(isGeneric(in.type()) ? "(" + in.type() + ") " : "")
                 .append("io.strata.EntityType.require(services, ").append(erasure(in.type())).append(".class, ")
                 .append(literal(in.name())).append(", ").append(literal(m.name())).append(", ").append(literal(in.field())).append(");\n");
            }
            b.append("                return s -> {");
            for (int i = 0; i < m.injected().size(); i++) b.append(" s.").append(m.injected().get(i).field()).append(" = v").append(i).append(";");
            b.append(" };\n            })\n");
        }
        if (m.mount() != null) b.append("            .mount(").append(literal(m.mount())).append(")\n");
        for (Op op : m.ops()) {
            String call = "s." + op.method().getSimpleName() + "(" + callArgs(op) + ")";
            String argType = argClass(m, op);
            String guard = op.params().isEmpty() ? "" : "need(a, " + literal(op.command()) + "); ";
            if (op.query()) {
                b.append("            .query(").append(literal(op.command())).append(", ").append(argType)
                 .append(", (s, a) -> { ").append(guard).append("return ").append(call).append("; })\n");
            } else if (op.params().isEmpty()) {
                b.append("            .command(").append(literal(op.command())).append(", s -> ")
                 .append(update(op, call)).append(")\n");
            } else {
                b.append("            .command(").append(literal(op.command())).append(", ").append(argType)
                 .append(", (s, a) -> { ").append(guard).append(updateBody(op, call)).append(" })\n");
            }
        }
        for (Op op : m.ops()) {
            b.append("            .describe(").append(literal(op.command())).append(", java.util.List.of(");
            for (int i = 0; i < op.params().size(); i++) {
                Param p = op.params().get(i);
                b.append(i == 0 ? "" : ", ").append("new io.strata.EntityType.Param(").append(literal(p.name())).append(", ").append(typeExpr(p.type())).append(")");
            }
            b.append("), ").append(op.returns().getKind() == TypeKind.VOID ? "java.lang.Void.class" : typeExpr(op.returns())).append(")\n");
        }
        for (Op op : m.ops()) {
            if (op.httpMethod() != null) {
                b.append("            .route(").append(literal(op.httpMethod())).append(", ").append(literal(op.httpPath()))
                 .append(", ").append(literal(op.command())).append(")\n");
            }
        }
        b.append("            .build();\n\n");
        for (Op op : m.ops()) {
            if (!op.wrapsArgs()) continue;
            b.append("    /** Arguments of {@code ").append(op.command()).append("}, sent as a JSON object. */\n")
             .append("    public record ").append(op.argsRecord()).append("(");
            for (int i = 0; i < op.params().size(); i++) {
                Param p = op.params().get(i);
                b.append(i == 0 ? "" : ", ").append(p.type()).append(' ').append(p.name());
            }
            b.append(") {}\n\n");
        }
        b.append("    /** For ServiceLoader discovery; use {@link #TYPE}. */\n")
         .append("    public ").append(m.entityClass()).append("() {}\n\n")
         .append("    @Override public io.strata.EntityType<?> entityType() { return TYPE; }\n\n")
         .append("    private static void need(Object arg, String command) {\n")
         .append("        if (arg == null) throw new io.strata.StrataException(io.strata.StrataException.Kind.INVALID_ARGUMENT, command + \" needs an argument\");\n")
         .append("    }\n}\n");
        return b.toString();
    }

    private static String update(Op op, String call) {
        return op.returns().getKind() == TypeKind.VOID
                ? "{ " + call + "; return io.strata.Reply.update(s, null); }"
                : "io.strata.Reply.update(s, " + call + ")";
    }

    private static String updateBody(Op op, String call) {
        return op.returns().getKind() == TypeKind.VOID
                ? call + "; return io.strata.Reply.update(s, null);"
                : "return io.strata.Reply.update(s, " + call + ");";
    }

    /** Arguments as the handler passes them to the entity method. */
    private static String callArgs(Op op) {
        if (op.params().isEmpty()) return "";
        if (!op.wrapsArgs()) return "a";
        return String.join(", ", op.params().stream().map(p -> "a." + p.name() + "()").toList());
    }

    private String argClass(Model m, Op op) {
        if (op.params().isEmpty()) return "java.lang.Void.class";
        if (op.wrapsArgs()) return m.entityClass() + "." + op.argsRecord() + ".class";
        TypeMirror t = op.params().getFirst().type();
        return isGeneric(t) ? "new io.strata.TypeRef<" + t + ">() {}" : erasure(t) + ".class";
    }

    private String clientSource(Model m) {
        String s = m.stateType();
        String ref = "io.strata.EntityRef<" + s + ">";
        StringBuilder b = header(m.pkg());
        b.append("/** Typed calls to {@link ").append(s).append("} entities. */\n")
         .append("@javax.annotation.processing.Generated(\"").append(EntityProcessor.class.getName()).append("\")\n")
         .append("public final class ").append(m.clientClass()).append(" {\n")
         .append("    private final ").append(ref).append(" ref;\n\n")
         .append("    private ").append(m.clientClass()).append("(").append(ref).append(" ref) { this.ref = ref; }\n\n")
         .append("    /** The entity with this id (created on first use), from a runtime or, inside an entity, from its injected Entities. */\n")
         .append("    public static ").append(m.clientClass()).append(" of(io.strata.Entities entities, String id) {\n")
         .append("        return new ").append(m.clientClass()).append("(entities.ref(").append(m.entityClass()).append(".TYPE, id));\n")
         .append("    }\n\n")
         .append("    public String entityId() { return ref.id(); }\n\n")
         .append("    /** The same entity; a repeated call with the same key gets the first call's outcome instead of running again. */\n")
         .append("    public ").append(m.clientClass()).append(" withIdempotencyKey(String key) { return new ").append(m.clientClass()).append("(ref.withIdempotencyKey(key)); }\n\n")
         .append("    /** The entity's whole current state. */\n")
         .append("    public java.util.concurrent.CompletionStage<").append(s).append("> snapshot() { return ref.state(); }\n");
        for (Op op : m.ops()) {
            String result = boxed(op.returns());
            b.append("\n    public java.util.concurrent.CompletionStage<").append(result).append("> ").append(op.method().getSimpleName()).append("(");
            for (int i = 0; i < op.params().size(); i++) {
                Param p = op.params().get(i);
                b.append(i == 0 ? "" : ", ").append(p.type()).append(' ').append(p.name());
            }
            String arg = op.params().isEmpty() ? "null"
                    : op.wrapsArgs() ? "new " + m.entityClass() + "." + op.argsRecord() + "(" + String.join(", ", op.params().stream().map(Param::name).toList()) + ")"
                    : op.params().getFirst().name();
            String resultType = isGeneric(op.returns()) ? "new io.strata.TypeRef<" + result + ">() {}" : erasure(op.returns()) + ".class";
            b.append(") {\n        return ref.call(").append(literal(op.command())).append(", ").append(arg).append(", ").append(resultType).append(");\n    }\n");
        }
        // The same calls, waiting for the answer: for code on virtual threads, such as commands
        // calling other entities (errors come back as themselves, not wrapped).
        b.append("\n    /** These calls, waiting for each answer (on virtual threads, e.g. inside another entity's command). */\n")
         .append("    public Blocking blocking() { return new Blocking(); }\n\n")
         .append("    public final class Blocking {\n")
         .append("        private Blocking() {}\n");
        for (Op op : m.ops()) {
            String result = boxed(op.returns());
            boolean isVoid = op.returns().getKind() == TypeKind.VOID;
            b.append("\n        public ").append(isVoid ? "void" : op.returns().toString()).append(' ').append(op.method().getSimpleName()).append("(");
            for (int i = 0; i < op.params().size(); i++) {
                Param p = op.params().get(i);
                b.append(i == 0 ? "" : ", ").append(p.type()).append(' ').append(p.name());
            }
            b.append(") {\n            ").append(isVoid ? "" : "return ").append("io.strata.Await.result(").append(m.clientClass()).append(".this.")
             .append(op.method().getSimpleName()).append("(").append(String.join(", ", op.params().stream().map(Param::name).toList())).append("));\n        }\n");
        }
        b.append("    }\n");
        return b.append("}\n").toString();
    }

    private static StringBuilder header(String pkg) {
        StringBuilder b = new StringBuilder("// Generated by strata-processor from an @Entity class. Don't edit.\n");
        if (!pkg.isEmpty()) b.append("package ").append(pkg).append(";\n\n");
        return b;
    }

    // ---- types --------------------------------------------------------------------------------

    private static boolean isGeneric(TypeMirror t) {
        return switch (t.getKind()) {
            case DECLARED -> !((DeclaredType) t).getTypeArguments().isEmpty();
            case ARRAY -> isGeneric(((ArrayType) t).getComponentType());
            case TYPEVAR, WILDCARD, INTERSECTION, UNION -> true;
            default -> false;
        };
    }

    /** A {@code java.lang.reflect.Type} expression for {@code t}, keeping generic arguments. */
    private String typeExpr(TypeMirror t) {
        if (t.getKind().isPrimitive()) return t + ".class";
        return isGeneric(t) ? "new io.strata.TypeRef<" + t + ">() {}.type()" : erasure(t) + ".class";
    }

    /** The class literal's type: boxed primitives, raw declared types. */
    private String erasure(TypeMirror t) {
        if (t.getKind() == TypeKind.VOID) return "java.lang.Void";
        if (t.getKind().isPrimitive()) return processingEnv.getTypeUtils().boxedClass((javax.lang.model.type.PrimitiveType) t).getQualifiedName().toString();
        return processingEnv.getTypeUtils().erasure(t).toString();
    }

    private String boxed(TypeMirror t) {
        if (t.getKind() == TypeKind.VOID) return "java.lang.Void";
        if (t.getKind().isPrimitive()) return erasure(t);
        return t.toString();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private String option(String key, String fallback) {
        String v = processingEnv.getOptions().get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /** {@code Outer.Inner} → {@code Outer_Inner}, so nested entities get distinct generated names. */
    private static String flatName(TypeElement type) {
        StringBuilder b = new StringBuilder(type.getSimpleName());
        for (Element p = type.getEnclosingElement(); p instanceof TypeElement t; p = t.getEnclosingElement()) {
            b.insert(0, t.getSimpleName() + "_");
        }
        return b.toString();
    }

    /** {@code ShoppingCart} → {@code shopping-cart}, {@code HTTPServer} → {@code http-server}. */
    static String kebab(String name) {
        return name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2").replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(Locale.ROOT);
    }

    private static String literal(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    private static String qualified(String pkg, String cls) { return pkg.isEmpty() ? cls : pkg + "." + cls; }

    private void write(String pkg, String cls, String source, Element origin) throws Invalid {
        try (Writer w = processingEnv.getFiler().createSourceFile(qualified(pkg, cls), origin).openWriter()) {
            w.write(source);
        } catch (IOException e) {
            throw new Invalid(origin, "couldn't write " + qualified(pkg, cls) + ": " + e.getMessage());
        }
    }

    private void writeProviders() {
        Set<String> all = new LinkedHashSet<>(providers);
        try (Writer w = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", PROVIDERS).openWriter()) {
            for (String p : all) w.write(p + "\n");
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Couldn't write " + PROVIDERS + ": " + e.getMessage());
        }
    }
}
