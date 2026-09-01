package com.meshql.repositories.memory;

import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import com.tailoredshapes.stash.Stash;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * An in-memory implementation of the Repository interface.
 * Stores envelopes in memory with version history support.
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

    /**
     * The visibility predicate every other backend applies inside its query.
     *
     * <p>An envelope is visible when it carries no authorized tokens - a record
     * written without credentials is public - or when the caller holds one of the
     * tokens it carries, or when either side holds the wildcard. It is the same
     * rule {@link Auth#tokensVisibleTo} applies on the Searcher side.
     *
     * <p>A caller presenting no tokens used to be treated as an internal or system
     * read and was not filtered at all, so an anonymous HTTP request read every
     * record any credentialed caller had written. A reader that needs to see
     * everything now says so with the wildcard token.
     *
     * <p>Until the shared authorization contract was bound to this backend, read,
     * list and remove ignored their {@code tokens} argument entirely, so one
     * caller's records were readable, listable and deletable by any other.
     */
    private static boolean visibleTo(Envelope envelope, List<String> tokens) {
        return Auth.tokensVisibleTo(envelope.authorizedTokens(), tokens);
    }

    @Override
    public Envelope create(Envelope envelope, List<String> tokens) {
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
        List<String> authTokens = tokens != null ? tokens : List.of();

        Envelope newEnvelope = new Envelope(id, payload, now, false, authTokens);

        db.compute(id, (key, existingEnvelopes) -> {
            List<Envelope> envelopes = existingEnvelopes != null ? existingEnvelopes : new ArrayList<>();
            envelopes.add(newEnvelope);
            return envelopes;
        });

        return newEnvelope;
    }
    
    @Override
    public List<Envelope> createMany(List<Envelope> payloads, List<String> tokens) {
        return payloads.stream()
                .map(envelope -> create(envelope, tokens))
                .collect(Collectors.toList());
    }
    
    @Override
    public Optional<Envelope> read(String id, List<String> tokens, Instant createdAt) {
        List<Envelope> envelopes = db.get(id);
        if (envelopes == null || envelopes.isEmpty()) {
            return Optional.empty();
        }

        // Add 1ms buffer to account for nanosecond precision in Instant.now()
        // vs millisecond precision in timestamp capture
        Instant adjustedTimestamp = createdAt.plusMillis(1);

        // Find the latest envelope that is not deleted and created before or at the specified timestamp
        return envelopes.stream()
                .filter(e -> !e.deleted() && !e.createdAt().isAfter(adjustedTimestamp))
                .max(Comparator.comparing(Envelope::createdAt))
                .filter(e -> visibleTo(e, tokens));
    }
    
    @Override
    public List<Envelope> readMany(List<String> ids, List<String> tokens) {
        return ids.stream()
                .map(id -> read(id, tokens, Instant.now()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
    }
    
    @Override
    public List<Envelope> list(List<String> tokens) {
        List<Envelope> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        
        // For each ID in the database
        for (List<Envelope> envelopeList : db.values()) {
            // Filter out deleted envelopes
            List<Envelope> nonDeletedEnvelopes = envelopeList.stream()
                    .filter(e -> !e.deleted())
                    .collect(Collectors.toList());
            
            if (!nonDeletedEnvelopes.isEmpty()) {
                // Sort by createdAt descending to get the latest version
                nonDeletedEnvelopes.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
                Envelope latestEnvelope = nonDeletedEnvelopes.get(0);
                
                // Add the latest envelope if it hasn't been added yet
                if (!seen.contains(latestEnvelope.id()) && visibleTo(latestEnvelope, tokens)) {
                    seen.add(latestEnvelope.id());
                    result.add(latestEnvelope);
                }
            }
        }
        
        return result;
    }
    
    @Override
    public Boolean remove(String id, List<String> tokens) {
        List<Envelope> envelopes = db.get(id);
        if (envelopes == null || envelopes.isEmpty()) {
            return false;
        }

        // A caller can only remove what it can read.
        boolean removable = envelopes.stream()
                .filter(e -> !e.deleted())
                .max(Comparator.comparing(Envelope::createdAt))
                .filter(e -> visibleTo(e, tokens))
                .isPresent();
        if (!removable) {
            return false;
        }

        // Mark all versions as deleted
        List<Envelope> updatedEnvelopes = envelopes.stream()
                .map(e -> new Envelope(e.id(), e.payload(), e.createdAt(), true, e.authorizedTokens()))
                .collect(Collectors.toList());
        
        db.put(id, updatedEnvelopes);
        return true;
    }
    
    @Override
    public Map<String, Boolean> removeMany(List<String> ids, List<String> tokens) {
        Map<String, Boolean> results = new HashMap<>();
        for (String id : ids) {
            results.put(id, remove(id, tokens));
        }
        return results;
    }
} 