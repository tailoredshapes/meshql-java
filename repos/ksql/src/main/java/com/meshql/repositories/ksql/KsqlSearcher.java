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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.meshql.repositories.ksql.Converters.rowToEnvelope;

public class KsqlSearcher implements Searcher {
    private static final Logger logger = LoggerFactory.getLogger(KsqlSearcher.class);

    private final KsqlHttpClient ksqlClient;
    private final String streamName;
    private final Auth authorizer;

    public KsqlSearcher(KsqlHttpClient ksqlClient, String topic, Auth authorizer) {
        this.ksqlClient = ksqlClient;
        // Reads go to the stream, not the materialized table. The table is a
        // LATEST_BY_OFFSET rollup, so it holds one version per id and cannot
        // answer "as of" at all: an `at:` argument silently returned the present.
        // The stream is the version history, so the as-of instant can filter it.
        this.streamName = topic.replace("-", "_") + "_stream";
        this.authorizer = authorizer;
    }

    /**
     * Read the version history matching a query, as of an instant.
     *
     * <p>The as-of filter has to be applied before the latest-version reduction,
     * and the {@code deleted} flag after it: a removed record's history still
     * contains the versions that preceded the tombstone, and those versions are
     * what an earlier instant should resolve to.
     */
    private List<Envelope> versionsAsOf(String whereClause, long timestamp) {
        String query = String.format(
                "SELECT * FROM %s WHERE %s AND created_at <= %d;",
                streamName, whereClause, timestamp);

        logger.debug("KsqlSearcher - Query: {}", query);

        Map<String, Envelope> latest = new LinkedHashMap<>();
        for (Map<String, Object> row : ksqlClient.executeQuery(query)) {
            Envelope envelope = rowToEnvelope(getIdFromRow(row), row);
            Envelope seen = latest.get(envelope.id());
            if (seen == null || !envelope.createdAt().isBefore(seen.createdAt())) {
                latest.put(envelope.id(), envelope);
            }
        }

        return latest.values().stream()
                .filter(envelope -> !envelope.deleted())
                .collect(Collectors.toList());
    }

    @Override
    public Stash find(Template queryTemplate, Stash args, List<String> creds, long timestamp) {
        String whereClause = applyTemplate(queryTemplate, args);
        if (whereClause == null || whereClause.trim().isEmpty()) {
            return new Stash();
        }

        try {
            List<Envelope> results = versionsAsOf(whereClause, timestamp);

            if (!results.isEmpty()) {
                Envelope envelope = results.get(0);

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

        try {
            Stream<Stash> stream = versionsAsOf(whereClause, timestamp).stream()
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
