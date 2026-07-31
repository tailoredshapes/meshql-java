---
name: meshql-patterns
description: Core architecture patterns for building services with meshql (Java). Use when adding entities, endpoints, resolvers, or storage backends; designing GraphQL/JSON schemas; wiring federation; or modeling domains (actors/events/projections) on meshql. Covers Repository/Searcher interfaces, Envelope semantics, temporal queries, authorization, and CQRS conventions.
---

# meshql Architecture Patterns

meshql turns every entity into a pair of HTTP surfaces backed by the same store:

- **REST restlette** at `/<entity>/api` — CRUD writes + simple reads, JSON Schema validation
- **GraphQL graphlette** at `/<entity>/graph` — rich queries, federation to other entities, point-in-time reads

Everything is stored as an **Envelope**: `Envelope(String id, Stash payload, Instant createdAt, boolean deleted, List<String> authorizedTokens)` (`com.meshql.core.Envelope`) — `Stash` is the framework's `Map<String, Object>`-like payload type. Updates append new versions (same `id`, newer `createdAt`); deletes set `deleted = true`. Nothing is mutated or hard-deleted. This is what makes temporal queries (`at:` parameter) and audit trails free — never break it.

**The pattern meshql is built for** (start here for any non-trivial system): model the domain as **events** (immutable facts) and **projections** (domain models derived from events by **workers**). Front ends write events, never domain models; new domain models can be materialized by replaying history. The invariants below exist to make this sound.

**Before wiring a single entity for a new system, read `references/domain-design.md` in full — not just this summary.** It is not one optional deep-dive among several; it is the file whose own methodology section calls this "the pattern meshql is built for." Skipping it and defaulting to plain CRUD for every entity is the single most common way to misuse this framework, and it's an easy trap: nothing stops a plain-CRUD build from compiling, passing its own tests, or looking finished. If the deployment uses `merkql` anywhere (via the Rust connector+worker sidecar described in `merkql-architecture` — merkql itself is Rust-only), that's a strong signal the entity feeding it is meant to be event-sourced, not a generic swap-in datastore for ordinary mutable state; re-open `domain-design.md` before treating "I picked a storage mechanism" as a finished design decision.

**A note on `meshql-iron`'s `event-vs-domain-mesh.md`, if you also have that skill loaded:** it says the event/domain split is "a recommended pattern, not a technology requirement" and tells you not to force it onto plain-CRUD entities. That's correct advice *for a frontend consuming an already-built deployment* — you can't and shouldn't invent an event/domain split for a backend someone else already designed as plain CRUD. It is not license to skip the methodology below when *you* are the one designing the backend from scratch. If you're doing both jobs (building the backend and its frontend in the same task), `domain-design.md`'s guidance governs the design decision; `event-vs-domain-mesh.md` governs how you *read* whatever the backend ends up being once it exists.

## Build the product before the platform

**A storage decision never blocks product work.** Adapters are certified interchangeable and swapping storage is a two-line `Plugin` change (invariant 5). **Build against SQLite first.**

The order that works, with the effort to expect when the event storm already exists:

1. **Events and domain** — the entity list, the schemas, the meshes. **Minutes.**
2. **Workers** — one per projection. **Under an hour.**
3. **Config and wiring** — **about three minutes.**
4. **Storage, scale, benchmarks, adapters** — only once a real requirement names them.

**If a plan's Stage 0 is platform work, that is the smell — reorder it.** A recent rebuild spent hours on storage adapters and a contention benchmark and produced 120 KB of planning documents and zero lines of product code, because its plan front-loaded platform work in direct contradiction of the framework's own first guarantee. **Planning documents are not deliverables.** The one written artifact worth producing before code is the event storm (`references/domain-design.md`) — a domain document, not a platform one.

**Localdev default: SQLite + `merkql-connect`** (or SQLite + Debezium if the pipeline is Kafka-based). One meshql process, one connector process, one worker process, and **no Docker on the SQLite path**. See "Getting events onto the log" below.

## The six invariants

Follow these in every change; they are what the architecture excels at:

