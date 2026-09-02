package com.meshql.repositories.merksql;

import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.NotAuthorizedException;
import com.meshql.core.Operation;
import com.meshql.core.Repository;
import com.tailoredshapes.stash.Stash;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Repository backed by a merkql broker via JNI.
 * Follows the same versioning semantics as InMemoryRepository.
 *
 * <p>It holds no credentials and knows no authorization rule: it fetches, then
 * asks the session. Read, list and remove once ignored their {@code tokens}
 * argument entirely, so one caller's records were readable, listable and
 * deletable by any other.
 */
public class MerkSqlRepository implements Repository {
    private final MerkSqlStore store;

    public MerkSqlRepository(MerkSqlStore store) {
        this.store = store;
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

        store.produce(newEnvelope);
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
        List<Envelope> envelopes = store.getDb().get(id);
        if (envelopes == null || envelopes.isEmpty()) {
            return Optional.empty();
        }

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

        for (List<Envelope> envelopeList : store.getDb().values()) {
            List<Envelope> nonDeleted = envelopeList.stream()
                    .filter(e -> !e.deleted())
                    .collect(Collectors.toList());

            if (!nonDeleted.isEmpty()) {
                nonDeleted.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
                Envelope latest = nonDeleted.get(0);

                if (!seen.contains(latest.id()) && allows(session, Operation.READ, latest)) {
                    seen.add(latest.id());
                    result.add(latest);
                }
            }
        }

        return result;
    }

    /** A caller can only remove what the plugin lets it remove. */
    @Override
    public Boolean remove(String id, AuthSession session) {
        boolean removable = latestAt(id, Instant.now())
                .filter(e -> allows(session, Operation.REMOVE, e))
                .isPresent();
        if (!removable) {
            return false;
        }
        return store.markDeleted(id);
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
