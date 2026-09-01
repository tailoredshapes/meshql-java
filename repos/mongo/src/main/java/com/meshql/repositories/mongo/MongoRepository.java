package com.meshql.repositories.mongo;

import com.fasterxml.uuid.Generators;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import com.tailoredshapes.underbar.ocho.UnderBar;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.meshql.repositories.mongo.Converters.documentToEnvelope;
import static com.meshql.repositories.mongo.Converters.envelopeToDocument;
import static com.tailoredshapes.underbar.ocho.UnderBar.filter;
import static com.tailoredshapes.underbar.ocho.UnderBar.map;


public class MongoRepository implements Repository {
    private static final Logger logger = LoggerFactory.getLogger(MongoRepository.class);
    private final MongoCollection<Document> collection;

    /**
     * Constructor for MongoRepository.
     *
     * @param collection MongoDB collection to use for storage
     */
    public MongoRepository(MongoCollection<Document> collection) {
        this.collection = collection;
    }

    /**
     * Applies security filters to a MongoDB query based on authorization tokens.
     *
     * <p>A document with no authorized tokens is public and stays readable by a
     * caller holding credentials of their own. That is the rule
     * {@link Auth#tokensVisibleTo} applies on the Searcher side; without it here the
     * two surfaces disagreed, and a record written without credentials came back
     * through the graphlette while the restlette answered 404.
     *
     * <p>The filter applies to every caller, including one presenting no tokens. It
     * used to be added only when the caller held tokens, which gave an anonymous
     * request an unfiltered read of everything: a document is public because it
     * carries no authorized tokens, never because the caller carries none. A caller
     * that needs to see everything holds {@link Auth#WILDCARD_TOKEN}, and that same
     * token is matched on the document side so a document marked readable by
     * everyone is matched too.
     *
     * @param tokens List of authorization tokens
     * @param filter The existing filter to enhance with security
     * @return The enhanced filter with security constraints
     */
    private Bson secureRead(List<String> tokens, Bson filter) {
        if (Auth.seesEverything(tokens)) {
            return filter;
        }
        LinkedHashSet<String> matched = new LinkedHashSet<>();
        if (tokens != null) {
            matched.addAll(tokens);
        }
        matched.add(Auth.WILDCARD_TOKEN);
        return Filters.and(filter, Filters.or(
                Filters.in("authorizedTokens", matched),
                Filters.exists("authorizedTokens", false),
                Filters.eq("authorizedTokens", null),
                Filters.size("authorizedTokens", 0)));
    }

    @Override
    public Envelope create(Envelope envelope, List<String> tokens) {
        // Generate a new envelope with current timestamp and UUID if needed
        String id = envelope.id() != null ? envelope.id() : Generators.timeBasedGenerator().generate().toString();
        Instant createdAt = Instant.now();

        Envelope newEnvelope = new Envelope(
                id,
                envelope.payload(),
                createdAt,
                false,
                tokens);

        try {
            Document doc = envelopeToDocument(newEnvelope);
            collection.withWriteConcern(WriteConcern.MAJORITY).insertOne(doc);
            return newEnvelope;
        } catch (Exception e) {
            logger.error("Error creating document: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create document", e);
        }
    }

    @Override
    public List<Envelope> createMany(List<Envelope> envelopes, List<String> tokens) {
        Instant createdAt = Instant.now();
        List<Document> documents = new ArrayList<>();
        List<Envelope> createdEnvelopes = new ArrayList<>();

        for (Envelope envelope : envelopes) {
            String id = UUID.randomUUID().toString();
            Envelope newEnvelope = new Envelope(
                    id,
                    envelope.payload(),
                    createdAt,
                    false,
                    tokens);

            documents.add(envelopeToDocument(newEnvelope));
            createdEnvelopes.add(newEnvelope);
        }

        try {
            collection.insertMany(documents);
            return createdEnvelopes;
        } catch (Exception e) {
            logger.error("Error creating multiple documents: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create multiple documents", e);
        }
    }

    @Override
    public Optional<Envelope> read(String id, List<String> tokens, Instant createdAt) {
        if (createdAt == null) {
            createdAt = Instant.now();
        }

        Bson filter = Filters.and(
                Filters.eq("id", id),
                Filters.lte("createdAt", Date.from(createdAt)),
                Filters.eq("deleted", false));

        filter = secureRead(tokens, filter);

        try {
            List<Document> results = collection.find(filter)
                    .sort(Sorts.descending("createdAt"))
                    .into(new ArrayList<>());

            if (results.isEmpty()) {
                return Optional.empty();
            }

            return Optional.ofNullable(documentToEnvelope(results.get(0)));
        } catch (Exception e) {
            logger.error("Error reading document: {}", e.getMessage(), e);
            return Optional.empty();
        }
    }

    @Override
    public List<Envelope> readMany(List<String> ids, List<String> tokens) {
        Bson match = Filters.and(
                Filters.in("id", ids),
                Filters.eq("deleted", false));

        match = secureRead(tokens, match);

        try {
            List<Document> results = collection.aggregate(UnderBar.list(
                    Aggregates.match(match),
                    Aggregates.sort(Sorts.descending("createdAt")),
                    Aggregates.group("$id", new BsonField("doc",  new Document("$first", "$$ROOT"))),
                    Aggregates.replaceRoot("$doc"))).into(new ArrayList<>());

            return results.stream()
                    .map(Converters::documentToEnvelope)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error reading multiple documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Mark a document deleted, if the caller holds a token for it.
     *
     * <p>The return value reports what the update did. It used to report
     * {@code true} unconditionally, so a caller deleting a document it holds no
     * token for got HTTP 200 and a {@code "status": "deleted"} body while the
     * document sat untouched. The data was safe; the answer was not.
     */
    @Override
    public Boolean remove(String id, List<String> tokens) {
        try {
            Bson filter = secureRead(tokens, Filters.eq("id", id));
            return collection.updateMany(filter, Updates.set("deleted", true))
                    .getModifiedCount() > 0;
        } catch (Exception e) {
            logger.error("Error removing document: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Remove each id in turn, so the result says which removals happened.
     *
     * <p>A single bulk update cannot say which of the ids it matched, and reporting
     * {@code true} for all of them hid unauthorized removals the same way
     * {@link #remove} did.
     */
    @Override
    public Map<String, Boolean> removeMany(List<String> ids, List<String> tokens) {
        Map<String, Boolean> results = new HashMap<>();
        for (String id : ids) {
            results.put(id, remove(id, tokens));
        }
        return results;
    }

    @Override
    public List<Envelope> list(List<String> tokens) {
        Bson match = secureRead(tokens, Filters.eq("deleted", false));

        try {
            List<Document> results = collection.aggregate(UnderBar.list(
                    Aggregates.match(match),
                    Aggregates.sort(Sorts.descending("createdAt")),
                    Aggregates.group("$id", new BsonField("doc", new Document("$first", "$$ROOT"))),
                    Aggregates.replaceRoot("$doc"))).into(new ArrayList<>());

            return filter(map(results, Converters::documentToEnvelope), Objects::nonNull);

        } catch (Exception e) {
            logger.error("Error listing documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}