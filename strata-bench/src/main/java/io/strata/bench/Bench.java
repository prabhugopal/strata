package io.strata.bench;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import io.strata.Strata;
import io.strata.StrataRuntime;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Closed-loop benchmark: N callers each send a deposit to a random entity and wait for the
 * committed reply, repeatedly. Reports throughput and latency percentiles after a warm-up.
 */
public final class Bench {
    public static void main(String[] args) throws Exception {
        Config c = ConfigFactory.systemProperties().withFallback(ConfigFactory.parseResources("bench.conf")).resolve().getConfig("bench");
        String store = c.getString("store");
        int concurrency = c.getInt("concurrency"), entities = c.getInt("entities"), maxSamples = c.getInt("max-samples");
        Duration warmup = c.getDuration("warmup"), duration = c.getDuration("duration");

        Map<String, Object> overrides = store.equals("jdbc")
                ? Map.of("strata.store.kind", "jdbc", "strata.store.jdbc.url", "jdbc:h2:file:./bench-" + System.nanoTime())
                : Map.of("strata.store.kind", "memory");
        try (StrataRuntime strata = Strata.start(ConfigFactory.parseMap(overrides)
                .withFallback(ConfigFactory.parseMap(Map.of("strata.http.enabled", false, "strata.pekko.system.pekko.remote.artery.canonical.port", 0)))
                .withFallback(ConfigFactory.load()).resolve())) {
            run(strata, concurrency, entities, warmup, 0);
            long[] lat = new long[maxSamples];
            AtomicInteger n = new AtomicInteger();
            var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long cpu0 = os.getProcessCpuTime();
            long ops = run(strata, concurrency, entities, duration, 0, lat, n);
            long cpu = os.getProcessCpuTime() - cpu0;
            long[] sample = Arrays.copyOf(lat, Math.min(n.get(), lat.length));
            Arrays.sort(sample);
            System.out.printf("store=%s concurrency=%d entities=%d duration=%ss%n", store, concurrency, entities, duration.toSeconds());
            System.out.printf("throughput: %,.0f commands/s%n", ops / (duration.toNanos() / 1e9));
            // Less sensitive to other load on the machine than throughput: CPU this process spent per command.
            System.out.printf("cpu µs/command: %.1f%n", cpu / 1000.0 / Math.max(1, ops));
            System.out.printf("latency ms: p50 %.2f  p90 %.2f  p99 %.2f  p99.9 %.2f  max %.2f%n",
                    pct(sample, .50), pct(sample, .90), pct(sample, .99), pct(sample, .999), sample.length == 0 ? 0 : sample[sample.length - 1] / 1e6);
        }
    }

    private static long run(StrataRuntime s, int callers, int entities, Duration d, int unused) throws InterruptedException {
        return run(s, callers, entities, d, unused, null, null);
    }

    private static long run(StrataRuntime strata, int callers, int entities, Duration d, int unused, long[] lat, AtomicInteger n) throws InterruptedException {
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong ops = new AtomicLong();
        CountDownLatch done = new CountDownLatch(callers);
        for (int i = 0; i < callers; i++) {
            Thread.ofVirtual().start(() -> {
                var rnd = ThreadLocalRandom.current();
                try {
                    while (!stop.get()) {
                        var acct = AccountClient.of(strata, "a" + rnd.nextInt(entities));
                        long t0 = System.nanoTime();
                        acct.deposit(1).toCompletableFuture().join();
                        long t = System.nanoTime() - t0;
                        ops.incrementAndGet();
                        if (lat != null) { int k = n.getAndIncrement(); if (k < lat.length) lat[k] = t; }
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        Thread.sleep(d.toMillis());
        stop.set(true);
        done.await();
        return ops.get();
    }

    private static double pct(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.floor(p * sorted.length))] / 1e6;
    }
}
