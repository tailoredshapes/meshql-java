# MeshQL Certification Tests

This module provides BDD-style certification tests for MeshQL database plugins using Cucumber.

## Overview

The certification tests use the **EXACT SAME** feature files from [meshobj](https://github.com/tsmarsh/meshobj) via symbolic links, ensuring consistent behavior contracts across both TypeScript and Java implementations.

## Test Coverage

The certification suite validates:

### Repository Contract (`features/integration/repository.feature`)
- Creating envelopes with auto-generated IDs
- Reading envelopes by ID (single and multiple)
- Listing all envelopes
- Removing envelopes (single and multiple)
- Temporal versioning (multiple versions of the same ID)
- Point-in-time queries

### Searcher Contract (`features/integration/searcher.feature`)
- Finding singletons by ID
- Finding by custom fields (name, type, etc.)
- Finding all records matching criteria
- Handling non-existent records gracefully
- Empty query parameter handling

## Usage for Plugin Developers

To certify your database plugin implementation:

### 1. Add Dependency

Add the cert module as a test dependency in your plugin's `pom.xml`:

```xml
<dependency>
    <groupId>com.meshql</groupId>
    <artifactId>cert</artifactId>
    <version>0.2.0</version>
    <scope>test</scope>
</dependency>
```

### 2. Implement Plugin Interface

Your plugin must implement `com.meshql.core.Plugin`:

```java
public interface Plugin {
    Searcher createSearcher(StorageConfig config);
    Repository createRepository(StorageConfig config, Auth auth);
    void cleanUp();
}
```

### 3. Provide Test Queries

Create `SearcherTestTemplates` with Handlebars templates for your database query language:

```java
// For SQL databases
Template findById = Handlebars.compile("SELECT * FROM envelopes WHERE id = '{{id}}'");

// For MongoDB
Template findById = Handlebars.compile("{ \"id\": \"{{id}}\" }");
```

### 4. Create Test Hooks

Extend the `Hooks` class to configure your plugin:

```java
package com.example.myplugin;

import com.meshql.cert.Hooks;
import com.meshql.cert.IntegrationWorld;
import com.meshql.cert.SearcherTestTemplates;
import com.github.jknack.handlebars.Handlebars;
import io.cucumber.java.Before;

public class MyPluginHooks extends Hooks {
    public MyPluginHooks(IntegrationWorld world) {
        super(world);
    }

    @Before
    @Override
    public void setUp() {
        super.setUp(); // Sets up tokens and auth

        // Configure your plugin
        world.storageConfig = new StorageConfig("my-database-type");
        world.plugin = new MyDatabasePlugin();

        // Provide query templates
        Handlebars handlebars = new Handlebars();
        world.templates = new SearcherTestTemplates(
            handlebars.compileInline("SELECT * FROM envelopes WHERE id = '{{id}}'"),
            handlebars.compileInline("SELECT * FROM envelopes WHERE payload->>'name' = '{{id}}'"),
            handlebars.compileInline("SELECT * FROM envelopes WHERE payload->>'type' = '{{id}}'"),
            handlebars.compileInline("SELECT * FROM envelopes WHERE payload->>'name' = '{{name}}' AND payload->>'type' = '{{type}}'")
        );
    }
}
```

### 5. Create Test Runner

```java
package com.example.myplugin;

import org.junit.platform.suite.api.*;
import static io.cucumber.junit.platform.engine.Constants.*;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features/integration")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.meshql.cert.steps,com.example.myplugin")
@ConfigurationParameter(key = PLUGIN_PROPERTY_NAME, value = "pretty, json:target/cucumber-reports/cucumber.json")
public class CertificationTest {
}
```

### 6. Run Tests

```bash
mvn test
```

## Architecture

```
cert/
├── src/
│   ├── main/java/           (empty - this is a test-only module)
│   └── test/
│       ├── java/com/meshql/cert/
│       │   ├── IntegrationWorld.java      # Shared state
│       │   ├── SearcherTestTemplates.java # Query template contract
│       │   ├── Hooks.java                 # Base test setup
│       │   ├── CucumberTest.java          # Test runner
│       │   └── steps/
│       │       ├── CommonSteps.java       # Timestamp/wait utilities
│       │       ├── RepositorySteps.java   # CRUD operations
│       │       └── SearcherSteps.java     # Query operations
│       └── resources/
│           └── features/                   # Symlink to meshobj features
│               ├── integration/
│               │   ├── repository.feature
│               │   └── searcher.feature
│               └── egg-economy/
│                   └── egg_economy.feature   (symlink into the contract submodule)
```

## The end-to-end certification: the egg economy

meshql is storage agnostic, and the storage certifications above (repository, searcher,
versions, authorization, per store) are what prove it. The test pyramid therefore has
one end-to-end test, not one per store: `egg_economy.feature` (the contract's
`features/proposed/egg-economy/`, event storm in the contract's `specs/egg-economy.md`),
run against **SQLite only**, the cheapest store (`repos/sqlite`,
`SQLiteEggEconomyTest`). It replaced the farm e2e (`farm.feature`, which also ran per store).

It certifies surfaces: an event is **frozen** (PUT and DELETE answer 405), a projection
names its **writers** (every other credential gets 403, the worker's can write), and the
graph federates across the 14 entities and answers `at:`. The deployment is
`com.meshql.cert.egg.EggEconomyEnv` with the schemas under `src/main/resources/egg-economy/`.

The CDC, the bridge and the worker are not meshql's business and not under test.
The harness supplies the quickest that works (`EventBridge`: a poller that hands each
stored lay report to `HenWorker`, which folds a hen's lay reports into a
`hen_productivity` through the restlette with the worker's credential). The library
neither starts nor knows about them.

`frozen` and `writers` are restlette options (`RestletteConfig.builder().frozen(true)`,
`.writers(List.of("egg-worker"))`). A credential is the `sub` of the bearer JWT (or the
bearer token itself); empty `writers` means anyone may write; reads are never restricted
by it. It is independent of the auth plugin.

Run it: `mvn -pl repos/sqlite -am test -Dtest=SQLiteEggEconomyTest -Dsurefire.failIfNoSpecifiedTests=false`

## Example Implementations

See existing MeshQL repository plugins for reference:
- `repos/postgres` - PostgreSQL implementation
- `repos/mongo` - MongoDB implementation
- `repos/memory` - In-memory implementation

## Feature Files

The feature files are **symbolically linked** from meshobj to ensure both projects test the same contracts:

```bash
$ ls -la src/test/resources/features
lrwxrwxrwx features -> /path/to/meshobj/core/cert/features
```

This guarantees that any updates to the test specifications in meshobj automatically apply to meshql.

## Contributing

When adding new test scenarios:
1. Add them to meshobj's feature files first
2. The changes will automatically be available here via the symlink
3. Update step definitions in both projects as needed
