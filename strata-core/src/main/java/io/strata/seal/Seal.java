package io.strata.seal;

import com.typesafe.config.Config;
import io.strata.EntityType;
import io.strata.StrataException;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The seal on entity code. Reads the bytecode of an @Entity class and of the application classes
 * it uses (those from the same build output), and checks every class and member they reference
 * against strata.seal.allow / deny. Calls on the entity's injected service types are the way out
 * (they're recorded), so they're allowed and not looked into. Runs once per type at registration.
 */
public final class Seal {
    public enum Mode { ENFORCE, WARN, OFF }

    /** One reference the seal refuses, with where it is. */
    public record Violation(String where, String reference, String reason) {
        @Override public String toString() { return where + " uses " + reference + " (" + reason + ")"; }
    }

    private static final System.Logger LOG = System.getLogger(Seal.class.getName());
    private static final String PROVIDER = "io/strata/spi/EntityTypeProvider";

    private final Mode mode;
    private final Rules allow, deny;

    public Seal(Config root) {
        Config c = root.getConfig("strata.seal");
        String m = c.getString("mode").trim().toUpperCase(Locale.ROOT);
        try {
            this.mode = Mode.valueOf(m);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("strata.seal.mode must be enforce, warn or off, not " + c.getString("mode"), e);
        }
        this.allow = new Rules(c.getStringList("allow"));
        this.deny = new Rules(c.getStringList("deny"));
    }

    public Mode mode() { return mode; }

    /** Check a type made from an @Entity class; refuse it (enforce) or log (warn). Builder-made types aren't checked. */
    public void apply(EntityType<?> type) {
        if (mode == Mode.OFF || type.source() == null) return;
        List<Violation> found = check(type.source());
        if (found.isEmpty()) return;
        String text = "Entity type " + type.name() + " isn't sealed: its code reaches outside Strata, so its effects can't be recorded."
                + " Use an injected service (@Inject) for I/O and ctx for time and randomness, or allow it in strata.seal.allow:\n  "
                + String.join("\n  ", found.stream().map(Violation::toString).toList());
        if (mode == Mode.ENFORCE) throw new StrataException(StrataException.Kind.INTERNAL, text);
        LOG.log(System.Logger.Level.WARNING, text);
    }

    /** Every violation in the entity class and the application classes it reaches. */
    public List<Violation> check(Class<?> entity) {
        ClassLoader loader = entity.getClassLoader();
        String home = location(loader, internal(entity.getName()));
        Set<String> services = new HashSet<>();
        for (Class<?> c = entity; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) if (injected(f)) services.add(internal(f.getType().getName()));
        }
        List<Violation> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>(List.of(internal(entity.getName())));
        while (!queue.isEmpty()) {
            String cls = queue.poll();
            if (!seen.add(cls)) continue;
            byte[] bytes = read(loader, cls);
            if (bytes == null) { out.add(new Violation(cls.replace('/', '.'), cls.replace('/', '.'), "class file not found")); continue; }
            ClassReader reader = new ClassReader(bytes);
            // Generated entity types (strata-processor's XxxEntity) only register the type at
            // start; they never run inside a command, so they're Strata's, not entity code.
            if (!cls.equals(internal(entity.getName())) && java.util.Arrays.asList(reader.getInterfaces()).contains(PROVIDER)) continue;
            reader.accept(new Scanner(cls, loader, home, services, queue, out), ClassReader.SKIP_FRAMES);
        }
        return out;
    }

    // ---- scanning -----------------------------------------------------------------------------

    private final class Scanner extends ClassVisitor {
        private final String cls, home;
        private final ClassLoader loader;
        private final Set<String> services;
        private final Deque<String> queue;
        private final List<Violation> out;
        private final Set<String> reported = new LinkedHashSet<>();

        Scanner(String cls, ClassLoader loader, String home, Set<String> services, Deque<String> queue, List<Violation> out) {
            super(Opcodes.ASM9);
            this.cls = cls; this.loader = loader; this.home = home; this.services = services; this.queue = queue; this.out = out;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            if (superName != null) reference(superName, null, "class " + dots(cls));
            if (interfaces != null) for (String i : interfaces) reference(i, null, "class " + dots(cls));
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            return null; // field types alone do nothing; uses are checked where they happen
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            String where = dots(cls) + "." + name;
            boolean clinit = name.equals("<clinit>");
            return new MethodVisitor(Opcodes.ASM9) {
                int line = -1;

                String at() { return line > 0 ? where + " (line " + line + ")" : where; }

                @Override public void visitLineNumber(int l, Label start) { line = l; }

                @Override
                public void visitMethodInsn(int opcode, String owner, String member, String desc, boolean itf) {
                    reference(owner, member, at());
                }

                @Override
                public void visitFieldInsn(int opcode, String owner, String member, String desc) {
                    if (opcode == Opcodes.PUTSTATIC && !clinit) {
                        violation(at(), dots(owner) + "." + member, "static mutable state is shared between entities");
                        return;
                    }
                    reference(owner, member, at());
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    if (opcode == Opcodes.NEW) reference(type, "<init>", at());
                }

                @Override
                public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
                    reference(bsm.getOwner(), bsm.getName(), at());
                    for (Object a : args) if (a instanceof Handle h) reference(h.getOwner(), h.getName(), at());
                }

                @Override
                public void visitLdcInsn(Object value) {
                    if (value instanceof Handle h) reference(h.getOwner(), h.getName(), at());
                }
            };
        }

        /** Check one referenced class (and member). Application classes are queued for scanning. */
        void reference(String owner, String member, String where) {
            if (owner.startsWith("[")) {                      // arrays: check the element type
                Type t = Type.getType(owner);
                while (t.getSort() == Type.ARRAY) t = t.getElementType();
                if (t.getSort() != Type.OBJECT) return;
                owner = t.getInternalName();
                member = null;
            }
            if (owner.equals(cls)) return;
            if (services.contains(owner)) return;             // the way out: recorded, not looked into
            String name = dots(owner);
            String denied = deny.match(name, member);
            if (denied != null) { violation(where, member == null ? name : name + "#" + member, "denied by \"" + denied + "\""); return; }
            if (allow.match(name, member) != null) return;
            if (home != null && home.equals(location(loader, owner))) { queue.add(owner); return; }   // your code: scan it too
            violation(where, member == null ? name : name + "#" + member, "not on strata.seal.allow");
        }

        void violation(String where, String reference, String reason) {
            if (reported.add(where + "|" + reference)) out.add(new Violation(where, reference, reason));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static boolean injected(Field f) {
        if (f.isAnnotationPresent(io.strata.annotation.Inject.class)) return true;
        for (var a : f.getAnnotations()) if (a.annotationType().getName().equals("jakarta.inject.Inject")) return true;
        return false;
    }

    private static String internal(String name) { return name.replace('.', '/'); }

    private static String dots(String internal) { return internal.replace('/', '.'); }

    private static byte[] read(ClassLoader loader, String internalName) {
        try (InputStream in = loader.getResourceAsStream(internalName + ".class")) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** Where a class file comes from (its directory or jar), to tell your classes from libraries. */
    private static String location(ClassLoader loader, String internalName) {
        URL url = loader == null ? null : loader.getResource(internalName + ".class");
        if (url == null) return null;
        String s = url.toString();
        int bang = s.indexOf("!/");
        if (bang >= 0) return s.substring(0, bang);           // a jar
        return s.substring(0, s.length() - (internalName + ".class").length());  // a directory
    }
}
