---
title: Configuration
layout: default
parent: Reference
nav_order: 1
---

# Configuration Reference

MeshQL is configured through a hierarchy of Java records with builder support. All configuration is explicit — no annotations, classpath scanning, or convention-over-configuration.

---

## Config (Top Level)

The root configuration that defines the entire server:

```java
Config config = Config.builder()
    .graphlette(graphletteConfig)    // Add a GraphQL endpoint
    .restlette(restletteConfig)      // Add a REST endpoint
    .port(3033)                       // Server port
    .build();
```

| Field | Type | Description |
|:------|:-----|:------------|
| `graphlettes` | `List<GraphletteConfig>` | All GraphQL endpoints |
| `restlettes` | `List<RestletteConfig>` | All REST endpoints |
| `port` | `int` | HTTP server port |
| `casbinParams` | `List<String>` | Optional `[modelPath, policyPath]`. Set it and the server enables authorization: `CasbinAuth` over `JWTSubAuthorizer`. Omit it and every caller is authorized (`NoAuth`). Malformed or unreadable paths fail server startup. |

---

## GraphletteConfig

Defines a single GraphQL endpoint (one meshobj's graph API):

```java
GraphletteConfig graphlette = GraphletteConfig.builder()
    .path("/hen/graph")                           // URL path
    .storage(mongoConfig)                          // Storage backend
    .schema("/config/graph/hen.graphql")           // GraphQL schema file
    .rootConfig(rootConfig)                        // Queries and resolvers
    .build();
```

| Field | Type | Description |
|:------|:-----|:------------|
| `path` | `String` | URL path for this endpoint (e.g., `/hen/graph`) |
| `storage` | `StorageConfig` | Storage backend configuration |
| `schema` | `String` | Path to `.graphql` schema file |
| `rootConfig` | `RootConfig` | Query and resolver definitions |

---

## RestletteConfig

Defines a single REST endpoint (one meshobj's CRUD API):

```java
RestletteConfig restlette = RestletteConfig.builder()
    .path("/hen/api")                    // URL path
    .port(3033)                          // Server port
    .storage(mongoConfig)                // Storage backend
    .schema(jsonSchema)                  // JSON Schema for validation
    .build();
```

| Field | Type | Description |
|:------|:-----|:------------|
| `path` | `String` | URL path for this endpoint (e.g., `/hen/api`) |
| `port` | `int` | Server port |
| `storage` | `StorageConfig` | Storage backend configuration |
| `schema` | `JsonSchema` | JSON Schema (networknt) for input validation |
| `tokens` | `List<String>` | Fixed auth tokens for this endpoint |

---

## RootConfig

Defines all queries and federation resolvers for a graphlette:

```java
RootConfig rootConfig = RootConfig.builder()
    // Queries that return a single object
    .singleton("getById", "{\"id\": \"{{id}}\"}")
    .singleton("getByName", "{\"payload.name\": \"{{name}}\"}")

    // Queries that return a list
    .vector("getByCoop", "{\"payload.coop_id\": \"{{id}}\"}")
    .vector("getByCategory", "{\"payload.category\": \"{{category}}\"}")

    // External resolvers (HTTP)
    .singletonResolver("coop", "coop_id", "getById",
        "http://localhost:3033/coop/graph")
    .vectorResolver("layReports", null, "getByHen",
        "http://localhost:3033/lay_report/graph")

    // Internal resolvers (same JVM, no HTTP)
    .internalSingletonResolver("coop", "coop_id", "getById", "/coop/graph")
    .internalVectorResolver("layReports", null, "getByHen", "/lay_report/graph")

    .build();
```

| Field | Type | Description |
|:------|:-----|:------------|
| `singletons` | `List<QueryConfig>` | Queries returning a single object |
| `vectors` | `List<QueryConfig>` | Queries returning a list |
| `singletonResolvers` | `List<SingletonResolverConfig>` | External 1:1 resolvers |
| `vectorResolvers` | `List<VectorResolverConfig>` | External 1:N resolvers |
| `internalSingletonResolvers` | `List<InternalSingletonResolverConfig>` | In-process 1:1 resolvers |
| `internalVectorResolvers` | `List<InternalVectorResolverConfig>` | In-process 1:N resolvers |
| `dataLoaderEnabled` | `boolean` | Enable DataLoader batching (default: `true`) |

---

## QueryConfig

Maps a GraphQL query name to a database query template:

```java
new QueryConfig("getById", "{\"id\": \"{{id}}\"}")
new QueryConfig("getByName", "{\"payload.name\": {\"$regex\": \"{{name}}\"}}")
```

| Field | Type | Description |
|:------|:-----|:------------|
| `name` | `String` | GraphQL query name (must match schema) |
| `query` | `String` | Handlebars template for the database query |

Templates use [Handlebars](https://jknack.github.io/handlebars.java/) syntax. Parameters come from GraphQL query arguments.

### Template Examples

```
# Exact match by ID
{\"id\": \"{{id}}\"}

# Match on payload field
{\"payload.name\": \"{{name}}\"}

# Foreign key lookup
{\"payload.coop_id\": \"{{id}}\"}

# Regex search (MongoDB)
{\"payload.name\": {\"$regex\": \"{{name}}\"}}
```

---

## Resolver Configs

### SingletonResolverConfig (External 1:1)

Resolves a field by making an HTTP call to another graphlette:

```java
new SingletonResolverConfig(
    "coop",           // Field name in GraphQL schema
    "coop_id",        // Foreign key field in this entity's payload
    "getById",        // Query name on the target graphlette
    URI.create("http://localhost:3033/coop/graph")
)
```

### VectorResolverConfig (External 1:N)

```java
new VectorResolverConfig(
    "hens",           // Field name in GraphQL schema
    "id",             // This entity's ID field (passed as parameter)
    "getByCoop",      // Query name on the target graphlette
    URI.create("http://localhost:3033/hen/graph")
)
```

### InternalSingletonResolverConfig (In-Process 1:1)

```java
new InternalSingletonResolverConfig(
    "coop",           // Field name
    "coop_id",        // Foreign key field
    "getById",        // Query name
    "/coop/graph"     // Path of target graphlette (same server)
)
```

### InternalVectorResolverConfig (In-Process 1:N)

```java
new InternalVectorResolverConfig(
    "hens",           // Field name
    "id",             // This entity's ID field
    "getByCoop",      // Query name
    "/hen/graph"      // Path of target graphlette
)
```

---

## Storage Configs

### MongoConfig

```java
MongoConfig config = new MongoConfig();
config.uri = "mongodb://localhost:27017";
config.db = "myapp";
config.collection = "items";
```

### PostgresConfig

```java
PostgresConfig config = new PostgresConfig();
config.uri = "jdbc:postgresql://localhost:5432/myapp";
config.table = "items";
config.username = "user";
config.password = "pass";
```

### SQLiteConfig

```java
SQLiteConfig config = new SQLiteConfig();
// Configuration via constructor or fields
```

### Plugin Registration

```java
Map<String, Plugin> plugins = new HashMap<>();
plugins.put("mongo", new MongoPlugin(auth));
plugins.put("postgres", new PostgresPlugin(auth));
plugins.put("sqlite", new SQLitePlugin(auth));
plugins.put("memory", new InMemoryPlugin(auth));
```

The `type` field on `StorageConfig` must match the key in the plugins map.
