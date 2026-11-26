# Roadmap and ideas

What's next for Strata and Stratum, and ideas worth keeping. Each item says where it came from and
its state; move items to the README's status once they're built and tested.

## How an idea gets in

Strata stays simple: business code is plain classes, everything else is configuration. An idea
is added only if all of these hold:

1. **It solves a problem users have now**, not one that might come later.
2. **Code that doesn't use it doesn't change**: no new required annotation, setting, dependency
   or step; it costs nothing when unused.
3. **It's optional and off by default**, or on by default only when it changes nothing visible.
4. **It reuses what's there** (Pekko, the store, the journal, the plugin) before adding a part.
5. **It's configurable, not hard-coded**, and listed in the generated configuration reference.
6. **It's explained in a sentence**: if it needs a new concept to understand, it waits.

Ideas that don't pass stay under "Ideas to weigh separately", not in the plan.

## Done

- **`Strata.callKey()`** (2026-11-26): a service passes it to the provider as its idempotency key;
  the same on every retry of that call, so a call in flight at a crash isn't applied twice. The
  README's "charged once" claim corrected. (Planned as `ctx.callKey()`; it belongs to the service,
  not the entity, so entity code doesn't change.)
- **One idle setting** (2026-11-26): `strata.entity.passivate-after` (`STRATA_PASSIVATE_AFTER`);
  Pekko's own idle passivation off. A duplicate `strata.pekko.passivation.idle-timeout` was added
  and removed the same day.

- **Ready to publish** (2026-11-26): Apache-2.0 license; coordinates `io.github.strata-jvm`
  (Maven Central only accepts a namespace you own) and plugin id `io.github.strata-jvm.strata`;
  sources, javadoc and Central's POM fields on every module; signing from secrets;
  `scripts/check-release.py` checks a bundle against Central's rules; `release.yml` publishes on a
  `v*` tag (or dry-runs from the Actions tab). The first release waits on the accounts below.

## Next, in order

1. **Entity history: archive and flashback.** Keep the journal and earlier state versions instead
   of deleting them at commit, with a retention setting. Gives: each entity's history in Stratum,
   "what was this order at 10:42" (like Oracle's flashback query), and reverting an entity to an
   earlier version. Also: interrupt and cancel a running command.
2. **Messaging in and out (Kafka).** Events published reliably when a command commits (outbox),
   and commands driven from a topic with exactly-once processing. The biggest gap for
   microservices.
3. **Durable calls between Strata services.** One line, with the idempotency key derived
   automatically, like entity-to-entity calls inside one app.
4. **Queries: indexed fields, then projections.** "List unpaid orders over €100" can't be asked
   today: state is one serialized blob per row. Design (see the notes below): fields marked
   `@Indexed` are written to real columns in the same transaction as the state, so queries
   (`strata.query(Order.class).where(...)`) are always current; projections into separate tables
   for reports and joins.
5. **Observability exporters.** Optional modules: `strata-opentelemetry` (traces and metrics),
   `strata-micrometer` (Spring). Plus a setting to switch collection off, not only serving.

## Also on the list

- **A Jepsen test suite** (in its own `jepsen/` folder; nothing in the runtime changes). Jepsen
  (Clojure) runs a cluster on 5 nodes, injects faults and checks the recorded history for
  consistency violations. Workloads: a register per entity (one writer per entity should make each
  entity linearizable), bank transfers between wallets (the total never changes), and payments
  with `Strata.callKey()` (no charge twice). Faults: partitions, killed and frozen (`SIGSTOP`)
  nodes, clock skew (leases use the database's clock). Needs Linux nodes (Docker or VMs), more
  than this laptop has; CI or a cloud VM. PostgreSQL itself stays one node: Jepsen tests Strata's
  claims on top of it, not the database.

- Load tests on dedicated hardware.
- Stratum views for invocations, timers and leases.
- System views over the cluster, Oracle `V$`-style (e.g. stalled commands, journal size per
  type), shown live in Stratum.
- Wasm entities (on [Endive](https://github.com/bytecodealliance/endive)).
- Long waits inside one command (`sleep` for days), Temporal-style.
- Stratum: one connections file shared by all kinds (like `~/.kube/config`); today Golem
  connections are `golem` CLI profiles and Strata's live in Stratum's settings folder.

## Housekeeping

- Both repos are in the `strata-jvm` organization since 2026-11-26: `strata-jvm/strata`
  (public; docs at https://strata-jvm.github.io/strata/) and `strata-jvm/stratum` (private,
  branches `main` and `strata`).
- First release, waiting on accounts only you can create, then their secrets in the
  organization (names in `.github/workflows/release.yml`): a Sonatype Central account with the
  `io.github.strata-jvm` namespace verified, a Gradle Plugin Portal account, and a signing key
  (publish its public half to keys.openpgp.org). Run the release workflow as a dry run first.
- Commit email: switch to a GitHub noreply address?
- Delete the `backup-before-rewrite` branch once it's no longer needed.

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
outside systems that it can't roll back, which is why `Strata.callKey()` exists.

Avoid a global change number across all entities: per-entity order, ownership fencing and causal
ids scale; a global one needs coordination.

### Querying entities: grouping entities, indexes, projections

| Need | Use |
|---|---|
| "This customer's orders", "this cart's items" | A grouping entity: the owner keeps the ids. Fine when the group is bounded and has one owner |
| "Unpaid orders over €100, newest first", admin screens | `@Indexed` fields + `strata.query`, always current |
| Reports, joins across types, analytics | A projection into its own table, or export to a warehouse |

A grouping entity for a global list (one `UnpaidOrders` entity) is a hot spot: one writer for
the whole system, state with no ceiling (written whole on every commit), and filters, sorting
and paging written by hand. Sharding it reinvents a database index.

`@Indexed` is Oracle's secondary index: Strata writes the indexed values into columns in the
same transaction as the state, so the index is never stale. Once a command has replied, a query
sees it, unlike event-sourced read sides (Akka, Axon) that lag. Queries go to the database, not
to nodes. Indexed fields are declared on the class; the store creates the columns and indexes;
changes go through state versions (`@Migrate`).

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
