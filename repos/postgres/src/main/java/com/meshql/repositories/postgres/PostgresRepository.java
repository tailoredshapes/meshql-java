package com.meshql.repositories.postgres;

import com.fasterxml.uuid.Generators;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.meshql.repositories.rdbms.Converters.*;
import static com.tailoredshapes.underbar.ocho.Die.rethrow;
import static com.tailoredshapes.underbar.ocho.UnderBar.map;

public class PostgresRepository implements Repository {
    private static final Logger logger = LoggerFactory.getLogger(PostgresRepository.class);
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 2;

    // Inline the RequiredTemplates record as a private static inner class
    private static record SqlTemplates(
            List<Template> createScripts,
            Template insert,
            Template insertToken,
            Template read,
            Template readMany,
            Template remove,
            Template removeMany,
            Template list
    ) { }

    private final DataSource dataSource;
    private final String tableName;
    public final Handlebars handlebars;
    private final SqlTemplates sqlTemplates;

    /**
     * Fill in the visibility half of a query's template context.
     *
     * <p>The predicate applies to every caller, including one presenting no tokens.
     * It used to be rendered only when the caller held tokens, which gave an
     * anonymous request an unfiltered read of everything: an envelope is public
     * because it carries no authorized tokens, never because the caller carries
     * none. A caller that needs to see everything holds
     * {@link Auth#WILDCARD_TOKEN}, and that is the only way to skip the predicate.
     *
     * <p>The matched tokens are the caller's own plus the wildcard, so an envelope
     * marked readable by everyone is matched too. The list is therefore never
     * empty, and the rendered {@code IN} clause always has at least one parameter.
     */
    private static void applyVisibility(Map<String, Object> context, List<String> tokens) {
        boolean filtered = !Auth.seesEverything(tokens);
        context.put("filtered", filtered);
        if (filtered) {
            context.put("tokens", visibilityTokens(tokens));
        }
    }

    /** The tokens a filtered query binds, in the order the template renders them. */
    private static List<String> visibilityTokens(List<String> tokens) {
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        if (tokens != null) {
            matched.addAll(tokens);
        }
        matched.add(Auth.WILDCARD_TOKEN);
        return List.copyOf(matched);
    }

    /** The tokens to bind for this call, or empty when the query carries no predicate. */
    private static List<String> boundTokens(List<String> tokens) {
        return Auth.seesEverything(tokens) ? List.of() : visibilityTokens(tokens);
    }

    /**
     * Constructor for PostgresRepository.
     *
     * @param dataSource DataSource for database connections
     * @param tableName  Name of the table to use for storage
     */
    public PostgresRepository(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.handlebars = new Handlebars();
        this.sqlTemplates = initializeTemplates();
    }

    public SqlTemplates initializeTemplates() {
        try {
            List<Template> createScripts = IntStream.rangeClosed(1, 8)
                .mapToObj(i -> loadTemplate("sql/create/" + i + ".sql"))
                .collect(Collectors.toList());

            return new SqlTemplates(
                createScripts,
                loadTemplate("sql/insert.sql"),
                loadTemplate("sql/insertToken.sql"),
                loadTemplate("sql/read.sql"),
                loadTemplate("sql/readMany.sql"),
                loadTemplate("sql/remove.sql"),
                loadTemplate("sql/removeMany.sql"),
                loadTemplate("sql/list.sql")
            );
        } catch (Exception e) {
            logger.error("Failed to initialize templates: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize templates", e);
        }
    }

