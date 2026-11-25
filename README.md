# Strata

Durable, distributed entities for the JVM, on Apache Pekko. **Milestone 0**: sharded single-writer
entities with committed state, usable from Java, Kotlin, Scala and Clojure, with Spring Boot / WebFlux
support. The architecture and roadmap are in [`docs/`](docs/).

You write the business logic; Strata does the rest:

```java
@Entity(mount = "/counters/{id}")
public class Counter {
    private long count;                                   // fields are the state

    @Command(post = "/increment") public long increment() { return ++count; }
    @Command(post = "/add")       public long add(long n) { return count += n; }
    @Query(get = "")              public long count()     { return count; }
}
```

```java
StrataRuntime strata = Strata.start();                     // finds @Entity classes, serves their routes
CounterClient.of(strata, "alice").add(5);                  // generated, typed: CompletionStage<Long>
```

```sh
curl -X POST localhost:8080/counters/alice/increment       # 1
curl localhost:8080/counters/alice                         # 1
```

At compile time `strata-processor` generates `CounterEntity` (the entity type, found automatically
at start) and `CounterClient`. Annotation mistakes (a GET route with arguments, a missing mount, a
`final` state field…) are compile errors. If a command throws, the entity keeps its last
committed state. Generated class suffixes are processor options (`-Astrata.entitySuffix=…`,
`-Astrata.clientSuffix=…`).

The same entity with the builder API (no annotations, no code generation, for any JVM language):

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
| `strata-core` | The API: `@Entity`/`@Command`/`@Query`, `EntityType`, `Reply`, `StrataRuntime`, `EntityRef`, settings, the route table, and the runtime, store and extension SPIs. No Pekko, no Spring. |
| `strata-processor` | Annotation processor: generates the entity type, its discovery entry and a typed client. |
| `strata-pekko` | The runtime on Pekko: cluster sharding, single-writer entity actors, passivation, fencing of stale writers. |
| `strata-store-jdbc` | Committed state in PostgreSQL (or H2 for local development), with an optimistic revision per entity. |
| `strata-clojure` | `defentity` for Clojure, and loading entities from `strata.clojure.entities`. |
| `strata-http` | Serves entity routes with Pekko HTTP on its own port. No Spring. |
| `strata-spring-boot-starter` | Optional Spring Boot support: `strata.*` in `application.yaml`, `EntityType` beans registered, entity routes on Spring WebFlux, lifecycle tied to Spring. |
| `examples/counter-pekko` | The annotated counter on Pekko HTTP, no Spring. |
| `examples/counter-spring` | The same counter inside a Spring Boot WebFlux app. |
| `examples/interop-{kotlin,scala,clojure}` | An annotated (or `defentity`) bank account and a builder-API counter in each language, tested on the real runtime. |

## Languages

Every language writes only business logic, and all four produce the same entity type, wire
format and HTTP routes:

| Language | How | Typed client | Registered by |
|---|---|---|---|
| Java | `@Entity` class, `strata-processor` as annotation processor | generated | discovery (automatic) |
| Kotlin | the same `@Entity` class in Kotlin, processor through `kapt` | generated | discovery (automatic) |
| Scala 3 | the same `@Entity` class in Scala; built by reflection at start | `ref.call(...)` | `strata.entity.classes` |
| Clojure | `strata.core/defentity`: immutable map state, commands return `[state result]` | `ref.call(...)` | `strata.clojure.entities` |

```kotlin
@Entity(mount = "/accounts/{id}")
class Account {
    private var balance: Long = 0
    @Command(post = "/deposit") fun deposit(amount: Long): Long { balance += amount; return balance }
    @Query(get = "") fun balance(): Long = balance
}
```

```scala
@Entity(mount = "/accounts/{id}")
class Account:
  private var total: Long = 0L
  @Command(post = "/deposit") def deposit(amount: Long): Long = { total += amount; total }
  @Query(get = "") def balance(): Long = total
```

```clojure
(defentity account
  {:mount "/accounts/{id}" :initial {:balance 0}}
  (command deposit {:post "/deposit"} [state amount]
    (let [s (update state :balance + amount)] [s (:balance s)]))
  (query balance {:get ""} [state] (:balance state)))
```

