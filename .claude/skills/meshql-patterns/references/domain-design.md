# Domain Design: Events, Projections, and Workers

This is the methodology meshql is built for. The immutable Envelope, temporal reads, and per-entity mesh aren't incidental features — they exist so that a system can be modeled as **events that happened** plus **domain models derived from those events**. If you internalize one pattern from this skill, make it this one.

## The verbs and the nouns

There are exactly two kinds of thing, and the whole methodology is the bridge between them. `examples/egg-economy/` is the worked reference — a fully event-sourced system with 13 entities split across three MongoDB URIs (`ACTORS_MONGO_URI`, `EVENTS_MONGO_URI`, `PROJECTIONS_MONGO_URI` in `Main.java`) so the three kinds of store can even be scaled and operated independently.

| Kind | What it is | Written by | Examples (`examples/egg-economy`) |
|---|---|---|---|
| **Business event** (verb) | An immutable fact that happened | **Front ends** — the *only* write surface | `lay_report`, `storage_deposit`, `storage_withdrawal`, `container_transfer`, `consumption_report` |
| **Domain model** (noun) | A model derived by folding events | **Workers only** — never written directly | `farm`, `coop`, `hen`, `container`, `consumer` (actor-nouns); `hen_productivity`, `container_inventory`, `farm_output` (analytic-nouns) |

The key realization: **you keep both, and workers translate verbs into nouns.** Notice even the actor-nouns (`farm`, `hen`) live in their own mesh — in the shipped example they happen to be seeded directly rather than derived from a `build_farm`-style event, but the analytic-nouns (`hen_productivity`, `farm_output`, `container_inventory`) are strictly worker-derived, never `POST`ed by any app code. When you introduce a new business capability, prefer modeling it as an event + a worker-derived projection over a directly-written noun — that is what keeps the write surface honest.

## The design workflow

1. **Identify the business events first.** What *happens* in the domain? Eggs are laid, stored, transferred, consumed. Name them as facts. These are the primary writes.
2. **Model the nouns.** What does the application query — both entities (`hen`, `container`) and analytics ("how productive is this hen?", "what's in this container?"). Each analytic noun is a domain model built from events.
3. **Create event meshes** (standard wiring, `adding-an-entity.md`). Create-only from the front end's perspective; typically no resolvers needed on an event entity itself.
4. **Create noun meshes.** Same wiring, but nothing writes them through the public API — workers write them via their `Repository`, or via the REST API using worker/service credentials.
5. **Build one worker per noun.** Each worker consumes the events it needs, folds them into its noun, and writes it. One worker owns one noun so nouns can be added, rebuilt, and scaled independently.

## Run an actual event storm — don't skip straight to code

