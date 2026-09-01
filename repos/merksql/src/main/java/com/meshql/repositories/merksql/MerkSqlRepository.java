package com.meshql.repositories.merksql;

import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import com.tailoredshapes.stash.Stash;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Repository backed by a merkql broker via JNI.
 * Follows the same versioning semantics as InMemoryRepository.
 */
public class MerkSqlRepository implements Repository {
    private final MerkSqlStore store;

    public MerkSqlRepository(MerkSqlStore store) {
        this.store = store;
    }

    /**
     * The visibility predicate every other backend applies inside its query.
     *
     * <p>Read, list and remove used to ignore their {@code tokens} argument
     * entirely, so one caller's records were readable, listable and deletable by
     * any other. {@link Auth#tokensVisibleTo} is the same rule the Searcher side
     * applies, so the two surfaces agree.
     */
    private static boolean visibleTo(Envelope envelope, List<String> tokens) {
        return Auth.tokensVisibleTo(envelope.authorizedTokens(), tokens);
    }

    @Override
    public Envelope create(Envelope envelope, List<String> tokens) {
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
        store.produce(newEnvelope);
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
        List<Envelope> envelopes = store.getDb().get(id);
        if (envelopes == null || envelopes.isEmpty()) {
            return Optional.empty();
        }

        Instant adjustedTimestamp = createdAt.plusMillis(1);

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

        for (List<Envelope> envelopeList : store.getDb().values()) {
            List<Envelope> nonDeleted = envelopeList.stream()
                    .filter(e -> !e.deleted())
                    .collect(Collectors.toList());

            if (!nonDeleted.isEmpty()) {
                nonDeleted.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
                Envelope latest = nonDeleted.get(0);

                if (!seen.contains(latest.id()) && visibleTo(latest, tokens)) {
                    seen.add(latest.id());
                    result.add(latest);
                }
            }
        }

        return result;
    }

    /** A caller can only remove what it can read. */
    @Override
    public Boolean remove(String id, List<String> tokens) {
        if (read(id, tokens, Instant.now()).isEmpty()) {
            return false;
        }
        return store.markDeleted(id);
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
