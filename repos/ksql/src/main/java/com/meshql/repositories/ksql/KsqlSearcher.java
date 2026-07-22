package com.meshql.repositories.ksql;

import com.github.jknack.handlebars.Template;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Searcher;
import com.tailoredshapes.stash.Stash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.meshql.repositories.ksql.Converters.rowToEnvelope;

public class KsqlSearcher implements Searcher {
    private static final Logger logger = LoggerFactory.getLogger(KsqlSearcher.class);

    private final KsqlHttpClient ksqlClient;
    private final String tableName;
    private final Auth authorizer;

    public KsqlSearcher(KsqlHttpClient ksqlClient, String topic, Auth authorizer) {
        this.ksqlClient = ksqlClient;
        this.tableName = topic.replace("-", "_") + "_table";
        this.authorizer = authorizer;
    }

    @Override
    public Stash find(Template queryTemplate, Stash args, List<String> creds, long timestamp) {
        String whereClause = applyTemplate(queryTemplate, args);
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return new Stash();
        }

        String query = String.format(
                "SELECT * FROM %s WHERE %s AND deleted = false;",
                tableName, whereClause);

        logger.debug("KsqlSearcher.find() - Query: {}", query);

        try {
            List<Map<String, Object>> results = ksqlClient.executeQuery(query);

            if (!results.isEmpty()) {
                Map<String, Object> row = results.get(0);
                String id = getIdFromRow(row);
                Envelope envelope = rowToEnvelope(id, row);

                if (authorizer.isAuthorized(creds, envelope)) {
                    Stash payload = envelope.payload();
                    if (payload != null) {
                        payload.put("id", envelope.id());
                        payload.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(envelope.createdAt()));
                        return payload;
                    }
                }
            }

            return new Stash();
        } catch (Exception e) {
            logger.error("Error finding document: {}", e.getMessage(), e);
            return new Stash();
        }
    }

    @Override
    public List<Stash> findAll(Template queryTemplate, Stash args, List<String> creds, long timestamp) {
        String whereClause = applyTemplate(queryTemplate, args);
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return Collections.emptyList();
        }

        int limit = -1;
        if (args.containsKey("limit")) {
            limit = ((Number) args.get("limit")).intValue();
        }

        String query = String.format(
                "SELECT * FROM %s WHERE %s AND deleted = false;",
                tableName, whereClause);

        logger.debug("KsqlSearcher.findAll() - Query: {}", query);

        try {
            List<Map<String, Object>> results = ksqlClient.executeQuery(query);

            Stream<Stash> stream = results.stream()
                    .map(row -> {
                        String id = getIdFromRow(row);
                        return rowToEnvelope(id, row);
                    })
                    .filter(envelope -> !envelope.deleted())
                    .filter(envelope -> authorizer.isAuthorized(creds, envelope))
                    .map(envelope -> {
                        Stash payload = envelope.payload();
                        if (payload != null) {
                            payload.put("id", envelope.id());
                            payload.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(envelope.createdAt()));
                            return payload;
                        }
                        return null;
                    })
                    .filter(payload -> payload != null);

            if (limit > 0) {
                stream = stream.limit(limit);
            }

            return stream.collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error finding documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    private String applyTemplate(Template queryTemplate, Stash args) {
        try {
            return queryTemplate.apply(args);
        } catch (IOException e) {
            logger.error("Failed to apply template", e);
            throw new RuntimeException("Failed to apply template", e);
        }
    }

    private String getIdFromRow(Map<String, Object> row) {
        Object id = row.get("ID");
        if (id == null) id = row.get("id");
        return id != null ? id.toString() : null;
    }
}