1. **CQRS by convention.** Writes go through REST (`POST/PUT/DELETE /<entity>/api`). Reads — especially anything relational or historical — go through GraphQL (`/<entity>/graph`). Don't add write mutations to graphlettes; don't build join logic into restlettes.
2. **Envelopes are immutable versions.** A PUT creates a new Envelope version. A DELETE writes a tombstone. Reads return the latest non-deleted version at-or-before the requested time. Never write code that updates a row/document in place or filters without excluding `deleted`.
3. **Temporal everywhere.** Every GraphQL `Query` field takes `at: Float` (epoch millis — `Float`, not `Int`: GraphQL `Int` is 32-bit and overflows on millisecond timestamps) and every `Repository.read` / `Searcher.find` honors it. The root query's `at` propagates through federated resolver hops, so point-in-time reads are consistent across the whole graph. When adding a query, include `at` in the schema and let the storage layer's version-windowing handle it.
4. **Authorization rides the Envelope.** `authorizedTokens` on each Envelope is matched against caller credentials extracted by the `Auth` interface (`getAuthToken`, `isAuthorized`). Repositories and Searchers must filter by tokens on *every* read path — adding a query that skips token filtering is a security bug.
5. **Storage is pluggable, behavior is certified — and the certification *is* the contract.** Datastores only implement `Repository` + `Searcher` (`com.meshql.core`), instantiated by a `Plugin` (`createRepository`/`createSearcher`). All business behavior lives above the interfaces. A new adapter must pass the shared certification suite (`repos/certification`: `RepositoryCertification`, `SearcherCertification`). Choosing a backend is a question of what you already run, what you know, and what you like — **not** of capability: there is no practical difference in behaviour between the implementations, and that is exactly what the certification suite exists to guarantee. The property this buys you is that you can swap the database engine and replay the queue into the new store without any end user noticing a difference. The consequence that matters: **never depend on a storage-engine property the certification does not guarantee.** See `references/storage-adapters.md`, "Don't reach underneath the abstraction."
6. **Pick your scale — one size does not fit all.** The same abstraction deliberately spans in-memory, embedded, and distributed forms, and choosing a different implementation for dev/test than for production is *idiomatic*, not a compromise. `memory` or `sqlite` for a test; `mongo`, `postgres`, or `ksql` for prod. The behavior contract (certification, the interface) is identical; only the deployment weight changes. When you see two *certified* adapters for one seam, that is the design working — do not collapse them.

   **What this invariant does not say.** It is not a licence to keep a hand-rolled stand-in for a seam that has a real implementation. The CDC seam is where that went wrong in the sibling ports: the Rust skill's version of this invariant claimed "a poll-based CDC tail in-process; a native change-stream connector in prod" as two implementations of one seam, when only the poller existed and the connector was an intention written in the present tense. The connector now exists — **`merkql-connect`** — and it is a separate process, not an in-process alternative. See "Skills describe what exists".

## Skills describe what exists

This file and every reference under it describe **code that is in the repository now**. Design intent is written in a clearly-marked "not built yet" form — as `domain-design.md`'s "Known upstream gap" does — and **never in the present tense**.

The failure this prevents is cheap to commit and expensive to find. A CDC claim in the sibling skill started as an intention phrased as a description, was repeated into a second document, and by the third nothing distinguished it from working code. Readers — human and agent — then planned against a connector that did not exist. If you are documenting something you are about to build, label it, or do not write it down yet.

## Getting events onto the log: two topologies

Workers consume events from a log. There are exactly **two** valid ways to get committed writes onto that log. Choose once per event meshlette.

### 1. The log is the primary store — no bridge at all

The event mesh's `Repository` writes straight to a log, so the restlette `POST` **is** the append. On the JVM this means `ksql` (`repos/ksql`). It is **not** available with merkql: merkql is an embedded Rust library with no wire protocol, so a Java meshlette cannot be backed by it — see the `merkql-architecture` skill.

**Pick this** for a new event entity with no prior data and no store you need to keep.

### 2. A database is the primary store — add a connector

The event mesh keeps Postgres, Mongo or SQLite, and a **separate process** replicates each committed write onto the log — `merkql-connect` onto a merkql topic, or Debezium onto a Kafka topic.

```
front end ──POST──▶ event restlette ──▶ Postgres / Mongo / SQLite
   (Java)                                       │
                                                │ CDC, post-commit
                                                ▼
                                merkql-connect  /  Debezium       ← its own process
                                                │ sole writer
                                                ▼
                                        topic (1 partition)
                                                │
                                      workers (readers)
                                                │
                                                ▼
                        ──POST──▶ projection restlettes ──▶ Postgres/Mongo/SQLite
```