    /**
     * Loads a template from the resources folder.
     *
     * @param path Path to the template file
     * @return Compiled Handlebars template
     */
    private Template loadTemplate(String path) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new FileNotFoundException("Template not found: " + path);
            }
            
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                String templateContent = new BufferedReader(reader)
                    .lines()
                    .collect(Collectors.joining("\n"));
                return handlebars.compileInline(templateContent);
            }
        } catch (IOException e) {
            logger.error("Failed to load template: {}", path, e);
            throw new RuntimeException("Failed to load template: " + path, e);
        }
    }
    
    /**
     * Initializes the database schema.
     *
     * @throws SQLException if a database access error occurs
     */
    public void initialize() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            Map<String, Object> context = new HashMap<>();
            context.put("tableName", tableName);

            for(Template t : sqlTemplates.createScripts()) {
                stmt.execute(t.apply(context));
            };

            logger.info("Initialized PostgreSQL repository with table: {}", tableName);
        } catch (IOException e) {
            throw new SQLException("Failed to render SQL template", e);
        }
    }

    @Override
    public Envelope create(Envelope envelope, List<String> tokens) {
        return createWithRetry(envelope, tokens, 0);
    }

    /**
     * Creates a document with retry logic for handling unique constraint
     * violations.
     *
     * @param envelope   Envelope to create
     * @param tokens     Authorization tokens
     * @param retryCount Current retry count
     * @return Created envelope
     */
    private Envelope createWithRetry(Envelope envelope, List<String> tokens, int retryCount) {
        String id = envelope.id() != null ? envelope.id() : Generators.timeBasedGenerator().generate().toString();

        try {
            Map<String, Object> context = new HashMap<>();
            context.put("tableName", tableName);

            String sql = sqlTemplates.insert().apply(context);
            String tokenSql = sqlTemplates.insertToken().apply(context);

            Connection conn = null;
            try {
                conn = dataSource.getConnection();
                conn.setAutoCommit(false);

                // Insert main record
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, id);
                    stmt.setString(2, envelope.payload().toJSONString());

                    ResultSet rs = stmt.executeQuery();
                    if (!rs.next()) {
                        throw new SQLException("Failed to create document, no rows returned");
                    }

                    Envelope result = resultSetToEnvelope(rs);
                    rs.close();

                    // Insert token records if any
                    if (tokens != null && !tokens.isEmpty()) {
                        try (PreparedStatement tokenStmt = conn.prepareStatement(tokenSql)) {
                            for (int i = 0; i < tokens.size(); i++) {
                                tokenStmt.setString(1, id);
                                tokenStmt.setTimestamp(2, instantToTimestamp(result.createdAt()));
                                tokenStmt.setString(3, tokens.get(i));
                                tokenStmt.setInt(4, i);
                                tokenStmt.addBatch();
                            }
                            tokenStmt.executeBatch();
                        }
                    }

                    conn.commit();
                    
                    // Set the authorized tokens on the result
                    return new Envelope(
                        result.id(),
                        result.payload(),
                        result.createdAt(),
                        result.deleted(),
                        tokens != null ? tokens : List.of()
                    );
                }
            } catch (SQLException e) {
                if (conn != null) {
                    try {
                        conn.rollback();
                    } catch (SQLException rollbackException) {
                        logger.error("Failed to rollback transaction", rollbackException);
                    }
                }
                throw e;
            } finally {
                if (conn != null) {
                    try {
                        conn.setAutoCommit(true);
                        conn.close();
                    } catch (SQLException closeException) {
                        logger.error("Failed to close connection", closeException);
                    }
                }
            }
        } catch (SQLException e) {
            // PostgreSQL unique violation error code is 23505
            if (e.getSQLState() != null && e.getSQLState().equals("23505") && retryCount < MAX_RETRIES) {
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS);
                    return createWithRetry(envelope, tokens, retryCount + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during retry", ie);
                }
            }
            logger.error("Error creating document: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create document", e);
        } catch (IOException e) {
            logger.error("Failed to render SQL template", e);
            throw new RuntimeException("Failed to render SQL template", e);
        }
    }

    @Override
    public List<Envelope> createMany(List<Envelope> envelopes, List<String> tokens) {
        List<Envelope> created = new ArrayList<>();
        for (Envelope envelope : envelopes) {
            created.add(create(envelope, tokens));
        }
        return created;
    }

    @Override
    public Optional<Envelope> read(String id, List<String> tokens, Instant createdAt) {
        if (createdAt == null) {
            createdAt = Instant.now();
        }

        try {
            Map<String, Object> context = new HashMap<>();
            context.put("tableName", tableName);
            applyVisibility(context, tokens);

            String sql = sqlTemplates.read().apply(context);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, id);
                stmt.setTimestamp(2, instantToTimestamp(createdAt));

                // Bind the visibility predicate's tokens, if the query carries one.
                List<String> bound = boundTokens(tokens);
                for (int i = 0; i < bound.size(); i++) {
                    stmt.setString(i + 3, bound.get(i));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        return Optional.of(resultSetToEnvelope(rs));
                    } else {
                        return Optional.empty();
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error reading document: {}", e.getMessage(), e);
            return Optional.empty();
        } catch (IOException e) {
            logger.error("Failed to render SQL template", e);
            return Optional.empty();
        }
    }

    @Override
    public List<Envelope> readMany(List<String> ids, List<String> tokens) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Map<String, Object> context = new HashMap<>();
            context.put("tableName", tableName);
            applyVisibility(context, tokens);

            String sql = sqlTemplates.readMany().apply(context);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                // Join ids with commas for the string_to_array function
                stmt.setString(1, String.join(",", ids));

                // Bind the visibility predicate's tokens, if the query carries one.
                List<String> bound = boundTokens(tokens);
                for (int i = 0; i < bound.size(); i++) {
                    stmt.setString(i + 2, bound.get(i));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    List<Envelope> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(resultSetToEnvelope(rs));
                    }
                    return results;
                }
            }
        } catch (SQLException e) {
            logger.error("Error reading multiple documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        } catch (IOException e) {
            logger.error("Failed to render SQL template", e);
            return Collections.emptyList();
        }
    }

    @Override
    public Boolean remove(String id, List<String> tokens) {
        try {
            Map<String, Object> context = new HashMap<>();
            context.put("tableName", tableName);
            applyVisibility(context, tokens);

            String sql = sqlTemplates.remove().apply(context);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, id);

                // Bind the visibility predicate's tokens, if the query carries one.
                List<String> bound = boundTokens(tokens);
                for (int i = 0; i < bound.size(); i++) {
                    stmt.setString(i + 2, bound.get(i));
                }

                int affectedRows = stmt.executeUpdate();
                return affectedRows > 0;
            }
        } catch (SQLException e) {
            logger.error("Error removing document: {}", e.getMessage(), e);
            return false;
        } catch (IOException e) {
            logger.error("Failed to render SQL template", e);
            return false;
        }
    }

    @Override
    public Map<String, Boolean> removeMany(List<String> ids, List<String> tokens) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }

        try {
            Map<String, Object> context = new HashMap<>();
            context.put("tableName", tableName);
            applyVisibility(context, tokens);

            String sql = sqlTemplates.removeMany().apply(context);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                // Join ids with commas for the string_to_array function
                stmt.setString(1, String.join(",", ids));

                // Bind the visibility predicate's tokens, if the query carries one.
                List<String> bound = boundTokens(tokens);
                for (int i = 0; i < bound.size(); i++) {
                    stmt.setString(i + 2, bound.get(i));
                }

                int affectedRows = stmt.executeUpdate();

                // We can't tell exactly which IDs were successfully deleted, since PostgreSQL
                // doesn't provide that level of detail in the result. This is an approximation.
                Map<String, Boolean> results = new HashMap<>();
                for (String id : ids) {
                    results.put(id, true);
                }

                return results;
            }
        } catch (SQLException e) {
            logger.error("Error removing multiple documents: {}", e.getMessage(), e);
            
            // In case of error, report all as failed
            Map<String, Boolean> results = new HashMap<>();
            for (String id : ids) {
                results.put(id, false);
            }
            
            return results;
        } catch (IOException e) {
            logger.error("Failed to render SQL template", e);
            
            // In case of error, report all as failed
            Map<String, Boolean> results = new HashMap<>();
            for (String id : ids) {
                results.put(id, false);
            }
            
            return results;
        }
    }

    @Override
    public List<Envelope> list(List<String> tokens) {
        try {
            Map<String, Object> context = new HashMap<>();
            context.put("tableName", tableName);
            applyVisibility(context, tokens);

            String sql = sqlTemplates.list().apply(context);

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                // Bind the visibility predicate's tokens, if the query carries one.
                List<String> bound = boundTokens(tokens);
                for (int i = 0; i < bound.size(); i++) {
                    stmt.setString(i + 1, bound.get(i));
                }

                try (ResultSet rs = stmt.executeQuery()) {
                    List<Envelope> results = new ArrayList<>();
                    while (rs.next()) {
                        results.add(resultSetToEnvelope(rs));
                    }
                    return results;
                }
            }
        } catch (SQLException e) {
            logger.error("Error listing documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        } catch (IOException e) {
            logger.error("Failed to render SQL template", e);
            return Collections.emptyList();
        }
    }
} 