Steps 1–2 above are usually done badly when done silently in your head. **Event storming** (Alberto Brandolini's technique — sticky notes on a wall, or the written equivalent here) is a concrete process for doing them properly, and its categories map almost one-to-one onto meshql's own concepts. Produce this as a real, written artifact (a markdown doc is fine) *before* wiring anything — not a mental pass you skip straight through:

| Event storm artifact | meshql concept |
|---|---|
| **Domain Events** (past tense: "Order Placed", "Hens Bought") | Event-mesh entities — the verbs. Each becomes a create-only restlette. |
| **Commands** (imperative: "Place Order", "Buy Hens") | The REST payload shape of a `POST` to the corresponding event restlette. |
| **Actors / Roles** (who issues a command — a customer, an editor, a system) | Casbin roles (`CasbinAuth.create(model, policy, jwtAuth)`, see "Auth beyond NoAuth" in `SKILL.md`'s decision guide) — who's authorized to write which event. Don't default to `NoAuth` once you've named actors; wire the roles you just identified. |
| **Policies** ("whenever X happens, then Y should happen") | **Workers.** A policy *is* a worker's fold-and-react loop — this is the most direct mapping in the table. A multi-step policy (event → triggers a new command → new event) is a worker that, after folding, also issues a follow-up write via REST. |
| **Aggregates** (the consistency boundary around a command) | The event mesh's write-side boundary — what one event commits atomically, and what a restlette's validators should actually check. |
| **Read Models / Views** (what someone needs to see) | Domain/noun meshes — the nouns from step 2 above, one worker each. |
| **Hotspots** (open questions, conflicting opinions, "we're not sure yet") | Write them down explicitly in the design artifact and flag them for the human. Don't silently resolve a hotspot with a guess. |

**Ground the storm in reality, not a blank page.** If an established product already exists in the same problem space, look at how it actually models the domain (its real entities, its real event/state lifecycle) before inventing your own from scratch — a green-field brainstorm reliably misses events and edge cases that someone else already discovered the hard way. Adapt the domain shape, not the code.

Once the storm is done, steps 3–5 above are close to mechanical: Domain Events → event meshes, Read Models → noun meshes, Policies → workers.

## The write-path rule (the crux)

**Front ends write business events. They should not write analytic-noun domain models.**

- The public write surface for event data is the **event** restlettes (`POST /lay_report/api`, `POST /storage_deposit/api`, …).
- Analytic-noun meshes (`hen_productivity`, `farm_output`, `container_inventory`) are read-only to the outside world in intent: their `/api` write routes should be closed by **infrastructure** (network policy: only worker/service principals reach them, enforceable via `Auth`/`CasbinAuth` role checks) and **convention** (no application code `POST`s a noun). Workers write nouns through their `Repository`, out of band, or via the noun's REST API using a worker-only token that `CasbinAuth` authorizes for write but the front end's token does not.
- The application reads nouns via their **graphlettes** (`/hen/graph`, `/hen_productivity/graph`), never computing them on the read path.

A front end that can only reach event restlettes (enforced by auth/network policy) cannot put the system into an inconsistent state because it cannot express a noun write at all — only "this happened."

## The event → log bridge is storage-layer CDC, never application code

Workers consume events from a change feed — in the shipped examples, Debezium tailing MongoDB or PostgreSQL, publishing to Kafka. Getting events *onto* that feed is the one part people get wrong. **It is change-data-capture from the storage layer — not a hook in the write path.**

- The front end does exactly one write: `POST` the event, which commits to the event mesh's store. Done.
- The store's change feed (Debezium against MongoDB or PostgreSQL WAL, per `meshql/CLAUDE.md`'s "CDC Pipeline Patterns") mirrors each *committed* write onto a Kafka topic. This is configured infrastructure (`docker-compose.yml`'s `debezium/server:2.6`, `snapshot.mode=initial`). **No restlette is touched; there is no application-level publish-after-write.**

Why this and not an application-level publish after the write:

- **No dual write.** A "write the DB, then publish the event" hook is two writes with no shared transaction. Crash in between and the row exists but the event never fired — the log is now a lie, and every projection built from it is silently wrong. CDC derives the event *from the committed write*, so the event cannot be lost relative to the data. At-least-once delivery after commit is the datastore/CDC layer's responsibility, not the mesh's.
- **Correct order and time.** Debezium appends committed writes in commit order, carrying the store's timestamp, so replay and temporal reads reflect what actually happened rather than application wall-clock during a request. Note what the bridge's job *is*: appending. The authority on order is the Kafka topic itself — see "The queue is the ordering authority" below — so nothing here needs to sort, buffer, or reconcile before publishing.

Because folds are (or should be) deterministic and idempotent, at-least-once delivery is enough: replaying a duplicate event recomputes the same noun.

**Be precise about what "idempotent" means here — the over-reading is dangerous.** A fold is idempotent over a *whole log*: replay the log from an empty projection twice and you get the same noun. That is NOT the same as idempotent *per event*, and most useful folds are not — an accumulator like `messageCount + 1` recomputes correctly from zero and inflates if handed the same event twice against state that already includes it.

A worker that re-reads the log from the beginning every tick never exercises the distinction, because every tick resets and re-folds. **It becomes live the moment a worker consumes incrementally** (committed consumer offsets, a Kafka consumer group resuming where it left off), because at-least-once delivery then means a redelivered batch lands on state that may already include it.

The fix is not to make every fold per-event idempotent — often impossible without storing seen-ids forever. It is to treat **the consumed offset and the fold state as one atomic artifact**: checkpoint them together, restore them together, and commit the offset *after* the projection write. A redelivered batch then lands on the state that predates it and recomputes rather than accumulates. A worker with nowhere durable to keep fold state must also keep no offset — committing a position for in-memory state means restarting at a non-zero offset with an empty projector, which silently produces a wrong noun instead of failing.

Note the checkpoint is generally **not** derivable from the published projection: a projector usually holds state it deliberately never publishes (a pending-request set, an explicitly-granted role, a live-session set). Rehydrating from the projection alone loses exactly that.


**If an event meshlette's primary store is `merkql` (via the Rust connector+worker sidecar — see `merkql-architecture`), there's no CDC bridge to build for that entity at all.** merkql is itself a log; a restlette write against it already is the log append, not something a Debezium-style bridge needs to derive afterward. The Debezium/Kafka pattern above is for entities whose primary store is a real database (Mongo, Postgres) that isn't itself a log. Don't assume merkql needs the same bridging machinery — check whether `meshql-merkql`'s direct `Repository`/`Searcher` is available in the Rust sidecar before concluding merkql can't be used for a given event entity.

## The queue is the ordering authority

**Ordering is provided implicitly. You do not have to build it.** Events arrive at N event meshlettes; the Kafka topics provide the ultimate ordering; workers receive those events in a defined order and are presented with a predictable domain. **Nothing needs to arrive pre-sorted — the append to the topic is what defines the order.**

This is the single most reliably reinvented part of the platform. Do not build machinery to reconstruct a total order before publishing. Two separate builds recently did exactly that:

- one derived a cursor from a storage engine's physical row id (`_id INTEGER PRIMARY KEY`) and pinned itself to SQLite to get it — see `references/storage-adapters.md`, "Don't reach underneath the abstraction";
- the other funnelled every entity through a **single-partition** topic sorted by `(created_at, table, _id)`, with a parking buffer to hold events that arrived out of that order.

Both solved a problem Kafka had already solved, and each paid for it: the first with engine portability, the second with throughput plus a buffer that is now a piece of stateful infrastructure someone has to reason about on restart.

**Note what was actually wrong in the second case, because the next section recommends single-partition topics.** The fault was not the partition count. It was funnelling *every entity* through *one shared* topic and then writing a `Comparator` and a parking buffer to undo the resulting interleaving. One partition per topic with **one topic per event meshlette** — the next section's recommendation — is the opposite arrangement: no shared funnel, no cross-entity interleaving to undo, and therefore no re-sequencing code. The rule that survives is "write no ordering code above the queue," not "avoid single-partition topics."

If you find yourself writing a `Comparator`, a re-sequencer, or a hold-back buffer on the produce side, stop: the correct amount of ordering code above the queue is none. (The **phased processing** pattern below is a different thing and remains correct — it resolves *foreign keys* across entities within one run, not a total order over events. Ordering the phases is a dependency decision; ordering events inside a topic is not yours to make.)

## Ordering versus throughput versus durability is queue configuration — and it belongs to the domain

meshql deliberately does not choose between them for you. It **cannot**: it does not know what matters about your domain, and there is no defensible default.

The general principle holds everywhere: ordering, throughput and durability trade against each other, and which one wins is a fact about your domain, not about the framework. **What differs is which lever you actually have.** On both of meshql's event-log paths — the `ksql` adapter and the Debezium CDC bridge — **the Kafka message key is not yours to choose per aggregate.** The lever is topic granularity. Read the next subsection before setting a partition count.

### Partitioning by aggregate id is not expressible on either Java event-log path

**Path 1 — the `ksql` adapter.** The producer key is hardcoded to the Envelope id at all three write sites in `repos/ksql/src/main/java/com/meshql/repositories/ksql/KsqlRepository.java` — `:109` (`create`), `:205` (`remove`'s tombstone append), `:226` (`createMany`):

```java
producer.send(new ProducerRecord<>(topic, id, json)).get();
```

`KsqlConfig` is the whole config surface (`repos/ksql/src/main/java/com/meshql/repositories/ksql/KsqlConfig.java:6-12`) and has no key field, no selector, and no `Partitioner` class setting:

```java
public String bootstrapServers;
public String ksqlDbUrl;
public String topic;
public int partitions = 3;
public int replicationFactor = 1;
public boolean autoCreate = true;
public String acks = "all";
```

**Note `partitions = 3` is the default.** Partition *count* is configurable and applied automatically (`KsqlPlugin.java:49-50` → `KsqlRepository.initialize` → the DDL's `PARTITIONS=%d` at `KsqlRepository.java:63`), while the *key* is not. Envelope ids are unique per record, so **out of the box a ksql-backed meshlette scatters one aggregate's events across three partitions with no ordering between them.** A builder who follows the usual advice — raise the count, expect per-aggregate order — makes it worse, not better. This is a trap, not a gap.

The hardcoding is also load-bearing, so do not "fix" it locally: the stream DDL declares `id VARCHAR KEY` (`KsqlRepository.java:57-63`), the materialized table is `GROUP BY id` (`:66-74`), and temporal reads filter on `record.key()` (`:300-308`).

**Path 2 — the Debezium CDC bridge.** For a meshlette whose primary store is Postgres or Mongo, the bridge is Debezium (see "The event → log bridge" above), and Debezium's message key is derived from the **table primary key**. For meshql's Postgres envelope table that is `pk`, a surrogate generated *per version* (`repos/postgres/src/main/resources/sql/create/2.sql`):

```sql
pk UUID DEFAULT uuid_generate_v4() PRIMARY KEY,
id TEXT,
payload JSONB,
```

So the default key is not merely per-envelope but **per-version** — even two versions of the same envelope land on different partitions. `message.key.columns` can redirect the key to a *column* (`id` buys you per-envelope grouping), but the **aggregate id lives inside the `payload` JSONB**, not in a column, so aggregate-id partitioning is not reachable without writing an SMT — which is application code in the bridge, exactly what "the bridge is configured infrastructure, never code" rules out.

**What to do instead: one partition per topic, one topic per event meshlette.**

- Set `KsqlConfig.partitions = 1` (do **not** leave the default of 3), and provision Debezium-fed topics with one partition. This gives **total order within each topic** — a stronger guarantee than per-aggregate ordering, not a weaker one. A worker sees every event in its meshlette in append order, with no re-sequencing and no per-key reasoning.
- Parallelism comes from **topic count**: N event meshlettes are N topics consumed by N independent workers.

State the costs plainly in the architecture document:

- **Single-topic write throughput is the ceiling** for any one meshlette. A high-volume event type cannot be scaled by adding partitions; the only lever is splitting it into more than one meshlette — a domain modelling decision, not a config change.
- **There is no cross-topic ordering.** Events in different meshlettes have no defined relative order.
- Consumer parallelism within a meshlette is capped at one — a single-partition topic cannot be shared across a consumer group.

Cross-topic causality is *not* solved by partitioning — it is handled by the rejection mechanism in the next section: a worker that sees an event referencing something it hasn't observed yet emits a rejection, and that rejection **withdraws itself** when the referent arrives. That is the designed answer to "these two events are in different topics and arrived in the wrong order." Do not reach for a partition scheme, a re-sequencer, or a hold-back buffer.

**Known upstream gap.** The honest fix on path 1 is a partition-key selector on `KsqlRepository`/`KsqlConfig` — a configurable function from Envelope to key, defaulting to today's envelope-id behaviour — so a deployment that genuinely wants per-aggregate partitioning can key on a payload field. The same gap exists in the Rust `MerkqlRepository` (`meshql-merkql/src/repository.rs:83`). Until it is closed, one-partition-per-topic is not a preference; it is the only configuration whose guarantee you can rely on.

The `memory` and `merksql` adapters are in-process and have no partitioning concept at all; this section does not apply to them.

Whatever you choose, **write down in the architecture document what you prioritised and what you gave up.** It is a domain decision, so it belongs where the domain is described — next to the event storm artifact — not implied by a partition count in a `docker-compose.yml` or a Debezium properties file that nobody reads as a design statement. A reader six months later must be able to tell the difference between "we chose total order per meshlette and capped our write throughput at one topic" and "nobody thought about it."

## A refused command is a domain object, not an error response

Events are events. If a user tries to put an aggregate into an illegal state, **then that is what they did**, and it is recorded in that event meshlette forever. You do not get to un-happen it, and a check that refuses the write at the door throws away the fact that someone tried.

It is the **worker's** responsibility to find those state errors and respond accordingly — by emitting a **rejection** into its own projection. A rejection carries:

- a link to the aggregate it concerns,
- the source event id, the actor, and the timestamp,
- what was attempted, and why the domain refused it.

Because it is an ordinary projection entity — its own mesh, its own `ProjectionUpdater`, its own graphlette — it is queryable by clients through the ordinary read path, so a UI can show *"your assignment was refused because the asset is disposed"* instead of leaving the user with a write that appeared to succeed. Think of it as a sophisticated, client-accessible dead-letter queue rather than a `logger.warn`.

It recurses cleanly: triaging a rejection is itself an event (`*_acknowledged`) folded into the same projection, so the queue gets worked without anything being mutated in place. **Correction is a new event, all the way down.**

### The consequence for admission checks

A gate in front of the event restlettes — a `Validator`, an auth filter — refuses only what is refusable **without domain state**:

- **authorization** — may this caller write this kind of event at all (`CasbinAuth`, `authorizedTokens`);
- **schema validity** — does the payload match the entity's JSON Schema;
- **create-only** — an event mesh takes `POST` and nothing else.

Anything that needs the aggregate's *current state* — is this asset already disposed, is this account closed, does this holder still exist — is the **worker's** job, and it produces a rejection object, not a 4xx. `Validator.validate(Stash data)` receives only the payload, with no view of any projection; if you find yourself wanting to query a graphlette from inside a validator, you have found the boundary.

Note that `teamchat` (a sibling product on the Rust port) currently does stateful checks at admission: its `write_gate` reads channel affiliation and returns 403. That is a defensible choice for chat, where immediate feedback while someone is typing matters more than it does in an asset register, and where a refusal has little downstream meaning worth querying later. But it is a **deliberate divergence** from the pattern above, and it should be named as one in that product's architecture document rather than copied into the next build because it was the shape that happened to be lying around.

## The worker, in Java

A worker is an independent process or thread (not part of the meshql `Server`; see SKILL.md's compose-your-own-binary model). `examples/egg-economy/src/main/java/com/meshql/examples/egg_economy/` shows the shape:

- **`EventWorkflowProcessor`** — owns a `KafkaConsumer<String, String>`, subscribes to the Debezium CDC topics for each event entity (`{prefix}.{db}.lay_report`, etc.), and routes each record to the updater(s) that care about it.
- **`ProjectionUpdater`** (abstract base, e.g. `HenProductivityUpdater extends ProjectionUpdater`) — implements the fold as a **read-modify-write over HTTP against the mesh itself**: `getProjection()` (GraphQL `getById`) → compute new field values → `createProjection()`/`updateProjection()` (REST `POST`/`PUT`). This is a valid, simple worker shape when the worker doesn't share a JVM/classpath with the noun's `Repository` — it goes through the same REST/GraphQL surface any other client would.
- **`ProjectionCache`** — an in-memory map from a business key (e.g. `hen_id`) to the mesh-generated `id`, populated via `discoverProjectionId()` (query GraphQL by FK immediately after a REST `POST`, since REST doesn't return the id — see SKILL.md's REST identity model anti-pattern). Needed because every subsequent update must `PUT /hen_productivity/api/{id}`, and the worker has no other way to learn `id`.

A worker with direct JVM access to the noun's `Repository` (e.g. running inside the same deployable as the `Server`) can skip the HTTP round-trip and call `repository.create(...)` directly — either shape satisfies the invariant, as long as nothing outside a worker writes the noun.

### Phased processing for FK-dependent workers

When a worker needs foreign keys resolved before it can process dependent entities — the anti-corruption-layer case in `examples/legacy/src/main/java/com/meshql/examples/legacy/LegacyToCleanProcessor.java` — process topics in dependency order within one run, sharing one Kafka consumer group across phases so offsets carry forward:

```java
props.put(ConsumerConfig.GROUP_ID_CONFIG, "legacy-processor");  // stable across the whole run — never System.currentTimeMillis()
...
// Phase 1: customers first (populates FK resolution cache)
logger.info("Phase 1: Processing customers...");
...
consumer.commitSync();
logger.info("Phase 1 complete: {} customers cached", idResolver.customerCacheSize());

// Phase 2: bills (need customer FK resolved)
...
consumer.commitSync();

// Phase 3: meter readings and payments (need customer + bill FKs)
...
consumer.commitSync();

// Phase 4: continuous consumption of all topics for ongoing changes
```

Two gotchas from `meshql/CLAUDE.md`'s "CDC Pipeline Patterns" that break this if missed:

- **Never build the group id from a timestamp.** A fresh group id per run discards committed offsets and reprocesses the whole log every restart.
- **Debezium's `payload.after` shape differs by source.** MongoDB CDC delivers it as a **double-encoded JSON string** (parse with `mapper.readTree(afterString)`); PostgreSQL CDC delivers a **proper JSON object** (use directly). Mixing these up silently produces null fields, not an exception.

## Replay: new nouns from historical events

Because events are immutable and every read is temporal, **the event log is the source of truth and nouns are disposable.** This unlocks the payoff:

- **A new noun can be built at any time from history.** Realize months later you want `farm_output_by_breed`? Define the mesh, write a worker, point its Kafka consumer at `AUTO_OFFSET_RESET_CONFIG = "earliest"` with a fresh group id. The noun materializes from events recorded long before it existed — no migration, no backfill, no lost data.
- **A noun can be rebuilt from scratch** by discarding it and replaying — the fix for a worker bug is "correct the fold and replay," not "hand-patch corrupted state."
- **Point-in-time nouns** fall out for free: fold the log up to a cutoff and you have the domain model as it stood at any past instant, which is exactly what `Repository.read(id, tokens, createdAt)` already gives every consumer of the noun mesh.

This is only sound because nothing bypasses the event log — which is exactly why the write-path rule and the storage-layer CDC bridge are enforced by infrastructure, not left to discipline.

## Why meshql fits this uniquely well

- **Immutable, versioned Envelopes** = an append-only event log, for free, on every backend.
- **Temporal reads** = replay-to-a-cutoff and point-in-time projections, for free.
- **Per-entity meshes** = events and projections are independently deployable, swappable, and rebuildable.
- **CQRS-by-convention** (REST writes / GraphQL reads) = the event-in / projection-out split is already the grain of the framework.

## Anti-patterns specific to this pattern

- Application code outside a worker writing to an analytic-noun mesh (`POST /hen_productivity/api` from a front end or an ad hoc script). Model the missing event instead, or fix the worker.
- **Publishing events from the application after the write** (a restlette-adjacent "save then emit" in a handler). That is a dual write with no shared transaction — a crash between the two loses the event and silently corrupts every downstream noun. Events come from the storage/CDC layer, post-commit.
- Updating or deleting an event after the fact — correct with a compensating event instead.
- Computing a domain model on the GraphQL read path instead of materializing it in a worker.
- A noun with no worker, kept in sync by hand.
- Treating a noun as the source of truth — if the log and the noun disagree, the log wins and the noun gets rebuilt.
- A worker fold that isn't deterministic/idempotent (depends on wall-clock, random, or read-back state) — it breaks replay and makes at-least-once delivery unsafe.
- **A worker that commits a consumed offset without durably checkpointing its fold state alongside it.** The two are one decision (see above): committing the offset alone means a restart resumes at a position with an empty projector, silently producing a wrong noun rather than an obvious failure.
- An unstable Kafka consumer group id, or forgetting `consumer.commitSync()` between phases — silently reprocesses the whole log on every restart instead of resuming.
- **Machinery to reconstruct a total order before publishing** — a re-sequencer, a parking/hold-back buffer, an all-entities-through-one-topic funnel, a physical-row-id cursor. The topic append defines the order; this is code written against a problem that was already solved. (A *per-meshlette* single-partition topic is not this — see "The queue is the ordering authority.")
- **Leaving `KsqlConfig.partitions` at its default of 3 and expecting per-aggregate ordering.** The producer key is hardcoded to the Envelope id (`KsqlRepository.java:109`), which is unique per record, so the default scatters one aggregate's events across three partitions with no ordering between them. Set it to 1: one partition per topic, one topic per event meshlette.
- **A partition/retention/`acks` choice made silently.** Ordering vs throughput vs durability is a domain decision — record in the architecture document what you prioritised and what you gave up.
- **A stateful domain check performed at admission and reported as a 4xx**, discarding the fact that the user tried. Admission refuses only what's refusable without domain state; everything else is a rejection object in a projection, queryable by the client.
