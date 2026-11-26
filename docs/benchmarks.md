# Benchmarks

`./gradlew :strata-bench:run [-Pbench.store=jdbc] [-Pbench.concurrency=…]` (settings in
`strata-bench/src/main/resources/bench.conf`). Closed loop: each caller sends a deposit to a random
entity and waits for the committed reply. One node; laptop numbers, for comparing changes, not
for capacity planning.

| Date | Change | Store | Throughput | p50 | p99 |
|---|---|---|---|---|---|
| 2026-11-25 | baseline (management API, pooled JDBC) | memory | 132,700/s | 0.24 ms | 1.70 ms |
| 2026-11-25 | baseline | jdbc (H2 file) | 32,400/s | 1.53 ms | 9.57 ms |

Machine: Intel i7-8850H (6 cores), 32 GB, JDK 27, 64 callers, 1,000 entities, 15 s after 5 s warm-up.
