package io.strata.examples.clojure;

import io.strata.Strata;
import io.strata.StrataException;
import io.strata.TypeRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/** The defentity account is loaded from config and behaves like the Java, Kotlin and Scala ones. */
class ClojureDefentityTest {
    static <T> T await(java.util.concurrent.CompletionStage<T> s) throws Exception { return s.toCompletableFuture().get(); }

    @Test
    void clojureEntityFromConfig() throws Exception {
        try (var strata = Strata.start(ClojureInteropTest.config())) {
            var type = strata.types().stream().filter(t -> t.name().equals("account")).findFirst().orElseThrow();
            assertEquals("/accounts/{id}", type.mount());
            assertEquals(4, type.routes().size());
            var acct = strata.ref(type, "c1");
            assertEquals(100L, await(acct.call("deposit", 100, Long.class)));
            assertEquals(60L, await(acct.call("withdraw", 40, Long.class)));
            var e = assertThrows(ExecutionException.class, () -> await(acct.call("withdraw", 1000, Long.class)));
            assertEquals(StrataException.Kind.FAILED, ((StrataException) e.getCause()).kind());
            assertTrue(e.getCause().getMessage().contains("insufficient funds"));
            assertEquals(60L, await(acct.call("balance", Long.class)));
            assertEquals(List.of("deposit 100", "withdraw 40"), await(acct.call("history", null, new TypeRef<List<String>>() {})));
        }
    }
}
