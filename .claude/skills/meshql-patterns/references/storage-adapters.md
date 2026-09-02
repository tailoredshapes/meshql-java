# Storage Adapters: Repository, Searcher, and Certification

A datastore plugs into meshql by implementing three types from `com.meshql.core`: `Repository`, `Searcher`, and a `Plugin` that constructs them. Existing adapters (all under `repos/`): `mongo`, `postgres`, `sqlite` (smallest — best starting reference, ~480 LOC total across `SQLiteRepository`/`SQLiteSearcher`/`SQLitePlugin`/`SQLiteConfig`), `memory`, `merksql`, `ksql`, `rdbms` (shared JDBC helper base for postgres/sqlite).

## The interfaces

```java
public interface Repository {
    Envelope create(Envelope envelope, AuthSession session);
    Optional<Envelope> read(String id, AuthSession session, Instant createdAt);
    List<Envelope> list(AuthSession session);
    Boolean remove(String id, AuthSession session);
    List<Envelope> createMany(List<Envelope> payloads, AuthSession session);
    List<Envelope> readMany(List<String> ids, AuthSession session);
    Map<String, Boolean> removeMany(List<String> ids, AuthSession session);
    List<VersionRef> listVersions(String id, AuthSession session);
    Optional<Envelope> readVersion(String id, String token, AuthSession session);
}

public interface Searcher {
    Stash find(Template queryTemplate, Stash args, AuthSession session, long timestamp);
    List<Stash> findAll(Template queryTemplate, Stash args, AuthSession session, long timestamp);
}

public interface Plugin {
    Searcher createSearcher(StorageConfig config);
    Repository createRepository(StorageConfig config);
    void cleanUp();
    default boolean isHealthy() { return true; }
}
```

No method takes credentials. Every one takes an `AuthSession`: an opaque handle
the adapter cannot read, only ask. On write the adapter calls `session.stamp`
and persists what comes back in the same write as the payload; on read it
fetches, then asks `session.isAuthorized`. A null session fails closed — it is
never "an internal call".

Neither version method has a default implementation, and that absence is the
point: a default returning "unsupported" lets an adapter fall out of conformance
with nothing saying so. Both are one line over the store's own "every version of
this id" query — `Versions.listing` and `Versions.resolve` in
`com.meshql.core` do the ordering, the authorization and the addressing.

`Template` is a compiled Handlebars template (`com.github.jknack.handlebars.Template`); the `Searcher` renders it against `args` to produce a query, per the store's native query language.

## Semantics every adapter MUST honor

These are the contract, enforced by the certification suite:

