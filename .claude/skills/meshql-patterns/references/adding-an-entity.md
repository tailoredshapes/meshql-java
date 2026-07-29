# Adding an Entity

The complete checklist for adding entity `widget` (with a `parent_id` relation to `parent`). Reference implementations: `examples/farm/` (minimal, 4 entities), `examples/logistics/` (4 entities, 8 resolvers, 3 frontends), `examples/egg-economy/` (13 entities, actors/events/projections).

## 1. GraphQL schema — `config/graph/widget.graphql`

```graphql
scalar Date

type Widget {
    id: ID
    name: String
    parent_id: String
    parent: Parent
    createdAt: Date
}

# Local view of the federated type: declare only the fields this
# graphlette exposes. Each graphlette owns its own schema file.
type Parent {
    id: ID
    name: String
}

type Query {
    getById(id: ID, at: Float): Widget
    getAll(at: Float): [Widget]
    getByParent(id: ID, at: Float): [Widget]
}
```

Rules:
- **Every** query takes `at: Float` (epoch millis, point-in-time read — not `Int`, which is 32-bit in GraphQL and overflows on millis).
- **Optional:** add `createdAt: Date` (or `String`) to a type to opt into the "honesty" as-of field — every `Searcher` already merges it into the result `Stash` (next to `id`), so declaring the field is all that's needed; no resolver code. Omit it and it's simply not selectable. (REST gets the equivalent via `X-Meshql-Created-At`/`X-Meshql-Deleted` response headers automatically — see SKILL.md.)
- Query naming: every real example in this repo uses the generic dialect — `getById`, `getAll`, `getBy<Field>`/`getBy<Parent>` — not entity-named queries (`getWidget`). Match it.
- Federated fields (`parent: Parent`) get a local type definition with just the fields you want reachable from this entity. One level of nesting is the norm.

## 2. JSON Schema — `config/json/widget.schema.json`

```json
{
  "type": "object",
  "additionalProperties": false,
  "required": ["name", "parent_id"],
  "properties": {
    "id": { "type": "string", "format": "uuid" },
    "name": { "type": "string" },
    "parent_id": { "type": "string", "format": "uuid" }
  }
}
```

This validates the REST write payload. It describes the **payload** only. `id` may appear as an *optional* property purely for validator/tooling completeness (see `examples/farm/config/json/coop.schema.json`), but the framework generates and manages it — a client-supplied `id` in the payload is not how records are addressed. Never include `createdAt`, `deleted`, or `authorizedTokens`; those are Envelope metadata managed by the framework.

## 3. Wire it in `Main.java`

```java
MongoConfig widgetDB = createMongoConfig(mongoUri, prefix, env, "widget");

Config config = Config.builder()
        .port(port)
        // ... other entities ...
        .graphlette(GraphletteConfig.builder()
                .path("/widget/graph")
                .storage(widgetDB)
                .schema("/app/config/graph/widget.graphql")
                .rootConfig(RootConfig.builder()
                        // queries: name must match the GraphQL Query field; template is
                        // Handlebars rendering a JSON query against payload fields (and "id")
                        .singleton("getById", "{\"id\": \"{{id}}\"}")
                        .vector("getAll", "{}")
                        .vector("getByParent", "{\"payload.parent_id\": \"{{id}}\"}")
                        // relations: see references/federation.md
                        .singletonResolver("parent", "parent_id", "getById", platformUrl + "/parent/graph")))
        .restlette(RestletteConfig.builder()
                .path("/widget/api")
                .port(port)
                .storage(widgetDB)
                .schema(loadJsonSchema("/app/config/json/widget.schema.json")))
        .build();
```

(`{"payload.parent_id": ...}` is the Mongo `Searcher`'s query-template convention — the `payload.` prefix addresses a nested payload field in the stored document. **Other adapters differ and are not interchangeable**: the SQL adapters take a `WHERE` fragment (`payload->>'parent_id' = '{{id}}'`, `json_extract(payload, '$.parent_id') = '{{id}}'`), and `memory`/`merksql` take the field bare (`parent_id = '{{id}}'`). A mismatched template returns an empty list rather than an error, and a `getById` test will still pass — so always exercise a vector query against written data. Full table in SKILL.md, "Query templates: the shape is per-adapter".)

If the new entity is the *target* of relations, also add the reverse query (`getByParent`-style) and a resolver field to the parent's schema + `RootConfig`.

## 4. What you get for free

- `POST /widget/api`, `GET /widget/api`, `GET /widget/api/{id}`, `PUT /widget/api/{id}`, `DELETE /widget/api/{id}`, `POST /widget/api/bulk`, `GET /widget/api/bulk?ids=...` — with schema validation, soft delete, versioning
- `POST /widget/graph` GraphQL with temporal reads and federation
- Authorization filtering on every read, via the `Auth` passed to the `Plugin`/`Server`
- `X-Meshql-Created-At`/`X-Meshql-Deleted` response headers on REST `create`/`read`/`update` — the "honesty" as-of fields, automatic, no config needed
- `/widget/api/api-docs` Swagger UI

## 5. Custom validation and side routes

When a write needs business rules beyond JSON Schema, implement `com.meshql.core.Validator` (`CompletableFuture<Boolean> validate(Stash data)`) and pass it to the `Restlette`/`Server` construction alongside (or composed with) the generated `JSONSchemaValidator` — never inside the storage adapter.

For computed read endpoints that don't fit the graph (aggregations, a manifest endpoint), add a plain `HttpServlet` via the extra-servlets overload:

```java
String manifestJson = Files.readString(Paths.get("config", "manifest.json"));
server.init(config, Map.of("/manifest", new ManifestServlet(manifestJson)));
```

(See `examples/farm/src/main/java/com/meshql/examples/farm/Main.java`.)

## 6. Verify

```bash
mvn test -pl <example-module>
```

Smoke test: `POST /widget/api` a payload, then query `getById(id: "...")` — note the response won't contain the id, so discover it via `getAll` or a FK-based query first if needed — and `getById(id: "...", at: <past-millis>)` to confirm temporal behavior.
