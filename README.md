# Strata

Durable, distributed entities for the JVM, on Apache Pekko. **Milestone 0**: sharded single-writer
entities with committed state, usable from Java, Kotlin, Scala and Clojure, with Spring Boot / WebFlux
support. The architecture and roadmap are in [`docs/`](docs/).

```java
EntityType<Long> counter = EntityType.builder("counter", Long.class)
    .initial(() -> 0L)
    .command("increment", s -> Reply.update(s + 1, s + 1))
    .command("add", Long.class, (s, n) -> Reply.update(s + n, s + n))
    .query("get", s -> s)
    .build();

try (StrataRuntime strata = Strata.start()) {          // config: reference.conf + application.conf
    strata.register(counter);
    strata.ref(counter, "alice").call("increment", Long.class);   // CompletionStage<Long>
}
```

Each entity id lives in one place in the cluster and applies commands one at a time. A persistent
entity commits its new state before replying, so a crash (even `kill -9`) loses nothing that was
acknowledged. Idle entities are stopped and reload on next use.

## Modules

| Module | What it is |
|---|---|
| `strata-core` | The API: `EntityType`, `Reply`, `StrataRuntime`, `EntityRef`, settings, and the runtime and store SPIs. No Pekko, no Spring. |
| `strata-pekko` | The runtime on Pekko: cluster sharding, single-writer entity actors, passivation, fencing of stale writers. |
| `strata-store-jdbc` | Committed state in PostgreSQL (or H2 for local development), with an optimistic revision per entity. |
| `strata-spring-boot-starter` | Spring Boot auto-configuration: `strata.*` in `application.yaml`, every `EntityType` bean registered, lifecycle tied to Spring. |
| `examples/counter-spring` | The counter behind Spring WebFlux. |
| `examples/interop-{kotlin,scala,clojure}` | The same counter written in each language, tested on the real runtime. |

## Languages

The core API is plain functions and builders, so every JVM language uses it directly. Annotations
(`@StrataEntity`) can be added later as Java/Kotlin convenience on top.

- **Kotlin**: `.command("increment") { s: Long -> Reply.update(s + 1, s + 1) }`
- **Scala 3**: `.command("increment", (s: java.lang.Long) => Reply.update(s + 1, s + 1))`
- **Clojure**: `(.command "increment" (reify NoArgHandler (handle [_ s] (Reply/update (inc s) (inc s)))))`

Arguments, results and state are JSON (Jackson), so they cross JVMs and languages; Jackson modules on
the classpath (Kotlin, Scala) are picked up automatically.

## Configuration

Nothing is hard-coded: every default lives in a module's `reference.conf`, and code reads settings
through typed settings objects. Override any key in `application.conf`, with `-Dstrata.…=…`, or (for
Spring apps) under `strata:` in `application.yaml`.

| Key | Default | Meaning |
|---|---|---|
| `strata.runtime.backend` (`STRATA_BACKEND`) | `pekko` | Which runtime starts |
| `strata.call-timeout` | `10s` | How long a caller waits for an entity |
| `strata.entity.default-durability` | `persistent` | `persistent` (committed before reply) or `ephemeral` |
| `strata.entity.passivate-after` | `2m` | Stop idle entities; they reload on next use |
| `strata.entity.max-id-length` | `1024` | Longest entity id; stores size their id column from it |
| `strata.store.kind` (`STRATA_STORE`) | `memory` | `memory` or `jdbc` |
| `strata.store.executor` | `virtual` | Blocking store I/O on virtual threads, or `fixed` |
| `strata.store.jdbc.url` (`STRATA_JDBC_URL`) | local H2 file | e.g. `jdbc:postgresql://db:5432/strata` |
| `strata.store.jdbc.table` | `strata_entity_state` | State table (created if `create-schema = true`) |
| `strata.store.jdbc.max-state-bytes` | `1048576` | State column size on databases that need one (H2) |
| `strata.pekko.cluster-mode` (`STRATA_CLUSTER_MODE`) | `single` | `single` or `seed-nodes` |
| `strata.pekko.number-of-shards` | `1024` | Fixed for the cluster's lifetime |
| `strata.pekko.system.pekko.*` | see file | Pekko settings for Strata's actor system (host/port via `STRATA_HOST`/`STRATA_PORT`) |

See each module's `src/main/resources/reference.conf` for every key and its explanation.

## Building and running

Build settings live in `gradle.properties`; dependency versions only in `gradle/libs.versions.toml`.

| Property | Meaning |
|---|---|
| `strata.java.release` | Java version Strata targets (25) |
| `strata.java.toolchain` | Installed JDK that builds and tests (must be ≥ release) |
| `strata.jvm.args` | JVM flags every Strata process needs (applied to tests, examples and start scripts) |

```sh
./gradlew build                                   # everything, including all tests
./gradlew :examples:counter-spring:run            # http://localhost:8080
curl -X POST localhost:8080/counters/alice/increment
curl localhost:8080/counters/alice
```

If Gradle can't find a JDK installed by Homebrew, list it in `~/.gradle/gradle.properties`
(`org.gradle.java.installations.paths=…`); that file is per machine, not part of the project.

**Production JVM flags.** Pekko's network layer needs the flags in `strata.jvm.args` on recent JDKs.
The example's start script includes them; elsewhere pass them via `JAVA_TOOL_OPTIONS` (e.g. in a
Kubernetes pod spec).

## What's verified

- 200 concurrent increments on one entity apply exactly once each.
- State survives passivation, a clean restart and `kill -9` of the server.
- A stale writer can't overwrite newer state (revision fencing in memory and JDBC stores).
- Handler errors and unknown commands reach the caller; state is unchanged.
- Bad configuration fails at startup.
- The same API works from Java, Kotlin, Scala 3 and Clojure.

## Next

M1 durable steps (`ctx.step`, journal and replay, `ctx.now/random`, crash injection) · M2 timers and
signals · M3 three-node cluster on Kubernetes (bootstrap, lease, rolling restarts) · M4 management API
and metrics for Stratum · M5 Spring AI agent. PostgreSQL integration tests need Docker (Testcontainers).
