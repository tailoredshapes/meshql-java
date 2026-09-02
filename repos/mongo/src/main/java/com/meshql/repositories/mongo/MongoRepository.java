package com.meshql.repositories.mongo;

import com.fasterxml.uuid.Generators;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.NotAuthorizedException;
import com.meshql.core.Operation;
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

/**
 * MongoDB storage.
 *
 * <p>No query here carries a visibility filter and no method holds credentials.
 * The auth plugin's mark is opaque, so it cannot become a {@code $match}; the
 * repository fetches, then asks the session. The mark is part of the same
 * document as the payload, so authorization is never a second write.
 */
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

    /** A missing session is never an internal call. It is a caller who may see nothing. */
    private static boolean allows(AuthSession session, Operation operation, Envelope envelope) {
        return session != null && session.isAuthorized(operation, envelope);
    }

    @Override
    public Envelope create(Envelope envelope, AuthSession session) {
        if (session == null) {
            throw new NotAuthorizedException("A write needs a session; there is no unset session");
        }

        String id = envelope.id() != null ? envelope.id() : Generators.timeBasedGenerator().generate().toString();
        Instant createdAt = Instant.now();

        Envelope newEnvelope = session.stamp(
                new Envelope(id, envelope.payload(), createdAt, false, envelope.auth()));
        if (!session.isAuthorized(Operation.CREATE, newEnvelope)) {
            throw new NotAuthorizedException("Not authorized to create " + id);
        }

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
    public List<Envelope> createMany(List<Envelope> envelopes, AuthSession session) {
        if (session == null) {
            throw new NotAuthorizedException("A write needs a session; there is no unset session");
        }

        Instant createdAt = Instant.now();
        List<Document> documents = new ArrayList<>();
        List<Envelope> createdEnvelopes = new ArrayList<>();

        for (Envelope envelope : envelopes) {
            String id = UUID.randomUUID().toString();
            Envelope newEnvelope = session.stamp(
                    new Envelope(id, envelope.payload(), createdAt, false, envelope.auth()));
            if (!session.isAuthorized(Operation.CREATE, newEnvelope)) {
                throw new NotAuthorizedException("Not authorized to create " + id);
            }

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
    public Optional<Envelope> read(String id, AuthSession session, Instant createdAt) {
        return latestAt(id, createdAt).filter(e -> allows(session, Operation.READ, e));
    }

    /** The latest live version of an id at an instant, before anyone is asked about it. */
    private Optional<Envelope> latestAt(String id, Instant createdAt) {
        Instant at = createdAt == null ? Instant.now() : createdAt;

        Bson filter = Filters.and(
                Filters.eq("id", id),
                Filters.lte("createdAt", Date.from(at)),
                Filters.eq("deleted", false));

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
    public List<Envelope> readMany(List<String> ids, AuthSession session) {
        Bson match = Filters.and(
                Filters.in("id", ids),
                Filters.eq("deleted", false));

        try {
            List<Document> results = collection.aggregate(UnderBar.list(
                    Aggregates.match(match),
                    Aggregates.sort(Sorts.descending("createdAt")),
                    Aggregates.group("$id", new BsonField("doc",  new Document("$first", "$$ROOT"))),
                    Aggregates.replaceRoot("$doc"))).into(new ArrayList<>());

            return results.stream()
                    .map(Converters::documentToEnvelope)
                    .filter(Objects::nonNull)
                    .filter(envelope -> allows(session, Operation.READ, envelope))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error reading multiple documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Mark a document deleted, if the plugin lets this caller remove it.
     *
     * <p>The return value reports what the update did. It used to report
     * {@code true} unconditionally, so a caller deleting a document it was not
     * entitled to got HTTP 200 and a {@code "status": "deleted"} body while the
     * document sat untouched. The data was safe; the answer was not.
     */
    @Override
    public Boolean remove(String id, AuthSession session) {
        try {
            boolean removable = latestAt(id, Instant.now())
                    .filter(e -> allows(session, Operation.REMOVE, e))
                    .isPresent();
            if (!removable) {
                return false;
            }
            return collection.updateMany(Filters.eq("id", id), Updates.set("deleted", true))
                    .getModifiedCount() > 0;
        } catch (Exception e) {
            logger.error("Error removing document: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Remove each id in turn, so the result says which removals happened.
     */
    @Override
    public Map<String, Boolean> removeMany(List<String> ids, AuthSession session) {
        Map<String, Boolean> results = new HashMap<>();
        for (String id : ids) {
            results.put(id, remove(id, session));
        }
        return results;
    }

    @Override
    public List<Envelope> list(AuthSession session) {
        Bson match = Filters.eq("deleted", false);

        try {
            List<Document> results = collection.aggregate(UnderBar.list(
                    Aggregates.match(match),
                    Aggregates.sort(Sorts.descending("createdAt")),
                    Aggregates.group("$id", new BsonField("doc", new Document("$first", "$$ROOT"))),
                    Aggregates.replaceRoot("$doc"))).into(new ArrayList<>());

            return results.stream()
                    .map(Converters::documentToEnvelope)
                    .filter(Objects::nonNull)
                    .filter(envelope -> allows(session, Operation.READ, envelope))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Error listing documents: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
