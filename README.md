# Strata

[![CI](https://github.com/prabhugopal/strata/actions/workflows/ci.yml/badge.svg)](https://github.com/prabhugopal/strata/actions/workflows/ci.yml)
[![Docs](https://github.com/prabhugopal/strata/actions/workflows/docs.yml/badge.svg)](https://prabhugopal.github.io/strata/)

**Durable, distributed entities for the JVM, on Apache Pekko.** Write plain Java, Kotlin, Scala or
Clojure; Strata keeps it running through crashes, restarts and deploys, with every effect made
exactly once. It's a library in your app, not a separate server.

```java
@Entity(mount = "/orders/{id}", expose = true)
public class Order {
    private Status status = Status.NEW;            // state: plain fields
    private long total;
    @Inject Payments payments;                     // the outside world: only through services
    @Inject Mailer mailer;
    @Inject Ctx ctx;                               // time, randomness, identity

    @Command public String pay(String card) throws Declined {
        String receipt = payments.charge(ctx.entityId(), total, card);   // made exactly once
        mailer.send("Paid, receipt " + receipt);                         // even if the node dies here
        status = Status.PAID;
        return receipt;
    }
}
```

From that class Strata:

- runs each order in exactly one place in a [Pekko](https://pekko.apache.org) cluster, one command
  at a time, and commits its state before every reply;
- records every service call as it happens, so if a node dies mid-command the next node finishes
  it, answering calls already made from the record: the card is charged once, the email sent once;
- checks at start that the class can't reach the outside world any other way (**the seal**), so
  nothing escapes the record;
- generates the HTTP routes, a typed client and the forms Stratum shows; no main class, no wiring.

## Try it

```sh
./gradlew :examples:shop:run --args="--strata.store.kind=memory"

curl -X POST localhost:8080/stock/tea/restock -d 10
curl -X POST localhost:8080/orders/a1/place -H 'content-type: application/json' -d '[{"sku":"tea","quantity":2}]'
curl -X POST localhost:8080/orders/a1/pay -H 'Idempotency-Key: k1' -d '"4242-4242"'
curl localhost:8080/orders/a1/summary        # "PAID: 1 lines, 900 cents, receipt R-…"
```

`examples/shop` uses every construct: entities calling entities with compensation, a durable
timer that expires unpaid orders, services found at start (`@Provides`), idempotent payments.

## Documentation

**[prabhugopal.github.io/strata](https://prabhugopal.github.io/strata/)**, built from [`site/`](site/)
(run it locally with `cd site && npm install && npm run dev`). It includes an interactive
walkthrough of a command surviving a crash.

| Start | How it works | Guides | Reference |
|---|---|---|---|
| [Introduction](https://prabhugopal.github.io/strata/start/introduction/) | [The seal](https://prabhugopal.github.io/strata/concepts/the-seal/) | [Services](https://prabhugopal.github.io/strata/guides/services/) | [Configuration](https://prabhugopal.github.io/strata/reference/configuration/) (generated) |
| [Quick start](https://prabhugopal.github.io/strata/start/quick-start/) | [Durable invocations](https://prabhugopal.github.io/strata/concepts/durable-invocations/) | [Kotlin, Scala, Clojure](https://prabhugopal.github.io/strata/guides/languages/) | [Annotations](https://prabhugopal.github.io/strata/reference/annotations/) |
| [The shop example](https://prabhugopal.github.io/strata/start/shop/) | [Timers and signals](https://prabhugopal.github.io/strata/concepts/timers/) | [Spring Boot](https://prabhugopal.github.io/strata/guides/spring/) | [Management API](https://prabhugopal.github.io/strata/reference/management-api/) |
| | [Upgrading code](https://prabhugopal.github.io/strata/concepts/upgrades/) | [Kubernetes](https://prabhugopal.github.io/strata/guides/kubernetes/) | [The strata CLI](https://prabhugopal.github.io/strata/reference/cli/) (generated) |

## What's in the repository

| Path | What |
|---|---|
| `strata-core` | The API (`@Entity`, `@Command`, `@Inject`, `Ctx`, `Entities`, `EntityType`), the seal, durable invocations, management API, settings. No Pekko, no Spring. |
| `strata-pekko` | The runtime: cluster sharding, one actor per entity, recording and resuming, timers. |
| `strata-store-jdbc` | State, records, idempotency keys and timers in PostgreSQL (or H2 for development). |
| `strata-processor` | Compile-time code generation: entity types, typed clients, service lists; annotation mistakes are compile errors. |
| `strata-http` | Entity routes and the management API on Pekko HTTP. |
| `strata-spring-boot-starter` | Optional: Spring Boot configuration, routes on WebFlux, services from beans. |
| `strata-clojure` | `defentity` for Clojure. |
| `strata-gradle-plugin`, `strata-bom` | `id("io.strata")`: dependencies, processor, JVM flags, container image, Kubernetes manifests and deploy. |
| `strata-bench` | Throughput and latency benchmark ([results](docs/benchmarks.md)). |
| `cli/` | The `strata` command (Rust) and `strata-client`, the Rust client Stratum (the desktop observer) uses. |
| `examples/` | `shop` (everything), `counter-pekko` and `counter-spring`, `services`, `kubernetes`, and one example per language. |
| `site/` | The documentation site (Astro Starlight). |
| `docs/` | The original architecture blueprint, and benchmark notes. |

## Building and testing

Strata runs on **Java 21** or newer. Versions live only in `gradle/libs.versions.toml`; build
settings in `gradle.properties`:

| Property | Meaning |
|---|---|
| `strata.java.release` | Lowest Java version Strata runs on (21) |
| `strata.java.toolchain` | The JDK that builds and tests; override per machine with `-Pstrata.java.toolchain=…` |
| `strata.jvm.args` | JVM flags Pekko needs on recent JDKs; applied to tests, examples and start scripts (pass them with `JAVA_TOOL_OPTIONS` elsewhere) |

```sh
./gradlew build                      # every module and example, with all tests
cd cli && cargo test                 # the CLI
./gradlew :strata-bench:run          # benchmark (-Pbench.store=jdbc, -Pbench.concurrency=…)
```

The JDBC store's contract tests run on H2 every time, and on PostgreSQL when
`STRATA_TEST_POSTGRES_URL` is set (`STRATA_TEST_POSTGRES_USER` / `_PASSWORD`).
[CI](.github/workflows/ci.yml) runs everything on Java 21 with a PostgreSQL service, the CLI's
tests, and the docs with a link check; [Docs](.github/workflows/docs.yml) publishes the site on
every push to `main`. If Gradle can't find a JDK installed by Homebrew, list it in
`~/.gradle/gradle.properties` (`org.gradle.java.installations.paths=…`).

## Status

An alpha: the core is built and tested, not yet run in production.

**Tested** (unit, integration, and end to end on real processes and minikube):

- One writer per entity: 200 concurrent increments end at 200; state survives `kill -9`.
- A node dies between two service calls: the next node finishes the command without repeating
  the first; a recorded exception replays so the same branch runs; `ctx.now()` replays.
- A transfer interrupted between two wallets changes each wallet exactly once.
- Idempotency keys return the first outcome, success or failure, in code and over HTTP.
- Timers fire by themselves, survive their node stopping, and with two nodes polling are each
  delivered once; a failed command leaves none.
- Upgrades: old state migrates on load, old code refuses newer state, a command resumed by changed
  code stalls safely (or is abandoned, if configured).
- Two-node clusters and a 2-pod minikube deployment: entities spread, a node or pod is deleted,
  every entity answers from the survivor with its state; the replacement rejoins.
- The seal refuses clocks, randomness, threads, I/O and reflection, with class, method and line.
- A 2-node cluster losing its lower-address node: under keep-majority the survivor stops itself;
  under lease-majority (a lease in the store) it keeps serving every entity. A removed node fails
  its liveness probe so Kubernetes restarts it.
- Node-to-node TLS with mutual authentication (keystores, or rotating PEM files from cert-manager):
  encrypted clusters form, and a node without TLS can't join.
- The JDBC store's contract on H2 and PostgreSQL 17: concurrent schema creation, fencing, record
  and commit in one transaction, timers claimed by two nodes without overlap.
- The same entities from Java, Kotlin, Scala 3 and Clojure; Spring Boot optional.

**Not done yet:**

- Load tests on dedicated hardware (laptop numbers are in [docs/benchmarks.md](docs/benchmarks.md)).
- Publishing artifacts to Maven Central (until then: `./gradlew publishAllPublicationsToLocalRepository`).
- Keeping each entity's full history, and operations on it (interrupt, cancel, revert).
- Long waits inside one command (`sleep` for days), Temporal-style; Strata uses timers and commands.
- Wasm entities (on [Endive](https://github.com/bytecodealliance/endive)).
