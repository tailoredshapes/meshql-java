# MeshQL Development Guide

## Build Commands
- Full build: `mvn clean install`
- Run all tests: `mvn test`
- Run single test: `mvn test -Dtest=ClassName`
- Run specific method: `mvn test -Dtest=ClassName#methodName`

## Code Style Guidelines
- **Java Version**: Java 17
- **Naming**: 
  - Classes: PascalCase (e.g., `CrudHandler`)
  - Methods: camelCase (e.g., `getAuthTokens`)
  - Interfaces: Noun-based (e.g., `Repository`)
- **Formatting**:
  - 4-space indentation
  - Opening braces on same line
- **Architecture**:
  - Interface-based design
  - Composition over inheritance
  - Immutable objects when possible
  - Functional programming style with UnderBar utilities
- **Error Handling**:
  - Use specific exceptions with clear messages
  - Log errors with SLF4J
  - Return appropriate HTTP status codes
  - Format errors consistently

  # MeshQL Integration Guide for AI Agents

This document helps AI agents integrate MeshQL into projects. Add this to your project's CLAUDE.md when using MeshQL as a dependency.

## Overview

MeshQL is a Java 21+ framework for building data APIs with:
- **Dual APIs**: REST (Restlette) and GraphQL (Graphlette)
- **Multiple datastores**: PostgreSQL, MongoDB, SQLite, in-memory
- **Pluggable auth**: JWT (decode-only), Casbin RBAC, NoAuth
- **Federation**: Internal/external resolvers between graphlettes

## Core Concepts

### Envelope
All data is wrapped in an Envelope with metadata:
```java
public record Envelope(
    String id,
    Stash payload,           // User data (key-value map)
    Instant createdAt,
    boolean deleted,
    List<String> authorizedTokens  // For access control
) {}
```

### Key Interfaces
| Interface | Purpose |
|-----------|---------|
| `Repository` | CRUD operations on documents |
| `Searcher` | GraphQL query execution |
| `Auth` | Authentication & authorization |
| `Plugin` | Storage backend factory |
| `Validator` | JSON schema validation |

## Quick Start Pattern

```java
// 1. Create storage config
MongoConfig storageConfig = new MongoConfig();
storageConfig.uri = "mongodb://localhost:27017";
storageConfig.db = "myapp";
storageConfig.collection = "items";

// 2. Create auth
Auth auth = new NoAuth();  // Development
// Auth auth = new JWTSubAuthorizer();  // Production

// 3. Create query configs
List<QueryConfig> singletons = List.of(
    new QueryConfig("getById", "{\"id\": \"{{id}}\"}")
);
List<QueryConfig> vectors = List.of(
    new QueryConfig("getByCategory", "{\"category\": \"{{category}}\"}")
);

// 4. Create RootConfig (GraphQL configuration)
RootConfig rootConfig = new RootConfig(
    singletons,           // Single-object queries
    vectors,              // List queries
    List.of(),            // External singleton resolvers
    List.of(),            // External vector resolvers
    List.of(),            // Internal singleton resolvers
    List.of()             // Internal vector resolvers
);

// 5. Create GraphletteConfig
GraphletteConfig graphlette = new GraphletteConfig(
    "/item/graph",        // Path
    storageConfig,
    "/path/to/schema.graphql",
    rootConfig
);

// 6. Initialize server
Map<String, Plugin> plugins = Map.of("mongo", new MongoPlugin(auth));
Config config = new Config(null, List.of(graphlette), 3033, List.of());
Server server = new Server(plugins);
server.init(config);
```

## Configuration Records

### QueryConfig
Maps GraphQL query names to database query templates:
```java
new QueryConfig("getById", "{\"id\": \"{{id}}\"}}")
new QueryConfig("getByName", "{\"name\": {\"$regex\": \"{{name}}\"}}")
```
Templates use Handlebars syntax. Parameters come from GraphQL query arguments.

### Resolver Configs

