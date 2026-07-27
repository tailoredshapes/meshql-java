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

## The six invariants

Follow these in every change; they are what the architecture excels at:

1. **CQRS by convention.** Writes go through REST (`POST/PUT/DELETE /<entity>/api`). Reads — especially anything relational or historical — go through GraphQL (`/<entity>/graph`). Don't add write mutations to graphlettes; don't build join logic into restlettes.
2. **Envelopes are immutable versions.** A PUT creates a new Envelope version. A DELETE writes a tombstone. Reads return the latest non-deleted version at-or-before the requested time. Never write code that updates a row/document in place or filters without excluding `deleted`.
3. **Temporal everywhere.** Every GraphQL `Query` field takes `at: Float` (epoch millis — `Float`, not `Int`: GraphQL `Int` is 32-bit and overflows on millisecond timestamps) and every `Repository.read` / `Searcher.find` honors it. The root query's `at` propagates through federated resolver hops, so point-in-time reads are consistent across the whole graph. When adding a query, include `at` in the schema and let the storage layer's version-windowing handle it.
4. **Authorization rides the Envelope.** `authorizedTokens` on each Envelope is matched against caller credentials extracted by the `Auth` interface (`getAuthToken`, `isAuthorized`). Repositories and Searchers must filter by tokens on *every* read path — adding a query that skips token filtering is a security bug.
5. **Storage is pluggable, behavior is certified — and the certification *is* the contract.** Datastores only implement `Repository` + `Searcher` (`com.meshql.core`), instantiated by a `Plugin` (`createRepository`/`createSearcher`). All business behavior lives above the interfaces. A new adapter must pass the shared certification suite (`repos/certification`: `RepositoryCertification`, `SearcherCertification`). Choosing a backend is a question of what you already run, what you know, and what you like — **not** of capability: there is no practical difference in behaviour between the implementations, and that is exactly what the certification suite exists to guarantee. The property this buys you is that you can swap the database engine and replay the queue into the new store without any end user noticing a difference. The consequence that matters: **never depend on a storage-engine property the certification does not guarantee.** See `references/storage-adapters.md`, "Don't reach underneath the abstraction."
6. **Pick your scale — one size does not fit all.** The same abstraction deliberately spans in-memory, embedded, and distributed forms, and choosing a different implementation for dev/test than for production is *idiomatic*, not a compromise. `memory` or `sqlite` for a test; `mongo`, `postgres`, or `ksql` for prod. The behavior contract (certification, the interface) is identical; only the deployment weight changes. When you see two adapters for one seam, that is the design working — do not collapse them.

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

Query templates are **Handlebars producing a JSON query**: `{"payload.farm_id": "{{id}}"}` for Mongo (note the `payload.` prefix Mongo's `Searcher` expects), or `{"farm_id": "{{id}}"}` for SQL-shaped backends whose query builder addresses payload columns directly — check the adapter you're using. The special key `id` always addresses the Envelope id, not a payload field.

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
- **Modeling a domain** (the pattern meshql is built for): identify **events** first (immutable facts), derive **projections** (domain models) from them, and write **one worker per projection** that folds events into it. Front ends write events, never domain models — enforced by infra + convention — and new domains can be built by replaying history. Full methodology in `references/domain-design.md`. Entity taxonomy split into **actors** (long-lived: farm, coop, hen), **events** (lay_report, storage_deposit, storage_withdrawal, container_transfer, consumption_report), and **projections** (hen_productivity, container_inventory, farm_output) is shown in `examples/egg-economy/` (13 entities, backed by three separate MongoDB URIs — `ACTORS_MONGO_URI`, `EVENTS_MONGO_URI`, `PROJECTIONS_MONGO_URI`); `examples/farm/` and `examples/logistics/` are the minimal non-event-sourced case.
- **Custom write-time validation**: implement `Validator` (`CompletableFuture<Boolean> validate(Stash data)`, `com.meshql.core.Validator`) and pass it into `Restlette`/`Server` alongside the JSON Schema validator. For computed read endpoints or side-channel routes that don't fit the graph (e.g. a manifest endpoint), pass extra servlets to `server.init(config, Map.of("/manifest", new ManifestServlet(...)))` — see `examples/farm/Main.java`.
- **Auth beyond NoAuth**: `JWTSubAuthorizer` extracts the `sub` claim from a Bearer token (decode-only — does not verify signatures, assumes an upstream gateway validates); wrap with `CasbinAuth.create(model, policy, jwtAuth)` for role-based checks.
- **FE needs to know how fresh data is / show a "pending" state** → see "Honesty: as-of freshness" above; add `createdAt: Date` to the GraphQL type, nothing else to do.

## Anti-patterns to flag

- A GraphQL query without `at: Float` — breaks temporal uniformity. (`at: Int` is also wrong: millisecond timestamps overflow GraphQL's 32-bit `Int`.)
- Hard deletes, in-place updates, or reads that don't filter `deleted`.
- A `Searcher` query path that skips `authorizedTokens` filtering.
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
