# Benchmarks

`./gradlew :strata-bench:run [-Pbench.store=jdbc] [-Pbench.concurrency=…]` (settings in
`strata-bench/src/main/resources/bench.conf`). Closed loop: each caller sends a deposit to a random
entity and waits for the committed reply. One node; laptop numbers, for comparing changes, not
for capacity planning.

| Date | Change | Store | Throughput | p50 | p99 |
|---|---|---|---|---|---|
| 2026-11-25 | baseline (management API, pooled JDBC) | memory | 132,700/s | 0.24 ms | 1.70 ms |
| 2026-11-25 | baseline | jdbc (H2 file) | 32,400/s | 1.53 ms | 9.57 ms |
| 2026-11-25 | services (`@Inject`, filled on every load and copy) | memory | 143,600/s | 0.25 ms | 1.65 ms |
| 2026-11-25 | services | jdbc (H2 file) | 36,300/s | 1.40 ms | 8.31 ms |

Run-to-run variation on this laptop is about ±10%; the services change is within it (no regression).

Machine: Intel i7-8850H (6 cores), 32 GB, JDK 27, 64 callers, 1,000 entities, 15 s after 5 s warm-up.

## Durable invocations (actor rewrite), A/B

Throughput on this laptop swung from 64k to 165k commands/s for the *same* build while other
work ran (Stratum, minikube), so the comparison uses CPU time per command (also printed by the
benchmark), alternating the build before the change (4c7fecf) and after, 3 runs each, 10 s:

| Store | before (µs CPU/command) | after |
|---|---|---|
| memory | 94.5, 101.4, 119.1 (avg 105) | 119.7, 105.5, 107.8 (avg 111) |
| jdbc | 399.3, 405.0, 367.2 (avg 390) | 388.5, 427.2, 442.1 (avg 419) |

Within the run-to-run spread: no clear regression for commands that don't use services (the
benchmark's). Commands of entities with services pay an extra write per invocation and per
service call by design. A first version locked the whole in-memory store on every commit
(about 20% slower); plain commands take the lock-free path again.