**External 1:1 relationship** (HTTP call to another service):
```java
new SingletonResolverConfig(
    "author",                    // Field name in schema
    "author_id",                 // Foreign key in this entity
    "getById",                   // Query on remote service
    URI.create("http://localhost:3033/author/graph")
)
```

**External 1:N relationship**:
```java
new VectorResolverConfig(
    "books",                     // Field name
    "id",                        // This entity's ID field
    "getByAuthor",               // Query on remote service
    URI.create("http://localhost:3033/book/graph")
)
```

**Internal resolvers** (between graphlettes in same server - no HTTP overhead):
```java
new InternalSingletonResolverConfig("coop", "coop_id", "getById", "/coop/graph")
new InternalVectorResolverConfig("hens", "id", "getByCoop", "/hen/graph")
```

### Storage Configs

**MongoDB**:
```java
MongoConfig config = new MongoConfig();
config.uri = "mongodb://localhost:27017";
config.db = "database_name";
config.collection = "collection_name";
```

**PostgreSQL**:
```java
PostgresConfig config = new PostgresConfig();
config.uri = "jdbc:postgresql://localhost:5432/db";
config.table = "table_name";
config.username = "user";
config.password = "pass";
```

## Authentication

### JWT (Production)
Extracts `sub` claim from Bearer token. **Does NOT verify signatures** - assumes upstream gateway validates.

```java
Auth auth = new JWTSubAuthorizer();
// Authorization header: "Bearer eyJ..."
// Returns: ["user-id-from-sub-claim"]
```

### Casbin RBAC
Wraps another Auth to provide role-based access:
```java
Auth jwtAuth = new JWTSubAuthorizer();
CasbinAuth auth = CasbinAuth.create("model.conf", "policy.csv", jwtAuth);
// Returns: ["admin", "editor"] (roles for the user)
```

### NoAuth (Development only)
```java
Auth auth = new NoAuth();  // Always authorizes
```

## GraphQL Schema Pattern

```graphql
scalar Date

type Query {
  getById(id: ID!, at: Float): Item      # Singleton query
  getByCategory(category: String!): [Item]  # Vector query
}

type Item {
  id: ID!
  name: String!
  category: String
  createdAt: Date         # Opt-in "honesty" as-of field — see below
  relatedItems: [Item]    # Resolved via VectorResolver
  author: Author          # Resolved via SingletonResolver
}
```

The `at: Float` parameter enables temporal queries (returns data as of that timestamp).

**`createdAt` is opt-in, not automatic.** Every `Searcher` implementation merges the Envelope's `createdAt` into the result map alongside `id`, so declaring `createdAt: Date` (or `String`) in a type is all it takes to expose it — no resolver code needed. Omit the field from the schema and it's simply never selectable. `deleted` is never exposed in GraphQL; search results already exclude deleted/superseded versions, so there'd be nothing meaningful to show.

## REST API Endpoints

Restlette automatically provides:
| Method | Path | Description |
|--------|------|-------------|
| `POST /` | Create document (returns 201 + payload, no ID in body) |
| `GET /` | List all documents |
| `GET /{id}` | Read document (URL is the identifier) |
| `PUT /{id}` | Update document |
| `DELETE /{id}` | Delete document |
| `POST /bulk` | Batch create |
| `GET /bulk?ids=1,2,3` | Batch read |
| `GET /{id}/versions` | Every version of a document, oldest first |
| `GET /{id}/versions/{token}` | One version, by its address |
| `GET /api-docs` | Swagger UI |

**Note:** REST responses return only the payload — the `Envelope` (with internal ID, timestamps, auth tokens) is deliberately not exposed. The URL is the canonical resource identifier per REST principles. Use GraphQL to discover IDs.

