package io.strata.durability;

import io.strata.Strata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CallKeyTest {
    static final io.strata.Codec CODEC = new io.strata.Codec(io.strata.StrataSettings.load());

    @Test
    void aCallRetriedAfterACrashGetsTheSameKey() throws Throwable {
        List<String> seen = new ArrayList<>();
        Journal.Real charge = n -> { seen.add(Strata.callKey().orElseThrow()); return "R-1"; };

        // First attempt: the call reaches the provider, then the node dies before the result is recorded.
        Journal first = new Journal("inv-1", List.of(), (seq, entry) -> { throw new IllegalStateException("node died"); }, CODEC);
        assertThrows(JournalWriteException.class, () -> first.call("Payments.charge", String.class, charge));

        // Resumed elsewhere: nothing was recorded, so the call runs again, with the same key.
        List<byte[]> recorded = new ArrayList<>();
        Journal resumed = new Journal("inv-1", List.of(), (seq, entry) -> recorded.add(entry), CODEC);
        assertEquals("R-1", resumed.call("Payments.charge", String.class, charge));
        assertEquals(List.of("inv-1/1", "inv-1/1"), seen);

        // Once recorded, a replay answers from the journal and doesn't call the provider at all.
        Journal replay = new Journal("inv-1", recorded, (seq, entry) -> fail("nothing new to record"), CODEC);
        assertEquals("R-1", replay.call("Payments.charge", String.class, charge));
        assertEquals(2, seen.size());
    }

    @Test
    void eachCallHasItsOwnKeyAndThereIsNoneOutsideACall() throws Throwable {
        Journal j = new Journal("inv-2", List.of(), (seq, entry) -> {}, CODEC);
        Journal.Real key = n -> Strata.callKey().orElseThrow();
        assertEquals("inv-2/1", j.call("A.a", String.class, key));
        assertEquals("inv-2/2", j.call("B.b", String.class, key));
        assertTrue(Strata.callKey().isEmpty(), "cleared after the call");
    }
}
