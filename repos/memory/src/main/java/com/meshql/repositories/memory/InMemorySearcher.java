package com.meshql.repositories.memory;

import com.github.jknack.handlebars.Template;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.Operation;
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
 * In-memory implementation of the Searcher interface.
 */
public class InMemorySearcher implements Searcher {
    private static final Logger logger = LoggerFactory.getLogger(InMemorySearcher.class);

    private final Map<String, List<Envelope>> db;

    public InMemorySearcher(InMemoryStore store) {
        this.db = store.getDb();
    }

    /** A missing session is never an internal call. It is a caller who may see nothing. */
    private static boolean allows(AuthSession session, Envelope envelope) {
        return session != null && session.isAuthorized(Operation.READ, envelope);
    }

    /**
     * Process a query template with parameters to generate a query string.
     */
    private String processQueryTemplate(Stash parameters, Template queryTemplate) {
        try {
            return queryTemplate.apply(parameters);
        } catch (IOException e) {
            logger.error("Failed to apply template", e);
            throw new RuntimeException("Failed to apply template", e);
        }
    }

    /**
     * Evaluate a query against an envelope.
     * Supports simple queries like:
     * - id = 'value'
     * - name = 'value'
     * - name = 'value1' AND type = 'value2'
     */
    private boolean matchesQuery(Envelope envelope, String query) {
        // Split by AND
        String[] conditions = query.split("\\s+AND\\s+");

        for (String condition : conditions) {
            if (!matchesCondition(envelope, condition.trim())) {
                return false;
            }
        }

        return true;
    }

    /**
     * Evaluate a single condition against an envelope.
     */
    private boolean matchesCondition(Envelope envelope, String condition) {
        // Parse condition: field = 'value'
        String[] parts = condition.split("\\s*=\\s*");
        if (parts.length != 2) {
            logger.warn("Invalid condition format: {}", condition);
            return false;
        }

        String field = parts[0].trim();
        String expectedValue = parts[1].trim().replaceAll("^'|'$", ""); // Remove quotes

        // Get actual value from envelope
        String actualValue;
        if ("id".equals(field)) {
            actualValue = envelope.id();
        } else {
            // Look in payload
            Object payloadValue = envelope.payload().get(field);
            actualValue = payloadValue != null ? payloadValue.toString() : null;
        }

        return expectedValue.equals(actualValue);
    }

    /**
     * Get the latest non-deleted version of each envelope before the given timestamp.
     */
    private List<Envelope> getLatestVersions(long timestamp) {
        // Add 1ms buffer to account for nanosecond precision in Instant.now()
        // vs millisecond precision in System.currentTimeMillis()
        Instant timestampInstant = Instant.ofEpochMilli(timestamp + 1);
        List<Envelope> results = new ArrayList<>();

        for (List<Envelope> envelopes : db.values()) {
            // Find the latest envelope that is not deleted and created before timestamp
            envelopes.stream()
                    .filter(e -> !e.deleted() && !e.createdAt().isAfter(timestampInstant))
                    .max(Comparator.comparing(Envelope::createdAt))
                    .ifPresent(results::add);
        }

        return results;
    }

    @Override
    public Stash find(Template queryTemplate, Stash args, AuthSession session, long timestamp) {
        String query = processQueryTemplate(args, queryTemplate);

        try {
            List<Envelope> latestVersions = getLatestVersions(timestamp);

            // Find the first matching envelope
            Optional<Envelope> match = latestVersions.stream()
                    .filter(envelope -> matchesQuery(envelope, query))
                    .filter(envelope -> allows(session, envelope))
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
    public List<Stash> findAll(Template queryTemplate, Stash args, AuthSession session, long timestamp) {
        String query = processQueryTemplate(args, queryTemplate);

        int limit = -1;
        if (args.containsKey("limit")) {
            limit = ((Number) args.get("limit")).intValue();
        }

        try {
            List<Envelope> latestVersions = getLatestVersions(timestamp);

            java.util.stream.Stream<Stash> stream = latestVersions.stream()
                    .filter(envelope -> matchesQuery(envelope, query))
                    .filter(envelope -> allows(session, envelope))
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
