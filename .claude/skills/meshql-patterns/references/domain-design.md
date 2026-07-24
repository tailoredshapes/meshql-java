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
- **Correct order and time.** Events carry the store's commit order and timestamp, so replay and temporal reads reflect what actually happened, not application wall-clock during a request.

Because folds are (or should be) deterministic and idempotent, at-least-once delivery is enough: replaying a duplicate event recomputes the same noun.

**If an event meshlette's primary store is `merkql` (via the Rust connector+worker sidecar — see `merkql-architecture`), there's no CDC bridge to build for that entity at all.** merkql is itself a log; a restlette write against it already is the log append, not something a Debezium-style bridge needs to derive afterward. The Debezium/Kafka pattern above is for entities whose primary store is a real database (Mongo, Postgres) that isn't itself a log. Don't assume merkql needs the same bridging machinery — check whether `meshql-merkql`'s direct `Repository`/`Searcher` is available in the Rust sidecar before concluding merkql can't be used for a given event entity.

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
- An unstable Kafka consumer group id, or forgetting `consumer.commitSync()` between phases — silently reprocesses the whole log on every restart instead of resuming.