Arguments: none, one (sent as the value itself, generic types included), or several (a JSON
object by parameter name, or an array in order). State fields can't be `final`/`val` because
Strata restores them on load. Scala collections work with `jackson-module-scala` on the
classpath. The builder API (`EntityType.builder`) remains for anything the annotations don't
cover. Spring is never required.

## Management API

Served on the same HTTP server as entity routes (strata-http or Spring), under
`strata.management.base-path` (`/strata`). The strata CLI and Stratum use it.

| Endpoint | What |
|---|---|
| `GET /strata/info` | Node, version, store, uptime, settings summary |
| `GET /strata/cluster` | Members, leader, entities per type per node |
| `GET /strata/types` | Entity types, commands with parameter/result schemas, routes |
| `GET /strata/types/{type}/entities?after=&limit=` | Stored and in-memory entities, paged by id |
| `GET /strata/types/{type}/entities/{id}` | State, revision, whether it's in memory |
| `POST /strata/types/{type}/entities/{id}/{command}` | Run a command (JSON in, JSON out) |
| `GET /strata/events?after=&type=&id=` | Recent events on this node (activated, command, failed, stopped…) |
| `GET /strata/metrics` | Prometheus: commands by outcome, latency histograms, commits, live entities |

Set `strata.management.token` (`STRATA_MANAGEMENT_TOKEN`) to require a bearer token, and
`allow-invoke = false` for read-only. Limits, event capacity, histogram buckets and schema depth
are all settings.

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
| `strata.entity.discover` | `true` | Register every generated (`@Entity` + processor) type on the classpath at start |
| `strata.entity.classes` | `[]` | More entity classes to register, e.g. Scala `@Entity` classes (built by reflection) |
| `strata.clojure.entities` | `[]` | Clojure entities, as `namespace/var` (strata-clojure) |
| `strata.http.enabled` (`STRATA_HTTP_ENABLED`) | `true` | Serve entity routes (strata-http or Spring WebFlux) |
| `strata.http.base-path` | `""` | Prefix for every route, e.g. `/api` |
| `strata.http.max-body-bytes` | `1048576` | Largest request body |
| `strata.http.host` / `port` (`STRATA_HTTP_HOST`, `PORT`, `STRATA_HTTP_PORT`) | `0.0.0.0` / `8080` | strata-http's listen address |
| `strata.codec.field-visibility` | `any` | Which fields are stored without getters |
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
| `strata.java.release` | Lowest Java version Strata runs on (21, the LTS most teams run; it needs virtual threads). Apps can run it on any newer JDK. |
| `strata.java.toolchain` | Installed JDK that builds and tests (must be ≥ release); override per machine with `-Pstrata.java.toolchain=…` or `~/.gradle/gradle.properties` |
| `strata.jvm.args` | JVM flags every Strata process needs (applied to tests, examples and start scripts) |

```sh
./gradlew build                                   # everything, including all tests
./gradlew :examples:counter-pekko:run             # http://localhost:8080, no Spring
./gradlew :examples:counter-spring:run            # the same, inside Spring Boot
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
- Handler errors and unknown commands reach the caller; state is unchanged, even when an annotated
  entity changed its fields before throwing.
- Errors carry a kind that HTTP maps to a status: bad argument 400, unknown route 404, wrong
  method 405, conflict 409, handler failure 422, unavailable 503.
- Annotated entities: several parameters, generic parameters and results, void commands,
  queries with arguments, discovery on and off, state across restarts.
- The management API end to end over HTTP: describe, list, read, run, events, cluster, metrics,
  token and read-only mode.
- Annotation mistakes fail compilation with a message on the offending element.
- Bad configuration fails at startup.
- The same API works from Java, Kotlin, Scala 3 and Clojure.

## Next

Stratum connection · Gradle plugin and strata CLI · M1 durable steps (`ctx.step`, journal and replay, `ctx.now/random`, crash injection) · M2 timers and
signals · M3 three-node cluster on Kubernetes (bootstrap, lease, rolling restarts) · M4 management API
and metrics for Stratum · M5 Spring AI agent. PostgreSQL integration tests need Docker (Testcontainers).
