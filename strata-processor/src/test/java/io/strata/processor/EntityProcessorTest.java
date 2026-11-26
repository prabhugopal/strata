package io.strata.processor;

import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class EntityProcessorTest {

    record Result(boolean ok, String errors, Path out) {}

    static Result compile(String className, String source, String... options) throws Exception {
        Path out = Files.createTempDirectory("strata-processor");
        var javac = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + className.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignore) { return source; }
        };
        var args = new java.util.ArrayList<>(List.of("-d", out.toString(), "-s", out.toString(),
                "-classpath", System.getProperty("java.class.path"), "-proc:full"));
        args.addAll(List.of(options));
        var task = javac.getTask(null, null, diagnostics, args, null, List.of(file));
        task.setProcessors(List.of(new EntityProcessor()));
        boolean ok = task.call();
        String errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .map(d -> d.getMessage(null)).collect(Collectors.joining("\n"));
        return new Result(ok, errors, out);
    }

    static void assertRejected(String body, String expected) throws Exception {
        var r = compile("p.E", "package p; import io.strata.annotation.*; " + body);
        assertFalse(r.ok(), "should not compile");
        assertTrue(r.errors().contains(expected), "expected \"" + expected + "\" in:\n" + r.errors());
    }

    @Test
    void generatesTypeClientAndProvider() throws Exception {
        var r = compile("p.ShoppingCart", """
                package p;
                import io.strata.annotation.*;
                @Entity(mount = "/carts/{cartId}", durability = "ephemeral")
                public class ShoppingCart {
                    int n;
                    @Command(post = "/add") public int add(int k) { return n += k; }
                    @Query(get = "") public int n() { return n; }
                }
                """);
        assertTrue(r.ok(), r.errors());
        String entity = Files.readString(r.out().resolve("p/ShoppingCartEntity.java"));
        assertTrue(entity.contains("builder(\"shopping-cart\""), entity);
        assertTrue(entity.contains(".mount(\"/carts/{cartId}\")"));
        assertTrue(entity.contains(".durability(io.strata.Durability.EPHEMERAL)"));
        assertTrue(entity.contains(".route(\"POST\", \"/add\", \"add\")"));
        assertTrue(Files.exists(r.out().resolve("p/ShoppingCartClient.class")));
        assertEquals("p.ShoppingCartEntity", Files.readString(r.out().resolve("META-INF/services/io.strata.spi.EntityTypeProvider")).trim());
    }

    @Test
    void injectedFieldsAreFilledFromServicesNotStored() throws Exception {
        var r = compile("p.Q", """
                package p;
                import io.strata.annotation.*;
                @Entity public class Q {
                    long n;
                    @Inject java.util.function.Supplier<String> greeting;
                    @Inject(name = "clock") java.time.Clock clock;
                    @Command public String hi() { return greeting.get(); }
                }
                """);
        assertTrue(r.ok(), r.errors());
        String entity = Files.readString(r.out().resolve("p/QEntity.java"));
        assertTrue(entity.contains("(java.util.function.Supplier<java.lang.String>) io.strata.EntityType.require(services, java.util.function.Supplier.class, \"\", \"q\", \"greeting\")"), entity);
        assertTrue(entity.contains("io.strata.EntityType.require(services, java.time.Clock.class, \"clock\", \"q\", \"clock\")"), entity);
        assertTrue(entity.contains("s.greeting = v0; s.clock = v1;"), entity);
    }

    @Test
    void exposeGivesDefaultRoutesAndClientsHaveABlockingView() throws Exception {
        var r = compile("p.Wallet", """
                package p;
                import io.strata.annotation.*;
                @Entity(mount = "/wallets/{id}", expose = true)
                public class Wallet {
                    long b;
                    @Command public long depositCash(long c) { return b += c; }
                    @Command(put = "/reset") public void reset() { b = 0; }
                    @Query public long balance() { return b; }
                    @Query public long plus(long x) { return b + x; }
                }
                """);
        assertTrue(r.ok(), r.errors());
        String entity = Files.readString(r.out().resolve("p/WalletEntity.java"));
        assertTrue(entity.contains(".route(\"POST\", \"/deposit-cash\", \"depositCash\")"), entity);
        assertTrue(entity.contains(".route(\"PUT\", \"/reset\", \"reset\")"), "an explicit path wins");
        assertTrue(entity.contains(".route(\"GET\", \"/balance\", \"balance\")"));
        assertTrue(entity.contains(".route(\"POST\", \"/plus\", \"plus\")"), "a query with arguments uses POST");
        String client = Files.readString(r.out().resolve("p/WalletClient.java"));
        assertTrue(client.contains("public long depositCash(long c) {") && client.contains("io.strata.Await.result(WalletClient.this.depositCash(c))"), client);
        assertTrue(client.contains("public void reset() {"), client);
        assertRejected("@Entity(expose = true) public class E { @Command public void a() {} }", "expose = true needs a mount");
    }

    @Test
    void generatedNamesAreConfigurable() throws Exception {
        var r = compile("p.Counter", "package p; import io.strata.annotation.*; @Entity public class Counter { int n; @Command public int inc() { return ++n; } }",
                "-Astrata.entitySuffix=Type", "-Astrata.clientSuffix=Ref");
        assertTrue(r.ok(), r.errors());
        assertTrue(Files.exists(r.out().resolve("p/CounterType.class")));
        assertTrue(Files.exists(r.out().resolve("p/CounterRef.class")));
    }

    @Test
    void kebabCase() {
        assertEquals("shopping-cart", EntityProcessor.kebab("ShoppingCart"));
        assertEquals("http-server", EntityProcessor.kebab("HTTPServer"));
        assertEquals("counter", EntityProcessor.kebab("Counter"));
        assertEquals("v2-thing", EntityProcessor.kebab("V2Thing"));
    }

    @Test
    void rejectsMistakesAtCompileTime() throws Exception {
        assertRejected("@Entity public class E { int n; }", "has no @Command or @Query methods");
        assertRejected("@Entity public class E { E(int x) {} @Command public void a() {} }", "constructor without parameters");
        assertRejected("@Entity public abstract class E { @Command public void a() {} }", "can't be abstract");
        assertRejected("@Entity public class E<T> { @Command public void a() {} }", "type parameters");
        assertRejected("@Entity(name = \"9bad\") public class E { @Command public void a() {} }", "type name \"9bad\"");
        assertRejected("@Entity(durability = \"sometimes\") public class E { @Command public void a() {} }", "Unknown durability");
        assertRejected("@Entity(mount = \"/things\") public class E { @Command public void a() {} }", "exactly one {variable}");
        assertRejected("@Entity public class E { @Command(post = \"/a\") public void a() {} }", "needs @Entity(mount");
        assertRejected("@Entity(mount = \"/e/{id}\") public class E { @Query(get = \"/q\") public int q(int x) { return x; } }", "GET route can't take arguments");
        assertRejected("@Entity(mount = \"/e/{id}\") public class E { @Command(post = \"/a\", put = \"/a\") public void a() {} }", "at most one HTTP method");
        assertRejected("@Entity(mount = \"/e/{id}\") public class E { @Command(post = \"/x\") public void a() {} @Command(post = \"/x\") public void b() {} }", "already has POST \"/x\"");
        assertRejected("@Entity(mount = \"/e/{id}\") public class E { @Command(post = \"/x/{y}\") public void a() {} }", "can't have variables");
        assertRejected("@Entity public class E { @Command public void a() {} @Command(name = \"a\") public void b() {} }", "duplicate command name");
        assertRejected("@Entity public class E { @Command public void a() {} @Command public void a(int x) {} }", "overloaded");
        assertRejected("@Entity public class E { @Query public void q() {} }", "must return a value");
        assertRejected("@Entity public class E { @Command @Query public int a() { return 1; } }", "not both");
        assertRejected("@Entity public class E { @Command private void a() {} }", "non-private instance methods");
        assertRejected("@Entity public class E { @Command public void snapshot() {} }", "used by the generated client");
        assertRejected("@Entity public class E { @Inject private Runnable r; @Command public void a() {} }", "must be a non-private, non-final instance field");
        assertRejected("@Entity public class E { final java.util.List<String> xs = new java.util.ArrayList<>(); @Command public void a() {} }", "can't be final");
    }
}
