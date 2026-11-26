package io.strata.seal;

import com.typesafe.config.ConfigFactory;
import io.strata.EntityType;
import io.strata.StrataException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SealTest {
    final Seal seal = new Seal(ConfigFactory.load());

    String violations(Class<?> c) { return String.join("\n", seal.check(c).stream().map(Seal.Violation::toString).toList()); }

    @Test
    void pureLogicAndInjectedServicesAreSealed() {
        assertEquals("", violations(Samples.Clean.class));
    }

    @Test
    void theOutsideWorldIsRefused() {
        assertTrue(violations(Samples.UsesClock.class).contains("java.lang.System#currentTimeMillis"));
        assertTrue(violations(Samples.UsesNow.class).contains("java.time.Instant#now"));
        assertTrue(violations(Samples.UsesRandom.class).contains("java.util.Random"));
        assertTrue(violations(Samples.CallsHttp.class).contains("java.net.http.HttpClient"));
        assertTrue(violations(Samples.CallsHttp.class).contains("not on strata.seal.allow"));
        assertTrue(violations(Samples.Sleeps.class).contains("java.lang.Thread#sleep"));
        assertTrue(violations(Samples.SharesState.class).contains("static mutable state"));
        assertTrue(violations(Samples.Reflects.class).contains("java.lang.Class#forName"));
        assertTrue(violations(Samples.MethodReference.class).contains("java.lang.System#nanoTime"), "method references are checked too");
    }

    @Test
    void yourOwnHelpersAreCheckedThrough() {
        String v = violations(Samples.UsesHelper.class);
        assertTrue(v.contains("Samples$Audit.write") && v.contains("java.nio.file.Files"), v);
        assertTrue(v.contains("(line "), "violations say where: " + v);
    }

    @Test
    void modesAndAllowlistAreConfigurable() {
        var type = EntityType.builder("clock", Samples.UsesClock.class).initial(Samples.UsesClock::new)
                .command("stamp", s -> io.strata.Reply.update(s, s.stamp())).source(Samples.UsesClock.class).build();
        var e = assertThrows(StrataException.class, () -> seal.apply(type));
        assertTrue(e.getMessage().contains("isn't sealed") && e.getMessage().contains("currentTimeMillis"), e.getMessage());

        new Seal(ConfigFactory.parseMap(Map.of("strata.seal.mode", "warn")).withFallback(ConfigFactory.load())).apply(type); // logs only
        new Seal(ConfigFactory.parseMap(Map.of("strata.seal.mode", "off")).withFallback(ConfigFactory.load())).apply(type);

        // An app can allow what it trusts; deny still wins.
        var cfg = ConfigFactory.parseString("strata.seal.allow += \"java.net.http.**\"").withFallback(ConfigFactory.load()).resolve();
        String http = String.join("\n", new Seal(cfg).check(Samples.CallsHttp.class).stream().map(Seal.Violation::toString).toList());
        assertFalse(http.contains("java.net.http.HttpClient"), http);

        // Builder-made types (plain lambdas) have no source class and aren't checked.
        seal.apply(EntityType.builder("n", Long.class).initial(() -> 0L).query("get", s -> s).build());
    }

    @Test
    void patterns() {
        var r = new Rules(List.of("java.util.*", "java.time.**#now", "kotlin.io.**", "scala.Predef$#print*"));
        assertNotNull(r.match("java.util.ArrayList", "add"));
        assertNull(r.match("java.util.concurrent.Executors", "x"), "* is one package level");
        assertNotNull(r.match("java.time.chrono.HijrahDate", "now"));
        assertNull(r.match("java.time.Instant", "plus"));
        assertNotNull(r.match("kotlin.io.FilesKt", null));
        assertNotNull(r.match("scala.Predef$", "println"));
        assertNull(r.match("scala.Predef$", "require"));
    }
}
