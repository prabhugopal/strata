package io.strata.management;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * A small, dependency-free metrics registry rendered in the Prometheus text format. Counters and
 * histograms are keyed by name and label values; histogram buckets come from configuration.
 */
public final class Metrics {
    private final double[] bucketsSeconds;
    private final Map<String, String> help = new ConcurrentHashMap<>();
    private final Map<String, String> kinds = new ConcurrentHashMap<>();
    private final Map<Key, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<Key, Histogram> histograms = new ConcurrentHashMap<>();
    private final Map<String, Supplier<Map<List<String>, Double>>> gauges = new ConcurrentHashMap<>();
    private final Map<String, List<String>> gaugeLabels = new ConcurrentHashMap<>();

    private record Key(String name, List<String> labelNames, List<String> labelValues) {}

    public Metrics(List<Duration> buckets) {
        this.bucketsSeconds = buckets.stream().mapToDouble(d -> d.toNanos() / 1e9).sorted().toArray();
    }

    public void counter(String name, String help, List<String> labels, List<String> values, long delta) {
        register(name, help, "counter");
        counters.computeIfAbsent(new Key(name, labels, values), k -> new LongAdder()).add(delta);
    }

    public void observe(String name, String help, List<String> labels, List<String> values, long nanos) {
        register(name, help, "histogram");
        histograms.computeIfAbsent(new Key(name, labels, values), k -> new Histogram(bucketsSeconds.length)).observe(nanos / 1e9, bucketsSeconds);
    }

    /** A gauge read when metrics are rendered: label values → value. */
    public void gauge(String name, String help, List<String> labels, Supplier<Map<List<String>, Double>> read) {
        register(name, help, "gauge");
        gauges.put(name, read);
        gaugeLabels.put(name, labels);
    }

    private void register(String name, String text, String kind) {
        help.putIfAbsent(name, text);
        kinds.putIfAbsent(name, kind);
    }

    /** Prometheus text exposition format, version 0.0.4. */
    public String render() {
        StringBuilder out = new StringBuilder();
        for (String name : new TreeMap<>(kinds).keySet()) {
            String kind = kinds.get(name);
            out.append("# HELP ").append(name).append(' ').append(help.get(name)).append('\n')
               .append("# TYPE ").append(name).append(' ').append(kind).append('\n');
            switch (kind) {
                case "counter" -> counters.forEach((k, v) -> {
                    if (k.name().equals(name)) line(out, name, k.labelNames(), k.labelValues(), null, v.sum());
                });
                case "histogram" -> histograms.forEach((k, h) -> {
                    if (!k.name().equals(name)) return;
                    long cumulative = 0;
                    for (int i = 0; i < bucketsSeconds.length; i++) {
                        cumulative += h.buckets[i].sum();
                        line(out, name + "_bucket", k.labelNames(), k.labelValues(), format(bucketsSeconds[i]), cumulative);
                    }
                    line(out, name + "_bucket", k.labelNames(), k.labelValues(), "+Inf", h.count.sum());
                    line(out, name + "_sum", k.labelNames(), k.labelValues(), null, h.sumNanos.sum() / 1e9);
                    line(out, name + "_count", k.labelNames(), k.labelValues(), null, h.count.sum());
                });
                case "gauge" -> gauges.get(name).get().forEach((values, v) -> line(out, name, gaugeLabels.get(name), values, null, v));
                default -> { }
            }
        }
        return out.toString();
    }

    private static void line(StringBuilder out, String name, List<String> labels, List<String> values, String le, Number value) {
        out.append(name);
        if (!labels.isEmpty() || le != null) {
            out.append('{');
            for (int i = 0; i < labels.size(); i++) {
                if (i > 0) out.append(',');
                out.append(labels.get(i)).append("=\"").append(escape(values.get(i))).append('"');
            }
            if (le != null) out.append(labels.isEmpty() ? "" : ",").append("le=\"").append(le).append('"');
            out.append('}');
        }
        out.append(' ').append(value instanceof Double d ? format(d) : value.toString()).append('\n');
    }

    private static String format(double d) {
        return d == Math.rint(d) && !Double.isInfinite(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static String escape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static final class Histogram {
        final LongAdder[] buckets;
        final LongAdder count = new LongAdder();
        final LongAdder sumNanos = new LongAdder();

        Histogram(int n) {
            buckets = new LongAdder[n];
            for (int i = 0; i < n; i++) buckets[i] = new LongAdder();
        }

        void observe(double seconds, double[] bounds) {
            for (int i = 0; i < bounds.length; i++) {
                if (seconds <= bounds[i]) { buckets[i].increment(); break; }
            }
            count.increment();
            sumNanos.add((long) (seconds * 1e9));
        }
    }
}