1. **Append-only versioning.** `create` with an existing `id` appends a new version (newer `createdAt`); it never overwrites. Reads return the latest version per id.
2. **Temporal reads.** `read(id, session, createdAt)` / `find(.., timestamp)` return the latest version with `createdAt <= timestamp`. The canonical query shape is a window per id — latest row per id ordered by `createdAt` descending — applied *after* the `createdAt <= at` filter.
3. **Soft delete.** `remove` appends a tombstone version (`deleted = true`) carrying the payload and the mark of the version it buries. It never rewrites what came before: history is immutable, and a version's address is derived from its content, so rewriting an old version changes where it lives. Every read path therefore resolves the *latest applicable version* first and checks `deleted` after — a `deleted = false` predicate applied before the reduction resolves to the version the tombstone buries and resurrects the record. A temporal read *before* the deletion still returns it. `versions.feature` certifies that a deletion appears in the history as a version of its own.
4. **Ask the plugin on every read.** A record is visible iff `session.isAuthorized(READ, envelope)` says so, and the adapter has no other way to find out: the mark on an envelope is the plugin's, opaque, and cannot become a `WHERE` clause. Fetch, then ask. The rule the default token plugin applies — empty mark means public, wildcard sees everything, a write stamps the caller's own tokens — lives inside that plugin and nowhere else, because eleven adapters once re-derived it eleven ways and nothing detected the difference.
5. **Template rendering.** Searchers render the Handlebars template with `args` into a query **in the store's own query language** — there is no portable template dialect, and `Searcher.compileQuery`'s javadoc (`core/.../Searcher.java:17-20`) says so. `id` always addresses the envelope id column/field. Payload fields differ by adapter: Mongo uses `payload.<field>` dot-notation inside a JSON filter document; the SQL adapters take a `WHERE` fragment carrying the JSONB/JSON path expression (`payload->>'<field>'`, `json_extract(payload, '$.<field>')`) spliced in at `{{{filters}}}`; `memory`/`merksql` take the field name **bare**. A new adapter must document which shape it expects, and should prefer to fail loudly on an unrecognised key — the existing adapters all fail silently with an empty result set, which is the single most common source of "the query returns nothing and there's no error" (see SKILL.md, "Query templates: the shape is per-adapter"). Always parameterize — never splice rendered values into SQL.
6. **Searcher returns payloads, not envelopes.** `find`/`findAll` return the payload `Stash` with `id` merged in, ready for GraphQL resolution. Also merge in `createdAt` (ISO-8601 string, from the Envelope's `createdAt`) right next to `id` — this is what lets a `.graphql` type opt into the "honesty" as-of field (`createdAt: Date`) with zero resolver code, since the graphlette's scalar field resolver is a generic `Stash` lookup. Never merge in `deleted` — search results already exclude deleted/superseded versions, so there's nothing to expose.
7. **Every version has an address.** `listVersions` returns one document's history oldest first — by `created_at` in milliseconds, then undeleted before deleted, then by token — with an entry for every version, including the ones this caller may not read (those carry when it happened and whether it was a deletion, and no token). `readVersion` applies the same authorization as `read`: an unknown token is empty, a token for a version the caller may not read throws `NotAuthorizedException`. The token is a SHA-256 over the id, the timestamp, the deletion flag, the mark and the canonicalized payload, identical in all three implementations, so a version URL survives a migration from one store to another. Do not invent a per-row key for it; see `com.meshql.core.Versions`.
8. **Canonical result ordering: `createdAt`, then `id`.** A result set is returned in insertion order — sorted by the *resolved* version's `createdAt` (millisecond precision), tiebroken by the envelope `id` — so a `limit` truncates a meaningful prefix rather than an arbitrary subset. Adapters that happen to have a monotonic physical sequence (SQLite `rowid`) may use it to decide *which version* of an id resolves inside a millisecond, but never as the primary sort key: Postgres and Mongo have no equivalent, and cross-adapter equivalence is worth more than sub-millisecond fidelity. **Status in this port:** the SQL adapters window per id with `ORDER BY created_at DESC` and do not yet pin the `id` tiebreaker, and `RepositoryCertification`/`SearcherCertification` do not yet contain ordering cases — the Rust port does certify this (`meshql_core::envelope_order` plus dedicated ordering tests). If a design of yours leans on result order, add the cases to the certification suite and make the adapters agree; do **not** reach under an adapter for a physical sequence instead (next section).

## Don't reach underneath the abstraction

The certification suite is not a smoke test; it is the **contract**. Choosing a storage backend is about what you already operate, what your team knows, and what you like — not about capability. There is no practical difference in behaviour between the implementations, and that is precisely what certification exists to guarantee. The property worth protecting: **you should be able to swap the database engine and replay the queue into the new store without any end user noticing a difference.**

So: **never depend on a storage-engine property the certification does not guarantee.**

A recent design picked SQLite specifically so a worker could tail `_id INTEGER PRIMARY KEY` as a CDC cursor — the reasoning being that SQLite serialises writers, so `_id` order is commit order. It works, and it is reaching *underneath* the abstraction. Nothing in `Repository`/`Searcher` exposes a physical row id; nothing in certification promises one exists, or that it is dense, or that it is monotonic with commit order; Postgres and Mongo have no equivalent to offer. The moment that cursor exists, swap-and-replay is gone and the product is welded to one engine — for a property that was never part of the deal.

The replacements, in order of what you were actually trying to do:

- **Need a stable order over results?** Use the canonical order (semantics 7 above: `createdAt`, then `id`), and certify it if this port doesn't yet.
- **Need a durable consumption position?** That's the Kafka consumer group's committed offset, not a row id — and it is checkpointed with the fold state as one artifact (see `references/domain-design.md`).
- **Need events on a log at all?** That's storage-layer CDC (Debezium), or merkql-as-primary-store via the Rust sidecar — not a hand-rolled tail (same file).

## Adding a new adapter

1. New Maven module under `repos/` (add to `repos/pom.xml` and root `pom.xml` modules list), depending on `core`.
2. Constructor convention: a `<Store>Config` (implements/extends `StorageConfig`) carrying connection info, a `<Store>Repository`, a `<Store>Searcher`, and a `<Store>Plugin implements Plugin` that builds them from the config — mirrors `mongo`/`sqlite` so `Main.java` wiring stays copy-paste portable across backends.
3. Implement `Repository` and `Searcher` per the semantics above. `repos/sqlite/src/main/java/com/meshql/repos/sqlite/` is the smallest complete reference.
4. **Certify.** Extend the shared abstract test classes from `repos/certification` (`com.meshql.repos.certification.RepositoryCertification`, `SearcherCertification`) — implement `init()` to wire your adapter against a throwaway/temp instance (see `SQLiteRepositoryCertificationTest`/`SQLiteSearcherCertificationTest` for the pattern: temp file DB, unique table name per test, `NoAuth`). Also wire the shared Cucumber BDD suite (`*CucumberCertificationTest`, `*FarmCertificationTest` in each adapter's `src/test`) for end-to-end coverage: create/read/list/remove, bulk ops, temporal reads, soft delete, token visibility. An adapter is not done until certification passes: `mvn test -pl repos/<store>`.

## What does NOT belong in an adapter

- Business validation, defaults, side effects → a `Validator` implementation passed into `Restlette`/`Server`, or extra servlet routes.
- Cross-entity joins → graphlette resolvers (federation, see `references/federation.md`).
- Aggregations/read models → projection entities materialized by a worker (see `references/domain-design.md`).

Adapters translate Envelope semantics to a store. Nothing else.
