package com.meshql.repositories.mongo;

import com.github.jknack.handlebars.Template;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
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
    private final Auth authorizer;

    /**
     * Constructor for MongoSearcher.
     *
     * @param collection MongoDB collection to search
     * @param authorizer Authorization service
     */
    public MongoSearcher(MongoCollection<Document> collection, Auth authorizer) {
        this.collection = collection;
        this.authorizer = authorizer;
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
    public Stash find(Template queryTemplate, Stash args, List<String> tokens, long timestamp) {
        Document query = processQueryTemplate(args, queryTemplate);

        // Add timestamp filter
        query.append("createdAt", new Document("$lt", new Date(timestamp)));

        logger.info("MongoSearcher.find() - Template: {}, Args: {}, Timestamp: {}, Final Query: {}",
                queryTemplate, args, timestamp, query.toJson());

        try {
            List<Document> results = collection.find(query)
                    .sort(Sorts.descending("createdAt"))
                    .into(new ArrayList<>());

            logger.info("MongoSearcher.find() - Found {} results", results.size());

            if (!results.isEmpty()) {
                Document result = results.get(0);
                Envelope envelope = documentToEnvelope(result);

                logger.info("MongoSearcher.find() - First result: id={}, payload={}",
                        envelope.id(), envelope.payload());

                if (authorizer.isAuthorized(tokens, envelope)) {
                    Stash payload = envelope.payload();
                    if (payload != null) {
                        payload.put("id", envelope.id());
                        payload.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(envelope.createdAt()));
                        logger.info("MongoSearcher.find() - Returning payload with keys: {}", payload.keySet());
                        return payload;
                    }
                } else {
                    logger.warn("Not authorized to access document: {}", envelope.id());
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
    public List<Stash> findAll(Template queryTemplate, Stash args, List<String> tokens, long timestamp) {
        Document query = processQueryTemplate(args, queryTemplate);

        // Extract limit from args (reserved argument)
        int limit = -1;
        if (args.containsKey("limit")) {
            limit = ((Number) args.get("limit")).intValue();
        }

        // Add timestamp filter
        query.append("createdAt", new Document("$lt", new Date(timestamp)));

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

            if (limit > 0) {
                pipeline.add(Aggregates.limit(limit));
            }

            List<Document> results = collection.aggregate(pipeline).into(new ArrayList<>());

            logger.info("MongoSearcher.findAll() - Found {} results before authorization", results.size());

            List<Stash> finalResults = results.stream()
                    .map(Converters::documentToEnvelope)
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