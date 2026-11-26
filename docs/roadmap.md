# Roadmap and ideas

What's next for Strata and Stratum, and ideas worth keeping. Each item says where it came from and
its state; move items to the README's status once they're built and tested.

## Next, in order

1. **`ctx.callKey()`: exactly-once at the edge.** A service call in flight when a node dies (the
   provider got it, the journal didn't record the result) runs again on resume, and today the
   service has no stable key to pass on, so the provider can't tell it's a repeat. Give each call a
   key that's the same on every replay (invocation id + call number) to pass as the provider's
   idempotency key. Correct the README's "the card is charged once" at the same time: true for
   recorded calls, not for the one in flight at the crash.
2. **Entity history: archive and flashback.** Keep the journal and earlier state versions instead
   of deleting them at commit, with a retention setting. Gives: each entity's history in Stratum,
   "what was this order at 10:42" (like Oracle's flashback query), and reverting an entity to an
   earlier version. Also: interrupt and cancel a running command.
3. **Messaging in and out (Kafka).** Events published reliably when a command commits (outbox),
   and commands driven from a topic with exactly-once processing. The biggest gap for
   microservices.
4. **Durable calls between Strata services.** One line, with the idempotency key derived
   automatically, like entity-to-entity calls inside one app.
5. **Projections.** Entity state into tables that can be queried ("list unpaid orders over €100").
6. **Observability exporters.** Optional modules: `strata-opentelemetry` (traces and metrics),
   `strata-micrometer` (Spring). Plus a setting to switch collection off, not only serving.

## Also on the list

- Load tests on dedicated hardware.
- Publishing to Maven Central.
- Stratum views for invocations, timers and leases.
- System views over the cluster, Oracle `V$`-style (e.g. stalled commands, journal size per
  type), shown live in Stratum.
- Wasm entities (on [Endive](https://github.com/bytecodealliance/endive)).
- Long waits inside one command (`sleep` for days), Temporal-style.
- Stratum: one connections file shared by all kinds (like `~/.kube/config`); today Golem
  connections are `golem` CLI profiles and Strata's live in Stratum's settings folder.

## Housekeeping

- Push the local commits (Strata and Stratum).
- Choose a LICENSE (Apache-2.0 suggested).
- Commit email: switch to a GitHub noreply address?
- Delete the `backup-before-rewrite` branch once it's no longer needed.
- Move to a GitHub organization later, then update the `prabhugopal/strata` references.

## Design notes

### Strata as a database for computation (the Oracle analogy)

The model behind Strata, mapped onto Oracle's recovery architecture:

| Oracle | Strata |
|---|---|
| A row in a table | An entity's state: one row in `strata_state`, keyed by type and id |
| Redo log | The journal (`strata_journal`): every service call's result, recorded as it happens |
| Commit | New state, journal and the reply's idempotency entry in one database transaction |
| Buffer cache | Entities in memory; passivation (`strata.pekko.passivation.idle-timeout`) is eviction |
| Per-row change number | Each entity's revision: writes from a node that no longer owns it are refused |
| Instance recovery | A crashed command resumes on another node and replays its journal |
| Archived logs, flashback | Not yet: item 2 above |
| `V$` views | The management API (`/strata/entities`, `/strata/events`, `/strata/cluster`) |

Where Strata differs from Oracle and from Golem: it writes the full state on every commit and
deletes a command's journal once it commits, so the journal is a redo log only for commands still
in flight. Recovery loads the last state and replays at most one unfinished command; it never
replays an entity's whole life, so recovery time doesn't grow with an entity's age. The cost is one
state write per command.

Where the analogy stops: Oracle commits atomically within the database; Strata's effects reach
outside systems that it can't roll back, which is why item 1 matters.

Avoid a global change number across all entities: per-entity order, ownership fencing and causal
ids scale; a global one needs coordination.

### Where Strata fits

Strong for services that keep something correct over time while calling other systems: orders,
checkout, payments; bookings and inventory; sagas across services; subscriptions, reminders,
SLAs; accounts and wallets. Not for plain CRUD and reporting, stateless request handling, or
high-volume streams. The realistic system mixes a few Strata services with ordinary ones.

The JVM gap: Akka moved to a source-available license (2022); Temporal and Restate are separate
servers; Axon is event-sourcing focused; Orleans is .NET. An open-source, library-only
durable-entities runtime on Pekko for Java, Kotlin, Scala and Clojure is the opening. The main
risk is maturity (alpha, no production users), more than any feature.

### Ideas to weigh separately (from a brainstorm, not decided)

- Entities composed from capabilities (memory, a model, tools, a budget, a goal), an
  entity-component model for agents.
- Temporal, Restate or Golem as optional backends behind Strata's API.
- Stratum as a place to simulate: inject failures, model scaling and storage growth before
  production.
- A benchmark suite against equivalent Temporal and Restate deployments before any performance
  claims.
