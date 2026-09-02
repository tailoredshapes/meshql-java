# meshql-java

The Java implementation of **[meshql](https://git.tildarc.com/tailoredshapes/meshql)**.

What meshql *is* — the model, the envelope, temporal reads, honesty, how
authorization works, and the Gherkin contract every storage plugin is certified
against — is defined once in the [contract repository][contract], which this
repository runs as a git submodule at `cert/src/test/resources/features/contract`.
Read that first. This README is the *how*: what is specific to running meshql on
the JVM.

[contract]: https://git.tildarc.com/tailoredshapes/meshql

## What is specific to Java

- **Jetty 12 with virtual threads** (Project Loom). Each request runs on a
  lightweight virtual thread, so federation calls and database queries do not
  block a platform thread.
- **Swagger/OpenAPI** is generated on every restlette. The other two
  implementations do not do this.
- **Mesher**, a CLI that points at a legacy database and generates a meshql
  anti-corruption layer, lives here and nowhere else.
- **A Groovy DSL** is available as an alternative to the builder configuration.
- **Storage plugins**: PostgreSQL, MongoDB, SQLite, ksqlDB, MerkSQL, and
  in-memory.

### Where this implementation diverges

- **No `/<id>/versions` endpoint.** Rust exposes the version history of a
  document over REST; Java does not yet. Nothing in the contract certifies it,
  which is why the gap went unnoticed.

## Examples

Seven complete applications, each demonstrating different aspects of MeshQL:

| Example | Entities | Key Demonstration |
|:--------|:---------|:-----------------|
| [**Farm**](examples/farm/) | Farm, Coop, Hen, LayReport | 4-level hierarchical federation, performance benchmarks (indexing beats DataLoader 100x) |
| [**Events**](examples/events/) | Event, ProcessedEvent | CDC pipeline with Debezium + Kafka, event enrichment processor |
| [**Logistics**](examples/logistics/) | Warehouse, Shipment, Package, TrackingUpdate | 3 frontend apps (React, Alpine.js, Chart.js), Docker + Kubernetes deployment |
| [**Legacy**](examples/legacy/) | Customer, MeterReading, Bill, Payment | Anti-corruption layer over legacy PostgreSQL, internal resolvers, CDC transformation |
| [**Egg Economy**](examples/egg-economy/) | 13 entities (5 actors, 5 events, 3 projections) | Event sourcing, materialized projections, MongoDB sharding, 3 frontend apps |
| [**Egg Economy SAP**](examples/egg-economy-sap/) | Same 13 entities | Anti-corruption layer over SAP-style database — transitional architecture for vendor replacement |
| [**Egg Economy Salesforce**](examples/egg-economy-salesforce/) | Same 13 entities | Anti-corruption layer over Salesforce-style database — platform migration without big-bang cutover |

The egg-economy variants demonstrate the same clean domain served three ways: native, from SAP, and from Salesforce — proving that downstream applications and frontends remain unchanged regardless of the data source.

Each example runs with `docker compose up` and includes full test suites.

## Mesher: Code Generation from Legacy Databases

[**Mesher**](mesher/) automates the creation of anti-corruption layer services. Point it at a PostgreSQL database and it generates a complete MeshQL project — the same structure as the legacy example, but for your database.

```bash
java -jar mesher.jar run \
    --jdbc-url jdbc:postgresql://localhost:5432/my_legacy_db \
    --project-name my-service --output ./generated
```

Introspects the schema, uses Claude to design clean names and transformations, generates all code and infrastructure. See the [Mesher README](mesher/README.md) for details.

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.8+
- Docker (for examples and integration tests)

### Build

```bash
mvn clean install
```

### Run an Example

```bash
cd examples/farm
docker compose up
# GraphQL: http://localhost:3033/farm/graph
# REST:    http://localhost:3033/farm/api
```

## Authentication

MeshQL provides pluggable authentication designed for enterprise deployment behind API gateways:

### JWT (`JWTSubAuthorizer`)

Extracts the `sub` claim from Bearer tokens. **Does not verify signatures** — assumes an upstream gateway (Kong, Istio, etc.) has already validated the token.

```java
Auth auth = new JWTSubAuthorizer();
```

### Casbin RBAC (`CasbinAuth`)

Wraps JWT to provide role-based access control:

```java
Auth jwtAuth = new JWTSubAuthorizer();
CasbinAuth auth = CasbinAuth.create("model.conf", "policy.csv", jwtAuth);
// Returns roles: ["admin", "editor"] instead of raw user ID
```

### NoAuth (Development)

```java
Auth auth = new NoAuth();  // Always authorizes
```

## Server Architecture

MeshQL runs on Jetty 12 with virtual threads (Project Loom):

- Each request runs on a lightweight virtual thread
- Thousands of concurrent requests without thread pool exhaustion
- I/O-bound workloads (database queries, federation HTTP calls) don't block platform threads

## Project Structure

```
meshql/
├── core/           # Interfaces: Repository, Searcher, Auth, Plugin, Validator
├── api/
│   ├── graphlette/ # GraphQL endpoint implementation
│   └── restlette/  # REST endpoint implementation
├── auth/
│   ├── jwt/        # JWT sub-claim extraction
│   ├── casbin/     # Casbin RBAC wrapper
│   └── noop/       # NoAuth for development
├── repositories/
│   ├── mongo/      # MongoDB plugin
│   ├── postgres/   # PostgreSQL plugin (HikariCP)
│   ├── sqlite/     # SQLite plugin
│   └── mem/        # In-memory plugin
├── server/         # Jetty 12 server assembly
├── mesher/         # CLI: generate anti-corruption layers from legacy DBs
└── examples/
    ├── farm/                  # Hierarchical federation
    ├── farm-groovy-dsl/       # Groovy DSL for config-as-text
    ├── events/                # CDC pipeline
    ├── logistics/             # Full-stack application
    ├── legacy/                # Anti-corruption layer
    ├── egg-economy/           # Event sourcing + projections + 3 frontends
    ├── egg-economy-sap/       # Same domain, SAP as legacy source
    └── egg-economy-salesforce/ # Same domain, Salesforce as legacy source
```

## Also See: Groovy DSL Configuration

The [**Groovy DSL**](examples/farm-groovy-dsl/) provides an alternative way to write MeshQL configs — same `Config` records, but in a grammar that non-engineers can read and edit.

Code changes ship monthly through QA as part of a normal release. Config changes — a new query, a resolver between two services, a renamed collection — can follow a faster path: edited in UAT by authorised domain experts, tested against production-shaped data, and promoted independently of the release cycle. The DSL makes this possible because the configs are constrained text files, not compiled Java. They can be versioned, diffed, reviewed, and rolled back by people who have never opened an IDE.

See the [farm-groovy-dsl README](examples/farm-groovy-dsl/README.md) for the full grammar reference and environment model.

## Documentation

Full documentation at [tsmarsh.github.io/meshql](https://tsmarsh.github.io/meshql/).

## License

[Business Source License 1.1](LICENSE.template)
