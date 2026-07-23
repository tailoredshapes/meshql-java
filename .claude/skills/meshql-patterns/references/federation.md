# Federation: Resolvers and Query Templates

Graphlettes federate by calling **other graphlettes' queries**, never by joining at the storage layer. Each entity owns its own store; relations are resolved at query time.

## Query templates

Defined on `RootConfig` (`com.meshql.core.config.RootConfig`, built via `RootConfig.builder()`). A template is Handlebars text that renders to a JSON query:

```java
.singleton("getById", "{\"id\": \"{{id}}\"}")                          // one result
.singleton("getByName", "{\"payload.name\": \"{{id}}\"}")              // one result, non-id lookup
.vector("getByFarm", "{\"payload.farm_id\": \"{{id}}\"}")              // many results, relation lookup
```

- `singleton(...)` vs `vector(...)` controls whether the GraphQL field returns an object or a list — it must match the schema's return type (`Coop` vs `[Coop]`).
- The special key `id` addresses the Envelope id; other keys address payload fields. The MongoDB `Searcher` expects a `payload.` prefix on payload keys (`payload.farm_id`); check the adapter you're using for its exact template shape — SQL-backed adapters may address the column/JSONB path differently.
- Args come from the GraphQL call's arguments — `getByFarm(id: "farm-1")` renders `{"payload.farm_id": "farm-1"}`.

## The two resolver directions

For Farm 1—N Coop (coop payload carries `farm_id`):

**Child → parent (singleton):** on the coop's `RootConfig`:

```java
.singletonResolver("farm", "farm_id", "getById", platformUrl + "/farm/graph")
//                 ^field   ^foreign_key  ^query    ^target graphlette
```

Reads `farm_id` from the parent Coop object, then invokes `getById` on `/farm/graph` with `id = <farm_id>`.

**Parent → children (vector):** on the farm's `RootConfig`:

```java
.vectorResolver("coops", null, "getByFarm", platformUrl + "/coop/graph")
```

The second argument `null` means "pass this object's own `id`". The target graphlette must define `getByFarm` with template `{"payload.farm_id": "{{id}}"}` — **the relation query lives on the child entity's `RootConfig`, the resolver on the parent's.** Forgetting one half is the most common federation bug.

## Internal vs HTTP resolution

- `singletonResolver`/`vectorResolver` take a full `URI` (or string URL) — even when the target graphlette lives in the *same* server, the farm example above resolves via `platformUrl + "/coop/graph"`, which is a real HTTP round-trip.
- `internalSingletonResolver`/`internalVectorResolver` take a bare graphlette path (`"/coop/graph"`, no host) and resolve **in-process**, with no HTTP overhead — use these whenever the graphlettes are registered on the same `Server`:

```java
// External (HTTP call — use when services are separate):
.singletonResolver("author", "author_id", "getById", platformUrl + "/author/graph")
.vectorResolver("books", null, "getByAuthor", platformUrl + "/book/graph")

// Internal (in-JVM — use when graphlettes share a server):
.internalSingletonResolver("author", "author_id", "getById", "/author/graph")
.internalVectorResolver("books", null, "getByAuthor", "/book/graph")
```

Real multi-entity examples (`examples/egg-economy`, `examples/legacy`) use internal resolvers throughout, e.g.:

```java
.internalSingletonResolver("farm", "farm_id", "getById", "/farm/graph")
.internalVectorResolver("hens", null, "getByCoop", "/hen/graph")
```

Resolver parameter reference (same shape for internal and external):
- **Singleton**: `(fieldName, foreignKeyField, queryName, target)` — looks up the parent's `foreignKeyField` value, calls `queryName(id: value)` on the target.
- **Vector**: `(fieldName, null, queryName, target)` — passes the parent's own `id` to `queryName(id: parentId)` on the target.

Use full URLs only when federating across genuinely separate, independently deployed services (e.g. an anti-corruption-layer service federating to another platform — see `examples/egg-economy-sap`, `examples/egg-economy-salesforce`).

**Temporal propagation:** the root query's effective `at` propagates through every resolver hop, so a point-in-time read of a farm returns its coops — and their hens — *as they were at that time*. Don't break this when touching resolver/schema-building code.

**Performance:** indexing the storage layer on foreign-key fields (`farm_id`, `coop_id`, …) gives roughly a 100x improvement over any batching scheme — prefer a DB index over building a DataLoader-style batcher for a hot federation path.

## Schema side

Each graphlette's `.graphql` file declares a **local view** of foreign types — only the fields reachable through this entity:

```graphql
type Farm {
    id: ID
    name: String!
}

type Coop {                # local projection of the Coop entity
    id: ID
    name: String!
    farm: Farm!             # resolved by singletonResolver
    hens: [Hen]              # resolved by vectorResolver
}
```

This is intentional duplication — each graphlette owns its own schema file, even when two files describe overlapping fields of the same underlying entity.

Keep local views one level deep. If a consumer needs farm → coop → hen in one query, the chain works because each hop is its own resolver (see `examples/farm`, which chains farm → coop → hen → lay_report; `examples/logistics`, which chains warehouse → shipment → package → tracking_update).

## Checklist for a new relation

1. Child payload carries `<parent>_id` (snake_case) — add it to the child's JSON Schema.
2. Child `RootConfig`: `vector` query `getBy<Parent>` with template `{"payload.<parent>_id": "{{id}}"}`.
3. Child schema: `parent` field + local parent type; child `RootConfig`: `singletonResolver`/`internalSingletonResolver` with the foreign-key field name.
4. Parent schema: `children: [Child]` field + local child type; parent `RootConfig`: `vectorResolver`/`internalVectorResolver` with `null`.
5. Both queries take `at: Float` in the schema.
6. If both graphlettes run in the same `Server`, use the `internal*` resolver variants, not the URL-based ones.
