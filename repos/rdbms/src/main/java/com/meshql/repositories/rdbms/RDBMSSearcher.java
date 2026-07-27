package com.meshql.repositories.rdbms;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Searcher;
import com.tailoredshapes.stash.Stash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.meshql.repositories.rdbms.Converters.resultSetToEnvelope;
import static com.tailoredshapes.stash.Stash.stash;

public abstract class RDBMSSearcher implements Searcher {
    private static final Logger logger = LoggerFactory.getLogger(RDBMSSearcher.class);

    /**
     * Handlebars configured for SQL string literals rather than for HTML.
     *
     * @see SqlLiteralEscapingStrategy
     */
    protected static final Handlebars SQL_HANDLEBARS =
            new Handlebars().with(SqlLiteralEscapingStrategy.INSTANCE);

    private final DataSource dataSource;
    private final String tableName;
    private final Auth authorizer;
    private final Function<Long, ?> timeFunc;

    private final Template SINGLETON_QUERY_TEMPLATE;

    private final Template VECTOR_QUERY_TEMPLATE;


    public RDBMSSearcher(Template singletonTemplate, Template vectorTemplate, DataSource dataSource, String tableName, Auth authorizer, Function<Long, ?> timeFunc) {
        this.SINGLETON_QUERY_TEMPLATE = singletonTemplate;
        this.VECTOR_QUERY_TEMPLATE = vectorTemplate;
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.authorizer = authorizer;
        this.timeFunc = timeFunc;
    }

    /**
     * Compile a configured query fragment for SQL.
     *
     * <p>The fragment is SQL, so values interpolated into it are escaped for SQL
     * string literals - a single quote is doubled - and not for HTML. Without
     * this override a search for {@code O'Brien} would generate
     * {@code = 'O&#x27;Brien'} and quietly match nothing.
     */
    @Override
    public Template compileQuery(String query) {
        try {
            return SQL_HANDLEBARS.compileInline(query);
        } catch (IOException e) {
            throw new RuntimeException("Failed to compile query template: " + query, e);
        }
    }

    /**
     * Processes a Handlebars template with the provided parameters.
     *
     * @param parameters    Parameters to apply to the template
     * @param queryTemplate Handlebars template for the query
     * @return Processed query string
     */
    private String processQueryTemplate(Stash parameters, Template queryTemplate) {
        try {
            return queryTemplate.apply(parameters);
        } catch (IOException e) {
            logger.error("Failed to apply template: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to apply template", e);
        }
    }

    @Override
    public Stash find(Template queryTemplate, Stash args, List<String> tokens, long timestamp) {
        String filters = processQueryTemplate(args, queryTemplate);
        String sql = processQueryTemplate(stash("_name", tableName, "filters", filters, "_createdAt", timeFunc.apply(timestamp)), SINGLETON_QUERY_TEMPLATE);

        logger.debug("Executing singleton query: {}", sql);

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    Envelope envelope = resultSetToEnvelope(rs);

                    if (authorizer.isAuthorized(tokens, envelope)) {
                        Stash payload = envelope.payload();
                        if (payload != null) {
                            payload.put("id", envelope.id());
                            payload.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(envelope.createdAt()));
                            return payload;
                        }
                    } else {
                        logger.trace("Not authorized to access document");
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Error executing find query: {}", e.getMessage(), e);
        }

        return new Stash();
    }

    @Override
    public List<Stash> findAll(Template queryTemplate, Stash args, List<String> tokens, long timestamp) {
        String filters = processQueryTemplate(args, queryTemplate);

        Stash context = stash("_name", tableName, "filters", filters, "_createdAt", timeFunc.apply(timestamp));
        Object limitObj = args.get("limit");
        if (limitObj instanceof Number) {
            context.put("_limit", String.valueOf(((Number) limitObj).intValue()));
        }

        String sql = processQueryTemplate(context, VECTOR_QUERY_TEMPLATE);

        logger.debug("Executing vector query: {}", sql);

        try (Connection conn = dataSource.getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            try (ResultSet rs = stmt.executeQuery()) {
                List<Envelope> envelopes = new ArrayList<>();
                while (rs.next()) {
                    envelopes.add(resultSetToEnvelope(rs));
                }

                return envelopes.stream()
                        .filter(envelope -> authorizer.isAuthorized(tokens, envelope))
                        .map(envelope -> {
                            Stash payload = envelope.payload();
                            if (payload != null) {
                                payload.put("id", envelope.id());
                                payload.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(envelope.createdAt()));
                                return payload;
                            }
                            return null;
                        })
                        .filter(payload -> payload != null)
                        .collect(Collectors.toList());
            }
        } catch (SQLException e) {
            logger.error("Error executing findAll query: {}", e.getMessage(), e);
        }

        return Collections.emptyList();
    }
}