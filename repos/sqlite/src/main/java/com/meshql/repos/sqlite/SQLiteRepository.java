package com.meshql.repos.sqlite;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.uuid.Generators;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import com.tailoredshapes.stash.Stash;
import com.tailoredshapes.underbar.ocho.UnderBar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class SQLiteRepository implements Repository {
    private static final Logger log = LoggerFactory.getLogger(SQLiteRepository.class);
    private final Connection connection;
    private final String table;
    private final List<String> indexedFields;
    private final ObjectMapper objectMapper;

    /**
     * The visibility predicate, as SQL.
     *
     * <p>An envelope with no authorized tokens is public, so it stays readable by a
     * caller holding credentials of their own. That is the same rule
     * {@link Auth#tokensVisibleTo} applies on the Searcher side, and dropping it here
     * made the two surfaces disagree: a record written without credentials came
     * back through the graphlette and returned 404 through the restlette.
     *
     * <p>The predicate applies to every caller, including one presenting no tokens.
     * It used to be appended only when the caller held tokens, which gave an
     * anonymous request an unfiltered read of everything. A caller that needs to see
     * everything holds {@link Auth#WILDCARD_TOKEN}, and {@link #visibilityTokens}
     * carries that same token on the envelope side.
     *
     * <p>Takes one bind parameter: the tokens to match as a JSON array.
     */
    private static final String VISIBLE_TO_CALLER =
        "AND (authorized_tokens IS NULL " +
        "     OR json_array_length(authorized_tokens) = 0 " +
        "     OR EXISTS (SELECT 1 FROM json_each(authorized_tokens) " +
        "                WHERE value IN (SELECT value FROM json_each(?)))) ";

    /**
     * The tokens the predicate matches against: the caller's own, plus the wildcard
     * so an envelope marked readable by everyone is matched too.
     */
    private static List<String> visibilityTokens(List<String> tokens) {
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        if (tokens != null) {
            matched.addAll(tokens);
        }
        matched.add(Auth.WILDCARD_TOKEN);
        return List.copyOf(matched);
    }

    public SQLiteRepository(Connection connection, String table) {
        this(connection, table, Collections.emptyList());
    }

    public SQLiteRepository(Connection connection, String table, List<String> indexedFields) {
        this.connection = connection;
        this.table = table;
        this.indexedFields = indexedFields;
        this.objectMapper = new ObjectMapper();
    }

    public void initialize() {
        String createTableSQL = String.format(
            "CREATE TABLE IF NOT EXISTS %s (" +
            "    _id INTEGER PRIMARY KEY AUTOINCREMENT, " +
            "    id TEXT, " +
            "    payload TEXT NOT NULL, " +
            "    created_at INTEGER DEFAULT (CAST((julianday('now') - 2440587.5)*86400000 AS INTEGER)), " +
            "    deleted INTEGER DEFAULT 0, " +
            "    authorized_tokens TEXT, " +
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

    private Envelope rowToEnvelope(ResultSet rs) throws SQLException, JsonProcessingException {
        Envelope envelope = new Envelope(
                rs.getString("id"),
                Stash.parseJSON(rs.getString("payload")),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                (rs.getInt("deleted") == 1),
                objectMapper.readValue(rs.getString("authorized_tokens"), new TypeReference<>(){})
        );
        
        return envelope;
    }

    @Override
    public Envelope create(Envelope doc, List<String> tokens) {
        // Don't specify created_at - let the database DEFAULT handle it
        // This ensures timestamps are set atomically by the database
        String query = String.format(
            "INSERT INTO %s (id, payload, authorized_tokens) " +
            "VALUES (?, ?, ?);", table);

        String id = doc.id() != null ? doc.id() : Generators.timeBasedGenerator().generate().toString();

        try {
            PreparedStatement pstmt = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
            pstmt.setString(1, id);
            pstmt.setString(2, objectMapper.writeValueAsString(doc.payload()));
            pstmt.setString(3, objectMapper.writeValueAsString(tokens != null ? tokens : Collections.emptyList()));

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
                    return create(doc, tokens);
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
    public List<Envelope> createMany(List<Envelope> payloads, List<String> tokens) {
        List<Envelope> created = new ArrayList<>();
        
        try {
            connection.setAutoCommit(false);
            
            for (Envelope envelope : payloads) {
                created.add(create(envelope, tokens));
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
    public Optional<Envelope> read(String id, List<String> tokens, Instant at) {
        long atMs = at != null ? at.toEpochMilli() : System.currentTimeMillis();
        
        StringBuilder queryBuilder = new StringBuilder(String.format(
            "SELECT * FROM %s " +
            "WHERE id = ? AND deleted = 0 AND created_at <= ? ", table));
            
        boolean filtered = !Auth.seesEverything(tokens);
        if (filtered) {
            queryBuilder.append(VISIBLE_TO_CALLER);
        }
        
        queryBuilder.append("ORDER BY created_at DESC LIMIT 1;");
        
        try (PreparedStatement pstmt = connection.prepareStatement(queryBuilder.toString())) {
            pstmt.setString(1, id);
            pstmt.setLong(2, atMs);
            
            if (filtered) {
                pstmt.setString(3, objectMapper.writeValueAsString(visibilityTokens(tokens)));
            }
            
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rowToEnvelope(rs));
                }
            }
            
            return Optional.empty();
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException("Error reading document", e);
        }
    }

    @Override
    public List<Envelope> readMany(List<String> ids, List<String> tokens) {
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
        
        StringBuilder queryBuilder = new StringBuilder(String.format(
            "SELECT * FROM %s " +
            "WHERE id IN (%s) AND deleted = 0 AND created_at <= ? ", 
            table, placeholders));
            
        boolean filtered = !Auth.seesEverything(tokens);
        if (filtered) {
            queryBuilder.append(VISIBLE_TO_CALLER);
        }
        
        queryBuilder.append("ORDER BY created_at DESC;");
        
        try (PreparedStatement pstmt = connection.prepareStatement(queryBuilder.toString())) {
            int paramIndex = 1;
            
            for (String id : ids) {
                pstmt.setString(paramIndex++, id);
            }
            
            pstmt.setLong(paramIndex++, now);
            
            if (filtered) {
                pstmt.setString(paramIndex, objectMapper.writeValueAsString(visibilityTokens(tokens)));
            }
            
            try (ResultSet rs = pstmt.executeQuery()) {
                Set<String> seen = new HashSet<>();
                List<Envelope> envelopes = new ArrayList<>();
                
                while (rs.next()) {
                    String id = rs.getString("id");
                    if (!seen.contains(id)) {
                        seen.add(id);
                        envelopes.add(rowToEnvelope(rs));
                    }
                }
                
                return envelopes;
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException("Error reading multiple documents", e);
        }
    }

    @Override
    public Boolean remove(String id, List<String> tokens) {
        StringBuilder queryBuilder = new StringBuilder(String.format(
            "UPDATE %s SET deleted = 1 WHERE id = ? ", table));
            
        boolean filtered = !Auth.seesEverything(tokens);
        if (filtered) {
            queryBuilder.append(VISIBLE_TO_CALLER);
        }
        
        try (PreparedStatement pstmt = connection.prepareStatement(queryBuilder.toString())) {
            pstmt.setString(1, id);
            
            if (filtered) {
                pstmt.setString(2, objectMapper.writeValueAsString(visibilityTokens(tokens)));
            }
            
            int changes = pstmt.executeUpdate();
            return changes > 0;
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException("Error removing document", e);
        }
    }

    @Override
    public Map<String, Boolean> removeMany(List<String> ids, List<String> tokens) {
        Map<String, Boolean> result = new HashMap<>();
        
        try {
            connection.setAutoCommit(false);
            
            for (String id : ids) {
                result.put(id, remove(id, tokens));
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
    public List<Envelope> list(List<String> tokens) {
        StringBuilder queryBuilder = new StringBuilder(String.format(
            "SELECT * FROM %s WHERE deleted = 0 ", table));
            
        boolean filtered = !Auth.seesEverything(tokens);
        if (filtered) {
            queryBuilder.append(VISIBLE_TO_CALLER);
        }
        
        queryBuilder.append("ORDER BY created_at DESC;");
        
        try (PreparedStatement pstmt = connection.prepareStatement(queryBuilder.toString())) {
            if (filtered) {
                pstmt.setString(1, objectMapper.writeValueAsString(visibilityTokens(tokens)));
            }
            
            try (ResultSet rs = pstmt.executeQuery()) {
                Set<String> seen = new HashSet<>();
                List<Envelope> envelopes = new ArrayList<>();
                
                while (rs.next()) {
                    String id = rs.getString("id");
                    if (!seen.contains(id)) {
                        seen.add(id);
                        envelopes.add(rowToEnvelope(rs));
                    }
                }
                
                return envelopes;
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException("Error listing documents", e);
        }
    }
} 