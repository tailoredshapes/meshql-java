package com.meshql.repositories.merksql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared store backed by a merkql broker via JNI.
 * Maintains an in-memory index for fast reads while persisting
 * all writes to the merkql broker for durability.
 */
public class MerkSqlStore {
    private static final Logger logger = LoggerFactory.getLogger(MerkSqlStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final long handle;
    private final String topic;
    private final Map<String, List<Envelope>> db = new ConcurrentHashMap<>();

    public MerkSqlStore(long handle, String topic) {
        this.handle = handle;
        this.topic = topic;
    }

    public Map<String, List<Envelope>> getDb() {
        return db;
    }

    /**
     * Store an envelope: write to the broker and update the in-memory index.
     */
    public void produce(Envelope envelope) {
        String json = envelopeToJson(envelope);
        MerkSqlNative.produce(handle, topic, envelope.id(), json);

        db.compute(envelope.id(), (key, existing) -> {
            List<Envelope> list = existing != null ? existing : new ArrayList<>();
            list.add(envelope);
            return list;
        });
    }

    /**
     * Mark all versions of an ID as deleted in both broker and in-memory index.
     */
    public boolean markDeleted(String id) {
        List<Envelope> envelopes = db.get(id);
        if (envelopes == null || envelopes.isEmpty()) {
            return false;
        }

        List<Envelope> updated = new ArrayList<>();
        for (Envelope e : envelopes) {
            Envelope deleted = new Envelope(e.id(), e.payload(), e.createdAt(), true, e.auth());
            updated.add(deleted);
        }
        db.put(id, updated);

        // Produce a deletion marker to the broker
        Envelope latest = envelopes.get(envelopes.size() - 1);
        Envelope tombstone = new Envelope(id, latest.payload(), Instant.now(), true, latest.auth());
        String json = envelopeToJson(tombstone);
        MerkSqlNative.produce(handle, topic, id, json);

        return true;
    }

    @SuppressWarnings("unchecked")
    private static String envelopeToJson(Envelope envelope) {
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("payload", envelope.payload() != null ? MAPPER.writeValueAsString(stashToMap(envelope.payload())) : "{}");
            map.put("created_at", envelope.createdAt().toEpochMilli());
            map.put("deleted", envelope.deleted());
            // The auth plugin's mark, persisted verbatim in the same write as the
            // payload. Nothing here reads it.
            map.put("auth", envelope.auth() != null
                    ? MAPPER.writeValueAsString(stashToMap(envelope.auth()))
                    : "{}");
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize envelope to JSON", e);
        }
    }

    private static Map<String, Object> stashToMap(Stash stash) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String key : stash.keySet()) {
            Object value = stash.get(key);
            if (value instanceof Stash) {
                map.put(key, stashToMap((Stash) value));
            } else {
                map.put(key, value);
            }
        }
        return map;
    }
}
