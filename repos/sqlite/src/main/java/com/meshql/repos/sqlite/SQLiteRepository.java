package com.meshql.repos.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.uuid.Generators;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.NotAuthorizedException;
import com.meshql.core.Operation;
import com.meshql.core.Repository;
import com.meshql.core.VersionRef;
import com.meshql.core.Versions;
import com.meshql.repositories.rdbms.Converters;
import com.tailoredshapes.stash.Stash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * SQLite storage.
 *
 * <p>No query here carries a visibility predicate, and no method here holds
 * credentials. The auth plugin's mark is opaque, so it cannot become a
 * {@code WHERE} clause; the repository fetches and then asks the session. The
 * mark is written in the same statement as the payload, so a crash can never
 * leave a record present with its authorization missing.
 */
public class SQLiteRepository implements Repository {
    private static final Logger log = LoggerFactory.getLogger(SQLiteRepository.class);
    private final Connection connection;
    private final String table;
    private final List<String> indexedFields;
    private final ObjectMapper objectMapper;

    public SQLiteRepository(Connection connection, String table) {
        this(connection, table, Collections.emptyList());
    }

    public SQLiteRepository(Connection connection, String table, List<String> indexedFields) {
        this.connection = connection;
        this.table = table;
        this.indexedFields = indexedFields;
        this.objectMapper = new ObjectMapper();
    }

    /** A missing session is never an internal call. It is a caller who may see nothing. */
    private static boolean allows(AuthSession session, Operation operation, Envelope envelope) {
        return session != null && session.isAuthorized(operation, envelope);
    }