In **both** topologies, projections live in a queryable store and are written **only by their own worker**.

### The rule for picking

> **Does this event meshlette need to keep a database?** No → topology 1. Yes → topology 2.

There is no valid third option. In particular, **never publish from application code**: a "save then emit" hook is a dual write with no shared transaction, and a crash between the commit and the publish loses the event permanently while the row survives (`references/domain-design.md`).

### `merkql-connect` is a Rust crate but not a Rust-only tool

`merkql-connect` is a **standalone deployable**, not a library: `merkql-connect <config.toml>`, run beside the database, configured with TOML. It talks to the database and to merkql and to nothing else — no restlette dependency, no shared process, no in-language API. **What wrote the row is irrelevant to it**, which is why it belongs in a Java deployment's toolbox at all. Full reference: `merkql-connect/README.md` in the `meshql-rs` repo.

It is what makes merkql's single-writer rule survivable. merkql is single-writer per process and does not detect a second one — the log silently loses records. Because the connector is a separate process, the meshql service writes only to the database and never touches merkql, and workers are readers. The constraint is satisfied by construction. Four separate builds hit it before the connector existed.

Backends: SQLite, MongoDB, PostgreSQL. No polling anywhere — a Postgres logical replication slot woken by a trigger-emitted `NOTIFY`, a Mongo `watch()` whose resume token is the cursor, inotify on the SQLite database's directory with an ordinary connection as the reader.

**What actually works against a Java deployment today** — a description of the code, not the design:

| Java store | Works today | Why |
|---|---|---|
| Mongo | **yes** | `Converters.envelopeToDocument` writes `id`/`createdAt`/`deleted`/`authorizedTokens`/`payload` — exactly the document shape the connector decodes. |
| SQLite, Postgres | **not yet** | The connector reads a `created_at_ms` column and a JSON `authorized_tokens` column. Java's tables use `created_at` (and Postgres keeps tokens in a `<table>_authtokens` side table). |

The gap is a decoder — one function per backend — not a design problem. But **do not plan around it as though it were done**, and do not describe it as available.

Two operational hazards to plan for rather than discover:

- **A Postgres replication slot pins WAL.** The heartbeat exists to stop an *idle* slot filling the disk, which is counterintuitive: the low-traffic deployment is the dangerous one, because the slot only advances when the connector advances it while every other table keeps generating WAL. A **stopped** connector still pins WAL — the heartbeat only runs while it is polling. Retiring a connector means `SELECT pg_drop_replication_slot(...)`, not deleting a config file.
- **SQLite's inotify edge is unavailable on some filesystems**, network mounts especially. It degrades **loudly** — `NoFeed`, refuse to start — and there is deliberately no timer fallback, because a connector that quietly becomes a poller has quietly stopped matching what was deployed.

## Honesty: as-of freshness

The FE should be able to tell how fresh a payload is, so it can show a "pending" state and let the user refresh instead of silently rendering stale data. Envelope metadata stays internal (invariant 2 links `createdAt` to versioning, not exposure) except for two deliberate, minimal leaks:

