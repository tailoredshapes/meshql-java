package com.meshql.repositories.postgres;

import com.fasterxml.uuid.Generators;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.NotAuthorizedException;
import com.meshql.core.Operation;
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

/**
 * PostgreSQL storage.
 *
 * <p>The auth plugin's mark lives in an {@code auth} JSONB column on the record's
 * own row, written by the same {@code INSERT} as the payload. It used to live in
 * a side table with its own statement inside the same transaction; an opaque
 * mark has no business being normalised into tokens, and keeping it on the row
 * means authorization cannot become a dual write under any failure.
 *
 * <p>No query here carries a visibility predicate and no method holds
 * credentials. The repository fetches, then asks the session.
 */
public class PostgresRepository implements Repository {
    private static final Logger logger = LoggerFactory.getLogger(PostgresRepository.class);
    private static final int MAX_RETRIES = 5;
    private static final long RETRY_DELAY_MS = 2;

    private static record SqlTemplates(
            List<Template> createScripts,
            Template insert,
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

    /** A missing session is never an internal call. It is a caller who may see nothing. */
    private static boolean allows(AuthSession session, Operation operation, Envelope envelope) {
        return session != null && session.isAuthorized(operation, envelope);
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
            List<Template> createScripts = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> loadTemplate("sql/create/" + i + ".sql"))
                .collect(Collectors.toList());

            return new SqlTemplates(
                createScripts,
                loadTemplate("sql/insert.sql"),
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
     */
    public void initialize() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            Map<String, Object> context = new HashMap<>();
            context.put("tableName", tableName);

            for(Template t : sqlTemplates.createScripts()) {
                stmt.execute(t.apply(context));
            }

            logger.info("Initialized PostgreSQL repository with table: {}", tableName);
        } catch (IOException e) {
            throw new SQLException("Failed to render SQL template", e);
        }
    }

    private String renderSql(Template template) throws IOException {
        Map<String, Object> context = new HashMap<>();
        context.put("tableName", tableName);
        return template.apply(context);
    }

    @Override
    public Envelope create(Envelope envelope, AuthSession session) {
        if (session == null) {
            throw new NotAuthorizedException("A write needs a session; there is no unset session");
        }
        Envelope stamped = session.stamp(envelope);
        if (!session.isAuthorized(Operation.CREATE, stamped)) {
            throw new NotAuthorizedException("Not authorized to create " + envelope.id());
        }
        return createWithRetry(stamped, 0);
    }

    private Envelope createWithRetry(Envelope stamped, int retryCount) {
        String id = stamped.id() != null ? stamped.id() : Generators.timeBasedGenerator().generate().toString();

        try {
            String sql = renderSql(sqlTemplates.insert());

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, id);
                stmt.setString(2, stamped.payload().toJSONString());
                stmt.setString(3, writeAuth(stamped.auth()));

                try (ResultSet rs = stmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new SQLException("Failed to create document, no rows returned");
                    }
                    return resultSetToEnvelope(rs);
                }
            }
        } catch (SQLException e) {
            // PostgreSQL unique violation error code is 23505
            if (e.getSQLState() != null && e.getSQLState().equals("23505") && retryCount < MAX_RETRIES) {
                try {
                    TimeUnit.MILLISECONDS.sleep(RETRY_DELAY_MS);
                    return createWithRetry(stamped, retryCount + 1);
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
    public List<Envelope> createMany(List<Envelope> envelopes, AuthSession session) {
        List<Envelope> created = new ArrayList<>();
        for (Envelope envelope : envelopes) {
            created.add(create(envelope, session));
        }
        return created;
    }

    @Override
    public Optional<Envelope> read(String id, AuthSession session, Instant createdAt) {
        return latestAt(id, createdAt).filter(e -> allows(session, Operation.READ, e));
    }

    /** The latest live version of an id at an instant, before anyone is asked about it. */
    private Optional<Envelope> latestAt(String id, Instant createdAt) {
        Instant at = createdAt == null ? Instant.now() : createdAt;

        try {
            String sql = renderSql(sqlTemplates.read());

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, id);
                stmt.setTimestamp(2, instantToTimestamp(at));

                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next() ? Optional.of(resultSetToEnvelope(rs)) : Optional.empty();
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
    public List<Envelope> readMany(List<String> ids, AuthSession session) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }

        try {
            String sql = renderSql(sqlTemplates.readMany());

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, String.join(",", ids));

                try (ResultSet rs = stmt.executeQuery()) {
                    List<Envelope> results = new ArrayList<>();
                    while (rs.next()) {
                        Envelope envelope = resultSetToEnvelope(rs);
                        if (allows(session, Operation.READ, envelope)) {
                            results.add(envelope);
                        }
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
    public Boolean remove(String id, AuthSession session) {
        boolean removable = latestAt(id, Instant.now())
                .filter(e -> allows(session, Operation.REMOVE, e))
                .isPresent();
        if (!removable) {
            return false;
        }

        try {
            String sql = renderSql(sqlTemplates.remove());

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql)) {

                stmt.setString(1, id);
                return stmt.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            logger.error("Error removing document: {}", e.getMessage(), e);
            return false;
        } catch (IOException e) {
            logger.error("Failed to render SQL template", e);
            return false;
        }
    }

    /**
     * Remove each id in turn, so the result says which removals happened.
     *
     * <p>A single bulk update cannot say which of the ids it matched, and an
     * unauthorized removal reported as a success is exactly the answer this
     * change exists to stop.
     */
    @Override
    public Map<String, Boolean> removeMany(List<String> ids, AuthSession session) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Boolean> results = new HashMap<>();
        for (String id : ids) {
            results.put(id, remove(id, session));
        }
        return results;
    }

    @Override
    public List<Envelope> list(AuthSession session) {
        try {
            String sql = renderSql(sqlTemplates.list());

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(sql);
                 ResultSet rs = stmt.executeQuery()) {

                List<Envelope> results = new ArrayList<>();
                while (rs.next()) {
                    Envelope envelope = resultSetToEnvelope(rs);
                    if (allows(session, Operation.READ, envelope)) {
                        results.add(envelope);
                    }
                }
                return results;
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