**Version history:** `GET /{id}/versions` answers with
`{"id": ..., "versions": [...]}`, oldest first. A readable entry carries `url`,
`created_at` and `deleted`; one the caller may not read carries `created_at`,
`deleted` and `unauthorized: true`, and no `url` key at all — omitting the entry
would make the history look continuous when it is not. A version's address is a
SHA-256 of what identifies it, so the same version addresses identically on
every adapter and a version URL survives a migration between stores; see
`com.meshql.core.Versions`. A delete appends a tombstone rather than rewriting
what came before, which is why every read resolves the newest version first and
checks `deleted` after.

**Honesty headers:** `create`/`read`/`update` responses carry `X-Meshql-Created-At` (ISO-8601 instant) and `X-Meshql-Deleted` (`true`/`false`) response headers, pulled from the Envelope without widening the JSON body. Use these to show an as-of / "pending" state in the FE instead of silently rendering stale data. Not present on `list` (one header can't represent many envelopes) or `delete` (`Repository.remove` returns only a `Boolean`, no Envelope to read a timestamp from).

## Common Patterns

### Adding a New Entity

1. Create storage config (Mongo/Postgres)
2. Write GraphQL schema file
3. Write JSON schema for REST validation
4. Create QueryConfigs for queries
5. Create ResolverConfigs for relationships
6. Create GraphletteConfig and/or RestletteConfig
7. Add to server Config

### Connecting Entities (Federation)

For `Author -> Books` relationship:

In Author graphlette:
```java
new VectorResolverConfig("books", "id", "getByAuthor",
    URI.create("http://localhost:3033/book/graph"))
```

In Book graphlette (the query being called):
```java
new QueryConfig("getByAuthor", "{\"author_id\": \"{{id}}\"}")
```

### Optimizing with Internal Resolvers

When graphlettes are in the same server, use internal resolvers:
```java
// Instead of:
new VectorResolverConfig("hens", "id", "getByCoop", URI.create("http://..."))

// Use:
new InternalVectorResolverConfig("hens", "id", "getByCoop", "/hen/graph")
```

Benefits: No HTTP overhead, better performance, maintains consistency.

## Project Structure

```
your-project/
├── src/main/java/
│   └── com/example/
│       └── Main.java           # Server initialization
├── config/
│   ├── graph/
│   │   ├── entity1.graphql     # GraphQL schemas
│   │   └── entity2.graphql
│   └── json/
│       ├── entity1.schema.json # JSON schemas for REST
│       └── entity2.schema.json
└── pom.xml
```

## Maven Dependencies

```xml
<dependencies>
    <!-- Core -->
    <dependency>
        <groupId>com.meshql</groupId>
        <artifactId>core</artifactId>
        <version>0.2.0</version>
    </dependency>

    <!-- Server -->
    <dependency>
        <groupId>com.meshql</groupId>
        <artifactId>server</artifactId>
        <version>0.2.0</version>
    </dependency>

    <!-- Choose storage backend(s) -->
    <dependency>
        <groupId>com.meshql</groupId>
        <artifactId>mongo</artifactId>
        <version>0.2.0</version>
    </dependency>
    <!-- or -->
    <dependency>
        <groupId>com.meshql</groupId>
        <artifactId>postgres</artifactId>
        <version>0.2.0</version>
    </dependency>

    <!-- Choose auth -->
    <dependency>
        <groupId>com.meshql</groupId>
        <artifactId>jwt</artifactId>
        <version>0.2.0</version>
    </dependency>
    <!-- optional RBAC -->
    <dependency>
        <groupId>com.meshql</groupId>
        <artifactId>casbin</artifactId>
        <version>0.2.0</version>
    </dependency>
</dependencies>
```

## Performance Notes

- **Indexing > DataLoader batching**: Database indexes provide 100x improvement over batching
- **Internal resolvers**: Use for same-server federation to avoid HTTP overhead
- **Virtual threads**: Jetty 12 uses Project Loom for efficient concurrency
- **Connection pooling**: HikariCP for PostgreSQL, built-in for MongoDB

## Debugging

### GraphQL errors appear in response
```json
{
  "data": null,
  "errors": [{"message": "..."}]
}
```

### Check server logs
MeshQL uses SLF4J. Configure logback for detailed output:
```xml
<logger name="com.meshql" level="DEBUG"/>
```

### Verify query templates
Templates use Handlebars. Test with sample data:
```java
Template t = Handlebars.compile("{\"id\": \"{{id}}\"}");
t.apply(Map.of("id", "test-123"));  // {"id": "test-123"}
```

## Building Applications: Patterns & Pitfalls

This section captures hard-won knowledge from building four example applications. Read this before building anything new with MeshQL.

### REST Identity Model (by design — not a bug)

**REST responses return only the payload, not the internal ID.** This is intentional:

- **The URL is the identifier.** Per REST principles, URLs are sacred — they ARE the system identifier. The ID is exposed via the URL, not the response body.
- **The Envelope is internal.** `Envelope` (with its `id`, `createdAt`, `deleted`, `authorizedTokens`) is a server-side data structure deliberately not exposed to clients, so it can evolve without breaking API contracts.
- **POST returns 201 Created** with just the payload. The resource URL is the canonical reference.

This means: **write via REST, read via GraphQL.** GraphQL exposes `id` because it's a query interface, not a resource interface.

| Operation | Use REST | Use GraphQL |
|-----------|----------|-------------|
| Create/Update/Delete | Yes (POST/PUT/DELETE) | No |
| Read with ID | No (ID not in body) | Yes (`getById`, `getAll`) |
| Frontend data loading | No | Yes |
| Discover IDs after create | No — query GraphQL | Yes |

**Pattern for server-side code that needs IDs after REST create**: POST to REST API, then query GraphQL by a known field (e.g., `getByFarm(id: farmId)`) to discover the MeshQL-generated UUID. See `ProjectionUpdater.createProjection()` in the egg-economy example.

### CDC Pipeline Patterns

When building CDC (Change Data Capture) pipelines:

1. **Phased processing for FK resolution**: Process entities in dependency order. After each phase, query GraphQL to populate an ID mapping cache before processing child entities.

```
Phase 1: Process parents (e.g., customers) → populate customer ID cache via GraphQL
Phase 2: Process entities with parent FKs (e.g., bills) → populate bill ID cache
Phase 3: Process entities with multiple FKs (e.g., payments needing customer + bill IDs)
Phase 4: Continuous consumption of all topics
```

2. **Store legacy IDs in clean schemas**: Add fields like `legacy_acct_id` to JSON/GraphQL schemas so GraphQL queries can map legacy FK → MeshQL UUID.

3. **Stable Kafka consumer group IDs**: NEVER use `System.currentTimeMillis()` in group IDs. Each phase reuses the same group so offsets carry forward. Call `consumer.commitSync()` before closing each phase's consumer.

4. **Debezium envelope differences**:
   - MongoDB CDC: `payload.after` is a **double-encoded JSON string** — parse with `mapper.readTree(afterString)`
   - PostgreSQL CDC: `payload.after` is a **proper JSON object** — use directly

5. **Topic naming**: Debezium topics follow `{prefix}.{db}.{collection}` (MongoDB) or `{prefix}.{schema}.{table}` (PostgreSQL)

### Internal vs External Resolvers

Use **internal resolvers** when graphlettes share a JVM — zero HTTP overhead:

```java
// External (HTTP call — use when services are separate):
.singletonResolver("author", "author_id", "getById", platformUrl + "/author/graph")
.vectorResolver("books", null, "getByAuthor", platformUrl + "/book/graph")

// Internal (in-JVM — use when services share a server):
.internalSingletonResolver("author", "author_id", "getById", "/author/graph")
.internalVectorResolver("books", null, "getByAuthor", "/book/graph")
```

Resolver parameter reference:
- **Singleton**: `(fieldName, foreignKeyField, queryName, targetPath)` — looks up parent's `foreignKeyField` value, calls `queryName(id: value)` on target
- **Vector**: `(fieldName, null, queryName, targetPath)` — passes parent's `id` to `queryName(id: parentId)` on target

### GraphQL Schema Conventions

- Each graphlette defines its **own view** of foreign types (intentional duplication)
- Always include `scalar Date` and the `at: Float` parameter for temporal queries
- Query names: `getById` (singleton), `getAll`/`getByField` (vector)
- MongoDB query templates: `{"payload.field": "{{param}}"}` — note the `payload.` prefix
- Declare `createdAt: Date` on a type to opt into the as-of timestamp — see "GraphQL Schema Pattern" above

### Frontend Stack Conventions

| App Type | Stack | When to Use |
|----------|-------|-------------|
| Customer-facing | React + Vite + Tailwind | SPAs with component composition |
| Admin/operations | Alpine.js + DaisyUI | Server-rendered HTML with interactivity |
| Analytics/reporting | Alpine.js + Chart.js + DaisyUI | Dashboards with data visualization |

All frontends:
- Use inline GraphQL strings (no gql`` or query files)
- Call `/api/{entity}/graph` for reads via `fetch()` (no GraphQL client library)
- Call `/api/{entity}/api` for writes via `fetch()`
- Use nginx reverse proxy with `/api/` prefix stripping

### Docker Compose Conventions

- MongoDB: `mongo:8` (or `mongo:latest`). For CDC, add `--replSet rs0`
- Kafka: `apache/kafka:3.7.0` (KRaft mode, no ZooKeeper)
- Debezium: `debezium/server:2.6` with `snapshot.mode=initial`
- PostgreSQL for CDC: requires `wal_level=logical`, `max_replication_slots=4`
- MeshQL server healthcheck: `curl -f http://localhost:{port}/ready`
- Port conventions: farm=3033, logistics=3044, events=4055, legacy=4066

### nginx Patterns

- Path-based routing: `/track/`, `/admin/`, `/reports/`, `/api/`
- API prefix stripping: `rewrite ^/api/(.*)$ /$1 break;`
- Root redirect: `return 302 /track/;`
- **Gotcha**: Don't use variables in `proxy_pass` with a path — nginx won't rewrite URIs
- **Gotcha**: Don't mix `alias` and `try_files` — use `root` with correct subdirectories

### Seed Data Pattern

REST API doesn't return IDs, so seed scripts must:
1. POST entities via REST
2. Query GraphQL to discover their IDs
3. Use those IDs as foreign keys for dependent entities

Always use `jq -n --arg` for safe JSON construction in shell scripts (avoids quote escaping bugs).

### CI/Testing

- Examples with Docker Compose dependencies (logistics, legacy) skip tests in CI via `<skipTests>${env.CI}</skipTests>` in surefire config
- `@DisabledIfEnvironmentVariable` does NOT work with Cucumber `@Suite` classes — Cucumber's engine ignores JUnit Jupiter condition annotations
- Cucumber BDD tests use picocontainer for DI: `cucumber-picocontainer` dependency, World objects for shared state

## Reference

- Farm example: `examples/farm/` - 4-entity hierarchical federation
- Events example: `examples/events/` - CDC pipeline with MongoDB → Kafka → processor
- Logistics example: `examples/logistics/` - Full-stack with 3 frontend apps
- Legacy example: `examples/legacy/` - Anti-corruption layer with PostgreSQL CDC, internal resolvers, 3 frontend apps
- Tests: `api/graphlette/src/test/` and `api/restlette/src/test/`
- Core interfaces: `core/src/main/java/com/meshql/core/`


## Certification pyramid

Storage agnosticism is proven by the per-store contract tests (repository, searcher, versions, authz). The one end-to-end test is the
egg economy (`cert/.../egg`, `repos/sqlite` `SQLiteEggEconomyTest`), SQLite only: do not wire it to other stores. Restlette options:
`frozen(true)` declares an event (PUT/DELETE answer 405), `writers(List.of(cred))` declares a projection (other writers get 403). The CDC, bridge
and worker in the cert module are harness code; library code must not start or know about them.
