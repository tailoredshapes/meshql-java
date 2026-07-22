package com.meshql.repositories.merksql;

import com.github.jknack.handlebars.Template;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Searcher;
import com.tailoredshapes.stash.Stash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Searcher backed by a merkql broker via JNI.
 * Queries are evaluated against the in-memory index in MerkSqlStore.
 */
public class MerkSqlSearcher implements Searcher {
    private static final Logger logger = LoggerFactory.getLogger(MerkSqlSearcher.class);

    private final Map<String, List<Envelope>> db;
    private final Auth authorizer;

    public MerkSqlSearcher(MerkSqlStore store, Auth authorizer) {
        this.db = store.getDb();
        this.authorizer = authorizer;
    }

    private String processQueryTemplate(Stash parameters, Template queryTemplate) {
        try {
            return queryTemplate.apply(parameters);
        } catch (IOException e) {
            logger.error("Failed to apply template", e);
            throw new RuntimeException("Failed to apply template", e);
        }
    }

    private boolean matchesQuery(Envelope envelope, String query) {
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        String[] conditions = trimmed.split("\\s+AND\\s+");
        for (String condition : conditions) {
            if (!matchesCondition(envelope, condition.trim())) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesCondition(Envelope envelope, String condition) {
        String[] parts = condition.split("\\s*=\\s*");
        if (parts.length != 2) {
            logger.warn("Invalid condition format: {}", condition);
            return false;
        }

        String field = parts[0].trim();
        String expectedValue = parts[1].trim().replaceAll("^'|'$", "");

        String actualValue;
        if ("id".equals(field)) {
            actualValue = envelope.id();
        } else {
            Object payloadValue = envelope.payload().get(field);
            actualValue = payloadValue != null ? payloadValue.toString() : null;
        }

        return expectedValue.equals(actualValue);
    }

    private List<Envelope> getLatestVersions(long timestamp) {
        Instant timestampInstant = Instant.ofEpochMilli(timestamp + 1);
        List<Envelope> results = new ArrayList<>();

        for (List<Envelope> envelopes : db.values()) {
            envelopes.stream()
                    .filter(e -> !e.deleted() && !e.createdAt().isAfter(timestampInstant))
                    .max(Comparator.comparing(Envelope::createdAt))
                    .ifPresent(results::add);
        }

        return results;
    }

    @Override
    public Stash find(Template queryTemplate, Stash args, List<String> tokens, long timestamp) {
        String query = processQueryTemplate(args, queryTemplate);

        try {
            List<Envelope> latestVersions = getLatestVersions(timestamp);

            Optional<Envelope> match = latestVersions.stream()
                    .filter(envelope -> matchesQuery(envelope, query))
                    .filter(envelope -> authorizer.isAuthorized(tokens, envelope))
                    .findFirst();

            if (match.isPresent()) {
                Envelope envelope = match.get();
                Stash payload = envelope.payload();
                if (payload != null) {
                    payload.put("id", envelope.id());
                    payload.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(envelope.createdAt()));
                    return payload;
                }
            }

            return new Stash();
        } catch (Exception e) {
            logger.error("Error finding document: {}", e.getMessage(), e);
            return new Stash();
        }
    }

    @Override
    public List<Stash> findAll(Template queryTemplate, Stash args, List<String> tokens, long timestamp) {
        String query = processQueryTemplate(args, queryTemplate);

        int limit = -1;
        if (args.containsKey("limit")) {
            limit = ((Number) args.get("limit")).intValue();
        }

        try {
            List<Envelope> latestVersions = getLatestVersions(timestamp);

            java.util.stream.Stream<Stash> stream = latestVersions.stream()
                    .filter(envelope -> matchesQuery(envelope, query))
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
                    .filter(Objects::nonNull);

            if (limit > 0) {
                stream = stream.limit(limit);
            }

            return stream.collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error finding documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
