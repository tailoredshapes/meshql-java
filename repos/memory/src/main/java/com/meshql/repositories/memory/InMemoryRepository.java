package com.meshql.repositories.memory;

import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.NotAuthorizedException;
import com.meshql.core.Operation;
import com.meshql.core.Repository;
import com.tailoredshapes.stash.Stash;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * An in-memory implementation of the Repository interface.
 * Stores envelopes in memory with version history support.
 *
 * <p>It holds no credentials and knows no authorization rule. It fetches, then
 * asks the session. Read, list and remove once ignored their {@code tokens}
 * argument outright, so one caller's records were readable, listable and
 * deletable by any other; there is now nothing here for an adapter to get wrong,
 * because there is nothing here to reimplement.
 */
public class InMemoryRepository implements Repository {
    private final Map<String, List<Envelope>> db;

    public InMemoryRepository(InMemoryStore store) {
        this.db = store.getDb();
    }

    /**
     * Constructor for backward compatibility.
     */
    public InMemoryRepository() {
        this.db = new ConcurrentHashMap<>();
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

        // Use provided ID, or fall back to name from payload, or generate UUID
        String id = envelope.id();
        if (id == null && envelope.payload() != null && envelope.payload().containsKey("name")) {
            id = (String) envelope.payload().get("name");
        }
        if (id == null) {
            id = UUID.randomUUID().toString();
        }

        Stash payload = envelope.payload();
        Instant now = Instant.now();

        Envelope newEnvelope = session.stamp(new Envelope(id, payload, now, false, envelope.auth()));
        if (!session.isAuthorized(Operation.CREATE, newEnvelope)) {
            throw new NotAuthorizedException("Not authorized to create " + id);
        }

        db.compute(id, (key, existingEnvelopes) -> {
            List<Envelope> envelopes = existingEnvelopes != null ? existingEnvelopes : new ArrayList<>();
            envelopes.add(newEnvelope);
            return envelopes;
        });

        return newEnvelope;
    }

    @Override
    public List<Envelope> createMany(List<Envelope> payloads, AuthSession session) {
        return payloads.stream()
                .map(envelope -> create(envelope, session))
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Envelope> read(String id, AuthSession session, Instant createdAt) {
        return latestAt(id, createdAt).filter(e -> allows(session, Operation.READ, e));
    }

    /** The latest live version of an id at an instant, before anyone is asked about it. */
    private Optional<Envelope> latestAt(String id, Instant createdAt) {
        List<Envelope> envelopes = db.get(id);
        if (envelopes == null || envelopes.isEmpty()) {
            return Optional.empty();
        }

        // Add 1ms buffer to account for nanosecond precision in Instant.now()
        // vs millisecond precision in timestamp capture
        Instant adjustedTimestamp = createdAt.plusMillis(1);

        return envelopes.stream()
                .filter(e -> !e.deleted() && !e.createdAt().isAfter(adjustedTimestamp))
                .max(Comparator.comparing(Envelope::createdAt));
    }

    @Override
    public List<Envelope> readMany(List<String> ids, AuthSession session) {
        return ids.stream()
                .map(id -> read(id, session, Instant.now()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }

    @Override
    public List<Envelope> list(AuthSession session) {
        List<Envelope> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (List<Envelope> envelopeList : db.values()) {
            List<Envelope> nonDeletedEnvelopes = envelopeList.stream()
                    .filter(e -> !e.deleted())
                    .collect(Collectors.toList());

            if (!nonDeletedEnvelopes.isEmpty()) {
                nonDeletedEnvelopes.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
                Envelope latestEnvelope = nonDeletedEnvelopes.get(0);

                if (!seen.contains(latestEnvelope.id()) && allows(session, Operation.READ, latestEnvelope)) {
                    seen.add(latestEnvelope.id());
                    result.add(latestEnvelope);
                }
            }
        }

        return result;
    }

    @Override
    public Boolean remove(String id, AuthSession session) {
        List<Envelope> envelopes = db.get(id);
        if (envelopes == null || envelopes.isEmpty()) {
            return false;
        }

        // A caller can only remove what the plugin lets it remove.
        boolean removable = latestAt(id, Instant.now())
                .filter(e -> allows(session, Operation.REMOVE, e))
                .isPresent();
        if (!removable) {
            return false;
        }

        // Mark all versions as deleted
        List<Envelope> updatedEnvelopes = envelopes.stream()
                .map(e -> new Envelope(e.id(), e.payload(), e.createdAt(), true, e.auth()))
                .collect(Collectors.toList());

        db.put(id, updatedEnvelopes);
        return true;
    }

    @Override
    public Map<String, Boolean> removeMany(List<String> ids, AuthSession session) {
        Map<String, Boolean> results = new HashMap<>();
        for (String id : ids) {
            results.put(id, remove(id, session));
        }
        return results;
    }
}
