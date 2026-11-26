package io.strata.seal;

import io.strata.annotation.Command;
import io.strata.annotation.Entity;
import io.strata.annotation.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Entities the seal accepts and refuses. */
final class Samples {
    private Samples() {}

    interface Payments { String charge(String who, long cents); }

    record Line(String item, long cents) {}

    /** Pure logic: collections, streams, records, lambdas, string building, BigDecimal, java.time values. */
    @Entity
    static class Clean {
        private List<Line> lines = new ArrayList<>();
        private Map<String, Long> totals = new TreeMap<>();
        @Inject Payments payments;

        @Command
        public String checkout(String who) {
            long sum = lines.stream().mapToLong(Line::cents).sum();
            totals.merge(who, sum, Long::sum);
            String summary = lines.stream().map(l -> l.item() + "=" + l.cents()).collect(Collectors.joining(","));
            var due = java.time.LocalDate.of(2026, 1, 1).plusDays(30);
            return payments.charge(who, sum) + " " + summary + " due " + due + " " + new java.math.BigDecimal(sum).movePointLeft(2);
        }
    }

    @Entity
    static class UsesClock {
        long at;
        @Command public long stamp() { return at = System.currentTimeMillis(); }
    }

    @Entity
    static class UsesNow {
        String at;
        @Command public String stamp() { return at = java.time.Instant.now().toString(); }
    }

    @Entity
    static class UsesRandom {
        long n;
        @Command public long roll() { return n = new java.util.Random().nextLong(); }
    }

    @Entity
    static class CallsHttp {
        @Command public int ping() throws Exception {
            return java.net.http.HttpClient.newHttpClient()
                    .send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://x")).build(), java.net.http.HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        }
    }

    @Entity
    static class Sleeps {
        @Command public void nap() throws InterruptedException { Thread.sleep(10); }
    }

    static long shared;

    @Entity
    static class SharesState {
        @Command public long bump() { return ++shared; }
    }

    @Entity
    static class Reflects {
        @Command public Object make() throws Exception { return Class.forName("java.io.File").getDeclaredConstructor(String.class).newInstance("/etc"); }
    }

    /** A helper of yours that writes files: reached through the entity, so it's checked too. */
    static final class Audit {
        static void write(String s) throws java.io.IOException { java.nio.file.Files.writeString(java.nio.file.Path.of("/tmp/audit"), s); }
    }

    @Entity
    static class UsesHelper {
        @Command public void note(String s) throws Exception { Audit.write(s); }
    }

    @Entity
    static class MethodReference {
        @Command public long later() { java.util.function.LongSupplier clock = System::nanoTime; return clock.getAsLong(); }
    }
}
