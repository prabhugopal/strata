package io.strata;

import com.typesafe.config.ConfigFactory;
import io.strata.spi.InMemoryStateStore;
import io.strata.spi.StaleRevisionException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    static final EntityType<Long> COUNTER = EntityType.builder("counter", Long.class)
            .initial(() -> 0L)
            .command("increment", s -> Reply.update(s + 1, s + 1))
            .command("add", Long.class, (s, n) -> Reply.update(s + n, s + n))
            .query("get", s -> s)
            .build();

    @Test
    void settingsComeFromReferenceConfAndCanBeOverridden() {
        StrataSettings d = StrataSettings.load();
        assertEquals(Duration.ofSeconds(10), d.callTimeout());
        assertEquals(Durability.PERSISTENT, d.defaultDurability());
        assertEquals("memory", d.storeKind());

        StrataSettings o = StrataSettings.from(ConfigFactory.parseString("strata.call-timeout = 2s, strata.entity.default-durability = ephemeral")
                .withFallback(ConfigFactory.load()));
        assertEquals(Duration.ofSeconds(2), o.callTimeout());
        assertEquals(Durability.EPHEMERAL, COUNTER.durability(o));
    }

    @Test
    void commandsAreNamedFunctions() throws Exception {
        var add = COUNTER.command("add");
        assertEquals(Long.class, add.argType());
        assertEquals(Reply.update(5L, 5L), add.handler().handle(3L, 2L));
        assertTrue(COUNTER.command("get").readOnly());
        var e = assertThrows(StrataException.class, () -> COUNTER.command("nope"));
        assertTrue(e.getMessage().contains("increment, add, get"));
    }

    @Test
    void inMemoryStoreFencesStaleWriters() {
        var store = new InMemoryStateStore();
        assertEquals(1L, store.save("counter", "a", 0, new byte[]{1}).toCompletableFuture().join());
        assertEquals(2L, store.save("counter", "a", 1, new byte[]{2}).toCompletableFuture().join());
        var e = assertThrows(CompletionException.class, () -> store.save("counter", "a", 1, new byte[]{3}).toCompletableFuture().join());
        assertInstanceOf(StaleRevisionException.class, e.getCause());
        assertArrayEquals(new byte[]{2}, store.load("counter", "a").toCompletableFuture().join().orElseThrow().state());
    }

    @Test
    void codecRoundTrips() {
        var codec = new Codec(StrataSettings.load());
        assertEquals(42L, codec.decode(codec.encode(42L), Long.class));
        assertNull(codec.decode(codec.encode(null), Void.class));
    }

    @Test
    void invalidNamesAndExecutorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> EntityType.builder("1bad", Long.class));
        var bad = StrataSettings.from(ConfigFactory.parseString("strata.store.executor = elastic").withFallback(ConfigFactory.load()));
        assertThrows(IllegalArgumentException.class, bad::newStoreExecutor);
    }
}