    public void initialize() {
        String createTableSQL = String.format(
            "CREATE TABLE IF NOT EXISTS %s (" +
            "    _id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "    id TEXT, " +
            "    payload TEXT NOT NULL, " +
            "    created_at INTEGER DEFAULT (CAST((julianday('now') - 2440587.5)*86400000 AS INTEGER)), " +
            "    deleted INTEGER DEFAULT 0, " +
            "    auth TEXT, " +
            "    UNIQUE (id, created_at)" +
            ");", table);

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(createTableSQL);
            for (String field : indexedFields) {
                String colName = field.replace("$.", "").replace(".", "_");
                String indexSQL = String.format(
                    "CREATE INDEX IF NOT EXISTS idx_%s_%s ON %s(json_extract(payload, '%s'))",
                    table, colName, table, field);
                stmt.execute(indexSQL);
                log.debug("Created index idx_{}_{} on {}", table, colName, table);
            }
        } catch (SQLException e){
            log.error("Failed to create SQLite table " + table);
            throw new RuntimeException(e);
        }
    }

    private Envelope rowToEnvelope(ResultSet rs) throws SQLException {
        return new Envelope(
                rs.getString("id"),
                Stash.parseJSON(rs.getString("payload")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                (rs.getInt("deleted") == 1),
                Converters.parseAuth(rs.getString("auth"))
        );
    }

    @Override
    public Envelope create(Envelope doc, AuthSession session) {
        if (session == null) {
            throw new NotAuthorizedException("A write needs a session; there is no unset session");
        }
        Envelope stamped = session.stamp(doc);
        if (!session.isAuthorized(Operation.CREATE, stamped)) {
            throw new NotAuthorizedException("Not authorized to create " + doc.id());
        }
        return insert(stamped);
    }

    private Envelope insert(Envelope stamped) {
        // Don't specify created_at - let the database DEFAULT handle it
        // This ensures timestamps are set atomically by the database.
        // The mark goes in with the payload: one statement, so authorization is
        // never a second write that can fail on its own.
        String query = String.format(
            "INSERT INTO %s (id, payload, auth, deleted) " +
            "VALUES (?, ?, ?, ?);", table);

        String id = stamped.id() != null ? stamped.id() : Generators.timeBasedGenerator().generate().toString();

        try {
            PreparedStatement pstmt = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
            pstmt.setString(1, id);
            pstmt.setString(2, objectMapper.writeValueAsString(stamped.payload()));
            pstmt.setString(3, Converters.writeAuth(stamped.auth()));
            pstmt.setInt(4, stamped.deleted() ? 1 : 0);

            pstmt.executeUpdate();

            try (ResultSet generatedKeys = pstmt.getGeneratedKeys()) {
                if (generatedKeys.next()) {
                    long lastId = generatedKeys.getLong(1);

                    try (PreparedStatement selectStmt = connection.prepareStatement(
                            String.format("SELECT * FROM %s WHERE _id = ?", table))) {
                        selectStmt.setLong(1, lastId);

                        try (ResultSet rs = selectStmt.executeQuery()) {
                            if (rs.next()) {
                                return rowToEnvelope(rs);
                            }
                        }
                    }
                }
            }

            return null;
        } catch (SQLException e) {
            if (e.getMessage().contains("SQLITE_CONSTRAINT")) {
                try {
                    TimeUnit.MILLISECONDS.sleep(2);
                    return insert(stamped);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted while retrying create operation", ex);
                }
            }
            throw new RuntimeException("Error creating document", e);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error serializing JSON", e);
        }
    }

    @Override
    public List<Envelope> createMany(List<Envelope> payloads, AuthSession session) {
        if (session == null) {
            throw new NotAuthorizedException("A write needs a session; there is no unset session");
        }
        List<Envelope> created = new ArrayList<>();

        try {
            connection.setAutoCommit(false);

            for (Envelope envelope : payloads) {
                created.add(create(envelope, session));
            }

            connection.commit();
            connection.setAutoCommit(true);

            return created;
        } catch (SQLException e) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                throw new RuntimeException("Error rolling back transaction", ex);
            }
            throw new RuntimeException("Error creating multiple documents", e);
        }
    }

    @Override
    public Optional<Envelope> read(String id, AuthSession session, Instant at) {
        return latestAt(id, at).filter(e -> allows(session, Operation.READ, e));
    }

    /**
     * The latest live version of an id at an instant, before anyone is asked about it.
     *
     * <p>The as-of cutoff is applied before the latest-version reduction and the
     * {@code deleted} flag after it. A removal appends a tombstone rather than
     * rewriting the rows before it, so a {@code deleted = 0} predicate in the query
     * would resolve to the version the tombstone buries and resurrect the record.
     */
    private Optional<Envelope> latestAt(String id, Instant at) {
        long atMs = at != null ? at.toEpochMilli() : System.currentTimeMillis();

        String query = String.format(
            "SELECT * FROM %s " +
            "WHERE id = ? AND created_at <= ? " +
            "ORDER BY created_at DESC, _id DESC LIMIT 1;", table);

        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, id);
            pstmt.setLong(2, atMs);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Envelope latest = rowToEnvelope(rs);
                    return latest.deleted() ? Optional.empty() : Optional.of(latest);
                }
            }

            return Optional.empty();
        } catch (SQLException e) {
            throw new RuntimeException("Error reading document", e);
        }
    }

    /** Every stored version of an id, live or buried, in no particular order. */
    private List<Envelope> versionsOf(String id) {
        String query = String.format("SELECT * FROM %s WHERE id = ?;", table);

        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            pstmt.setString(1, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                List<Envelope> versions = new ArrayList<>();
                while (rs.next()) {
                    versions.add(rowToEnvelope(rs));
                }
                return versions;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error listing versions", e);
        }
    }

    @Override
    public List<Envelope> readMany(List<String> ids, AuthSession session) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        long now = System.currentTimeMillis();

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                placeholders.append(", ");
            }
            placeholders.append("?");
        }

        String query = String.format(
            "SELECT * FROM %s " +
            "WHERE id IN (%s) AND created_at <= ? " +
            "ORDER BY created_at DESC, _id DESC;",
            table, placeholders);

        try (PreparedStatement pstmt = connection.prepareStatement(query)) {
            int paramIndex = 1;

            for (String id : ids) {
                pstmt.setString(paramIndex++, id);
            }

            pstmt.setLong(paramIndex, now);

            try (ResultSet rs = pstmt.executeQuery()) {
                Set<String> seen = new HashSet<>();
                List<Envelope> envelopes = new ArrayList<>();

                while (rs.next()) {
                    String id = rs.getString("id");
                    if (!seen.contains(id)) {
                        seen.add(id);
                        // The first row for an id is its latest version. If that
                        // version is the tombstone the record is gone, and the
                        // versions behind it are not a fallback.
                        Envelope envelope = rowToEnvelope(rs);
                        if (!envelope.deleted() && allows(session, Operation.READ, envelope)) {
                            envelopes.add(envelope);
                        }
                    }
                }

                return envelopes;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error reading multiple documents", e);
        }
    }

    /**
     * Bury a document under a tombstone, if the plugin lets this caller remove it.
     *
     * <p>A removal is a version, not an erasure. The rows that preceded it stay
     * exactly as written, so their addresses keep resolving, and the tombstone
     * keeps the mark of the record it buries: who may hear about the deletion is
     * who could read the thing deleted.
     */
    @Override
    public Boolean remove(String id, AuthSession session) {
        Optional<Envelope> buried = latestAt(id, Instant.now())
                .filter(e -> allows(session, Operation.REMOVE, e));
        if (buried.isEmpty()) {
            return false;
        }

        Envelope living = buried.get();
        insert(new Envelope(id, living.payload(), Instant.now(), true, living.auth()));
        return true;
    }

    @Override
    public Map<String, Boolean> removeMany(List<String> ids, AuthSession session) {
        Map<String, Boolean> result = new HashMap<>();

        try {
            connection.setAutoCommit(false);

            for (String id : ids) {
                result.put(id, remove(id, session));
            }

            connection.commit();
            connection.setAutoCommit(true);

            return result;
        } catch (SQLException e) {
            try {
                connection.rollback();
                connection.setAutoCommit(true);
            } catch (SQLException ex) {
                throw new RuntimeException("Error rolling back transaction", ex);
            }
            throw new RuntimeException("Error removing multiple documents", e);
        }
    }

    @Override
    public List<Envelope> list(AuthSession session) {
        String query = String.format(
            "SELECT * FROM %s ORDER BY created_at DESC, _id DESC;", table);

        try (PreparedStatement pstmt = connection.prepareStatement(query);
             ResultSet rs = pstmt.executeQuery()) {

            Set<String> seen = new HashSet<>();
            List<Envelope> envelopes = new ArrayList<>();

            while (rs.next()) {
                String id = rs.getString("id");
                if (!seen.contains(id)) {
                    seen.add(id);
                    // Resolve first, then drop the removed - see readMany.
                    Envelope envelope = rowToEnvelope(rs);
                    if (!envelope.deleted() && allows(session, Operation.READ, envelope)) {
                        envelopes.add(envelope);
                    }
                }
            }

            return envelopes;
        } catch (SQLException e) {
            throw new RuntimeException("Error listing documents", e);
        }
    }

    @Override
    public List<VersionRef> listVersions(String id, AuthSession session) {
        return Versions.listing(versionsOf(id), session);
    }

    @Override
    public Optional<Envelope> readVersion(String id, String token, AuthSession session) {
        return Versions.resolve(versionsOf(id), token, session);
    }
}