- **REST**: `create`/`read`/`update` responses carry `X-Meshql-Created-At` (ISO-8601 instant) and `X-Meshql-Deleted` (`true`/`false`) response headers — automatic, no config, the JSON body stays payload-only. Not present on `list` (one header can't represent many envelopes) or `delete` (`Repository.remove` returns only a `Boolean`, no Envelope to read a timestamp from).
- **GraphQL**: every `Searcher` merges `createdAt` (ISO-8601 string) into the result `Stash` next to `id`. A type opts in by declaring `createdAt: Date` (or `String`) — the scalar field resolver is a generic `Stash` lookup, so no resolver code is needed. `deleted` is never exposed (search results already exclude deleted/superseded versions).

Don't widen this: no other Envelope field (`authorizedTokens`, etc.) should ever leak into a REST body or an unopted GraphQL field.

## Naming and layout conventions

| Thing | Convention | Example |
|---|---|---|
| Graphlette path | `/<entity>/graph` (snake_case entity) | `/lay_report/graph` |
| Restlette path | `/<entity>/api` | `/lay_report/api` |
| Collection/table | `{prefix}-{env}-{entity}` | `farm-development-coop` |
| Database | `{prefix}_{env}` | `farm_development` |
| Foreign key in payload | snake_case `<parent>_id` | `farm_id` |
| GraphQL schema file | `config/graph/<entity>.graphql` | `config/graph/hen.graphql` |
| JSON Schema file | `config/json/<entity>.schema.json` | `config/json/hen.schema.json` |

**Query naming:** every real example (`examples/farm`, `examples/logistics`, `examples/egg-economy`) uses the same generic dialect — `getById(id, at)`, `getAll(at)`, `getByName(name, at)`, `getBy<Parent>(id, at)` — rather than entity-named queries (`getFarm`, `getFarms`). Stick with this dialect for consistency across the codebase; there is no meshql-mcp-style consumer in this repo requiring it, but it is what every existing schema and `RootConfig` wiring already does.

## Minimal entity wiring (the canonical shape)

From `examples/farm/src/main/java/com/meshql/examples/farm/Main.java` — one entity is always: storage config + `GraphletteConfig` + `RestletteConfig`, assembled into `Config`.

```java
MongoConfig coopDB = createMongoConfig(mongoUri, prefix, env, "coop");

Config config = Config.builder()
        .port(port)
        .graphlette(GraphletteConfig.builder()
                .path("/coop/graph")
                .storage(coopDB)
                .schema("/app/config/graph/coop.graphql")
                .rootConfig(RootConfig.builder()
                        .singleton("getByName", "{\"payload.name\": \"{{id}}\"}")
                        .singleton("getById", "{\"id\": \"{{id}}\"}")
                        .vector("getByFarm", "{\"payload.farm_id\": \"{{id}}\"}")
                        .singletonResolver("farm", "farm_id", "getById", platformUrl + "/farm/graph")
                        .vectorResolver("hens", null, "getByCoop", platformUrl + "/hen/graph")))
        .restlette(RestletteConfig.builder()
                .path("/coop/api")
                .port(port)
                .storage(coopDB)
                .schema(loadJsonSchema("/app/config/json/coop.schema.json")))
        .build();

Auth auth = new NoAuth();
Map<String, Plugin> plugins = Map.of("mongo", new MongoPlugin(auth));
Server server = new Server(plugins);
server.init(config);
```

### Query templates: the shape is per-adapter, and getting it wrong fails silently

A query template is a Handlebars template **written in the query language of the store behind that searcher** — it is *not* a portable JSON dialect. `Searcher.compileQuery`'s own javadoc says so (`core/src/main/java/com/meshql/core/Searcher.java:17-20`): *"A query fragment such as `id = '{{id}}'` is written in the query language of whatever store sits behind this searcher."* Mongo takes a JSON filter document; every other adapter takes a **SQL `WHERE` fragment** spliced into the searcher's outer template at `{{{filters}}}`.

The rule to remember:

> **Payload fields are never addressed bare — except on `memory`/`merksql`, where they always are. The Envelope's `id` is top-level everywhere.**

| Adapter | Payload field `name` | Envelope `id` | Template is |
|---|---|---|---|
| `mongo` | `{"payload.name": "{{id}}"}` | `{"id": "{{id}}"}` | a whole Mongo filter document |
| `postgres` | `payload->>'name' = '{{id}}'` | `id = '{{id}}'` | a SQL `WHERE` fragment |
| `sqlite` | `json_extract(payload, '$.name') = '{{id}}'` | `id = '{{id}}'` | a SQL `WHERE` fragment |
| `ksql` | `EXTRACTJSONFIELD(payload, '$.name') = '{{id}}'` | `id = '{{id}}'` | a ksqlDB `WHERE` fragment |
| `memory`, `merksql` | `name = 'x'` — **bare, no prefix** | `id = 'x'` | pseudo-SQL, equality only |

**Why this bites.** On Mongo, a template that omits the `payload.` prefix addresses a top-level document field that does not exist, so the query matches nothing and **returns an empty list — with no error**. `memory` and `merksql` are the mirror image: `MerkSqlSearcher.matchesCondition` (`repos/merksql/.../MerkSqlSearcher.java:66-70`) special-cases `id` and otherwise looks the field up **directly in the payload map**, so a `payload.`-prefixed key finds nothing and again returns empty:

```java
if ("id".equals(field)) {
    actualValue = envelope.id();
} else {
    Object payloadValue = envelope.payload().get(field);
```

`InMemorySearcher.java:79-83` is the same logic. **This differs from the Rust `meshql-merkql` adapter**, whose matcher runs a dot-path against the serialized Envelope and therefore *requires* `payload.` — do not port templates between the two without changing them.

**A by-id test passes either way**, because `id` is top-level and correct in both the right and the wrong version of a template. So: **always test a list/vector query against data you actually wrote.** A suite that only exercises `getById` will go green over a completely broken set of templates.

**Matcher limits on `memory`/`merksql`: exact equality only, all conditions ANDed.** `matchesQuery` splits on `\s+AND\s+` (`MerkSqlSearcher.java:46`) and each condition on `\s*=\s*` (`:56`); anything that isn't an equality is logged and treated as a non-match. **No ranges, no `OR`, no `ORDER BY`.** Every range question must become a **materialised bucket field** — `close_period`, `occurred_on`, `size_band` — written by the worker at fold time and queried by equality. Treat that as good practice rather than a workaround: bucket fields are the portable intersection across all the adapters, so a projection built that way keeps invariant 5's swap-and-replay property. A projection leaning on Postgres range predicates silently pins itself to Postgres.

**Two live gotchas on `ksql` specifically**, since its fragment is spliced raw into ksqlDB SQL (`KsqlSearcher.java:41-43`): the searcher unconditionally appends `AND deleted = false`, so an unparenthesised `OR` in your template binds looser than the tombstone filter and **leaks deleted records** — always self-parenthesize. And `KsqlSearcher` never references its `timestamp` argument, so GraphQL's `at:` is silently ignored on that adapter.

## Deployment model: compose your own binary

A meshql system is a small Java application owned by the service developer — there is no prebuilt meshql server configured at runtime. This is a deliberate design decision:

- **Maven is the composition mechanism.** Depend on `core`, `server`, and *only* the adapter/auth modules the service uses (`mongo`, `postgres`, `jwt`, `casbin`, …), all under `groupId com.tailoredshapes`, `artifactId meshql-java` version family (e.g. `core`/`server`/`mongo` at `0.2.0`). Unused backends are never imported, so they cost nothing.
- **`Main.java` is the configuration.** The wiring file (see `examples/farm/src/main/java/com/meshql/examples/farm/Main.java`) is code-as-config on purpose: entity wiring, resolver names, and query templates are compile-checked (records, builders) instead of failing at runtime from a config file typo.
- **`Plugin` is a runtime lookup keyed by name, not a dynamic loader.** `Map<String, Plugin> plugins = Map.of("mongo", new MongoPlugin(auth))` looks like registry dispatch, but every `Plugin` implementation in that map is a compile-time Maven dependency chosen by the developer — there is no classloader scanning, `ServiceLoader`, or jar-dropped-into-a-directory mechanism. Swapping Postgres for Mongo is a two-line change in `Main.java` plus a `pom.xml` dependency swap, not a plugin-directory deployment.
- **Adapters are interchangeable at the source level.** The certification suite (`repos/certification`) guarantees behavior parity across `Repository`/`Searcher` implementations, so the swap above doesn't change any calling code.

## Decision guide

- **Adding/changing an entity or query** → read `references/adding-an-entity.md`
- **Relating entities (1:1, 1:N), internal vs HTTP federation** → read `references/federation.md`
- **New datastore adapter, or touching Repository/Searcher internals** → read `references/storage-adapters.md`
- **Getting events onto a log (merkql or Kafka)** → "Getting events onto the log: two topologies" above, then `merkql-connect/README.md` (in the `meshql-rs` repo) if you land on topology 2 with merkql.
- **Modeling a domain** (the pattern meshql is built for): identify **events** first (immutable facts), derive **projections** (domain models) from them, and write **one worker per projection** that folds events into it. Front ends write events, never domain models — enforced by infra + convention — and new domains can be built by replaying history. Full methodology in `references/domain-design.md`. Entity taxonomy split into **actors** (long-lived: farm, coop, hen), **events** (lay_report, storage_deposit, storage_withdrawal, container_transfer, consumption_report), and **projections** (hen_productivity, container_inventory, farm_output) is shown in `examples/egg-economy/` (13 entities, backed by three separate MongoDB URIs — `ACTORS_MONGO_URI`, `EVENTS_MONGO_URI`, `PROJECTIONS_MONGO_URI`); `examples/farm/` and `examples/logistics/` are the minimal non-event-sourced case.
- **Custom write-time validation**: implement `Validator` (`CompletableFuture<Boolean> validate(Stash data)`, `com.meshql.core.Validator`) and pass it into `Restlette`/`Server` alongside the JSON Schema validator. For computed read endpoints or side-channel routes that don't fit the graph (e.g. a manifest endpoint), pass extra servlets to `server.init(config, Map.of("/manifest", new ManifestServlet(...)))` — see `examples/farm/Main.java`.
- **Auth beyond NoAuth**: `JWTSubAuthorizer` extracts the `sub` claim from a Bearer token (decode-only — does not verify signatures, assumes an upstream gateway validates); wrap with `CasbinAuth.create(model, policy, jwtAuth)` for role-based checks.
- **FE needs to know how fresh data is / show a "pending" state** → see "Honesty: as-of freshness" above; add `createdAt: Date` to the GraphQL type, nothing else to do.

## Anti-patterns to flag

- A GraphQL query without `at: Float` — breaks temporal uniformity. (`at: Int` is also wrong: millisecond timestamps overflow GraphQL's 32-bit `Int`.)
- Hard deletes, in-place updates, or reads that don't filter `deleted`.
- A `Searcher` query path that skips `authorizedTokens` filtering.
- **A query template written for the wrong adapter** — a bare `{"farm_id": "{{id}}"}` against Mongo, or a `payload.`-prefixed key against `memory`/`merksql`. Fails silently with an empty result set, and a `getById` test still passes. Test a vector query against written data.
- **A range, `OR`, or sort expressed in a `memory`/`merksql` template** — that matcher is equality-and-AND only. Materialise a bucket field in the projection instead.
- **An unparenthesised `OR` in a `ksql` template** — the searcher appends `AND deleted = false`, which binds tighter, so deleted records leak.
- Business/aggregation logic inside an adapter module (`mongo`, `postgres`, …) — it belongs in a `Validator`, an extra servlet/route, or a projection entity fed by a worker.
- Cross-entity joins implemented in a restlette — that's what graphlette resolvers are for (`references/federation.md`).
- A new adapter merged without passing `RepositoryCertification`/`SearcherCertification`.
- **Depending on an engine property the certification doesn't guarantee** — a physical row id, a writer-serialisation guarantee, a native sequence — especially as a CDC cursor or an ordering key. It silently couples the product to one backend and breaks swap-and-replay (invariant 5; `references/storage-adapters.md`).
- Building machinery to reconstruct a total event order before publishing — the Kafka topic's append already defines the order (`references/domain-design.md`, "The queue is the ordering authority").
- A stateful domain refusal reported as a 4xx from a `Validator` or admission filter, discarding the fact that the user tried it. Admission refuses only what's refusable without domain state; the rest is a worker-emitted rejection object (`references/domain-design.md`).
- Assuming a REST `POST`/`PUT` response body contains the generated `id`. It doesn't, by design — the URL is the identifier. Server-side code that needs the id after a write must discover it via a GraphQL query (see `ProjectionUpdater.createProjection()` / `discoverProjectionId()` in `examples/egg-economy`).
- Publishing an event from application code as a side effect of a write (a "save then emit" hook) instead of letting storage-layer CDC (Debezium) derive it from the committed write — see `references/domain-design.md`.
- An unstable Kafka consumer group id (e.g. built from `System.currentTimeMillis()`) in a CDC/worker pipeline — breaks phased replay and offset continuity between runs.
- Reaching for a dynamic plugin-loading scheme (classloaders, `ServiceLoader`, jar-drop directories) for storage backends — composition happens in `pom.xml` + the explicit `Map<String, Plugin>` passed to `Server` (see "Deployment model" above).
- **A second writer on a merkql topic.** merkql is single-writer per process and does not detect a collision; the log silently loses records. Exactly one process appends — the connector — and the meshql service never touches merkql.
- **A plan whose Stage 0 is platform work** — storage adapters, contention benchmarks, an infrastructure spike — before any event, entity or worker exists. Build the product first; storage is a two-line change.
- **A skill, README or design doc describing something unbuilt in the present tense.** Label it "not built yet" or leave it out. This has already cost real work (see "Skills describe what exists").
