package io.strata.examples.kotlin;

import io.strata.Strata;
import io.strata.StrataException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/** The annotated Kotlin class gets the same generated type and typed client as Java. */
class KotlinAnnotatedTest {
    static <T> T await(java.util.concurrent.CompletionStage<T> s) throws Exception { return s.toCompletableFuture().get(); }

    @Test
    void kotlinEntityIsGeneratedDiscoveredAndTyped() throws Exception {
        try (var strata = Strata.start(KotlinInteropTest.config())) {
            assertTrue(strata.types().contains(AccountEntity.TYPE));
            var acct = AccountClient.of(strata, "a1");
            assertEquals(100L, await(acct.deposit(100)));
            assertEquals(70L, await(acct.withdraw(30)));
            assertEquals(75L, await(acct.transferIn("bob", 5)));
            var e = assertThrows(ExecutionException.class, () -> await(acct.withdraw(1000)));
            assertEquals(StrataException.Kind.FAILED, ((StrataException) e.getCause()).kind());
            assertTrue(e.getCause().getMessage().contains("insufficient funds"));
            assertEquals(75L, await(acct.balance()));
            assertEquals(List.of("deposit 100", "withdraw 30", "from bob 5"), await(acct.history()));
        }
    }
}
