package com.meshql.repositories.mongo;

import com.github.jknack.handlebars.Template;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.Operation;
import com.meshql.core.Searcher;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.tailoredshapes.stash.Stash;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import static com.meshql.repositories.mongo.Converters.documentToEnvelope;

/**
 * MongoDB implementation of the Searcher interface.
 */
public class MongoSearcher implements Searcher {
    private static final Logger logger = LoggerFactory.getLogger(MongoSearcher.class);

    private final MongoCollection<Document> collection;

    /**
     * Constructor for MongoSearcher.
     *
     * @param collection MongoDB collection to search
     */
    public MongoSearcher(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    /** A missing session is never an internal call. It is a caller who may see nothing. */
    private static boolean allows(AuthSession session, Envelope envelope) {
        return session != null && session.isAuthorized(Operation.READ, envelope);
    }

    /**
     * Process a query template with parameters.
     *
     * @param parameters    Parameters to apply to the template
     * @param queryTemplate Handlebars template for the query
     * @return Processed query as a Document
     */
    private Document processQueryTemplate(Stash parameters, Template queryTemplate) {
        try {
            String query = queryTemplate.apply(parameters);
            try {
                // Parse the query string into a Document
                return Document.parse(query);
            } catch (Exception e) {
                logger.error("Failed to create query: Template: {}, Parameters: {}, Query: {}",
                        queryTemplate, parameters, query, e);
                throw new RuntimeException("Failed to parse query", e);
            }
        } catch (IOException e) {
            logger.error("Failed to apply template", e);
            throw new RuntimeException("Failed to apply template", e);
        }
    }

    @Override
    public Stash find(Template queryTemplate, Stash args, AuthSession session, long timestamp) {
        Document query = processQueryTemplate(args, queryTemplate);

        // Add timestamp filter
        query.append("createdAt", new Document("$lt", new Date(timestamp)));
        // Exclude tombstones. Every other Searcher does this (the RDBMS templates carry
        // "AND deleted = 0"); without it a removed record stays readable through GraphQL.
        query.append("deleted", false);

        logger.info("MongoSearcher.find() - Template: {}, Args: {}, Timestamp: {}, Final Query: {}",
                queryTemplate, args, timestamp, query.toJson());

        try {
            List<Document> results = collection.find(query)
                    .sort(Sorts.descending("createdAt"))
                    .into(new ArrayList<>());

            logger.info("MongoSearcher.find() - Found {} results", results.size());

            if (!results.isEmpty()) {
                // Fetch, then ask. The documents come back newest first, so the first
                // the session allows is the answer; stopping at the first document
                // would let one unreadable record hide a readable one behind it.
                for (Document result : results) {
                    Envelope envelope = documentToEnvelope(result);

                    if (allows(session, envelope)) {
                        Stash payload = envelope.payload();
                        if (payload != null) {
                            payload.put("id", envelope.id());
                            payload.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(envelope.createdAt()));
                            return payload;
                        }
                    }
                }
            } else {
                logger.warn("MongoSearcher.find() - No results found for query: {}", query.toJson());
            }

            return new Stash();
        } catch (Exception e) {
            logger.error("Error finding document: {}", e.getMessage(), e);
            return new Stash();
        }
    }

    @Override
    public List<Stash> findAll(Template queryTemplate, Stash args, AuthSession session, long timestamp) {
        Document query = processQueryTemplate(args, queryTemplate);

        // Extract limit from args (reserved argument)
        int limit = -1;
        if (args.containsKey("limit")) {
            limit = ((Number) args.get("limit")).intValue();
        }

        // Add timestamp filter
        query.append("createdAt", new Document("$lt", new Date(timestamp)));
        // Exclude tombstones - see find() above. remove() marks every version of an id
        // deleted, so filtering in the match stage cannot resurrect an older version.
        query.append("deleted", false);

        logger.info("MongoSearcher.findAll() - Template: {}, Args: {}, Timestamp: {}, Final Query: {}",
                queryTemplate, args, timestamp, query.toJson());

        try {
            // Create a custom aggregation pipeline using Document objects directly
            List<Bson> pipeline = new ArrayList<>(Arrays.asList(
                    Aggregates.match(query),
                    Aggregates.sort(Sorts.descending("createdAt")),
                    // Use Document directly for the group stage
                    new Document("$group",
                            new Document("_id", "$id")
                                    .append("doc", new Document("$first", "$$ROOT"))),
                    // Use Document directly for the replaceRoot stage
                    new Document("$replaceRoot",
                            new Document("newRoot", "$doc"))));

            // The limit is deliberately not in the pipeline: it has to truncate the
            // authorized documents, not the fetched ones.
            List<Document> results = collection.aggregate(pipeline).into(new ArrayList<>());

            logger.info("MongoSearcher.findAll() - Found {} results before authorization", results.size());

            java.util.stream.Stream<Stash> authorized = results.stream()
                    .map(Converters::documentToEnvelope)
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
                    .filter(payload -> payload != null);

            if (limit > 0) {
                authorized = authorized.limit(limit);
            }

            List<Stash> finalResults = authorized.collect(Collectors.toList());

            logger.info("MongoSearcher.findAll() - Returning {} results after authorization", finalResults.size());
            if (!finalResults.isEmpty()) {
                logger.info("MongoSearcher.findAll() - First result keys: {}", finalResults.get(0).keySet());
            }

            return finalResults;
        } catch (Exception e) {
            logger.error("Error finding documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}