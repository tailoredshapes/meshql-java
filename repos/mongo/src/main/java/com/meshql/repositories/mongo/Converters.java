package com.meshql.repositories.mongo;

import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;
import org.bson.Document;

import java.time.Instant;
import java.util.*;

public interface Converters {
     static Document stashToDocument(Stash stash) {
        Document doc = new Document();
        for (String key : stash.keySet()) {
            Object value = stash.get(key);
            if (value instanceof Stash) {
                doc.put(key, stashToDocument((Stash) value));
            } else {
                doc.put(key, value);
            }
        }
        return doc;
    }

    static Stash documentToStash(Document doc) {
        Map<String, Object> map = new HashMap<>();
        for (String key : doc.keySet()) {
            Object value = doc.get(key);
            if (value instanceof Document) {
                map.put(key, documentToStash((Document) value));
            } else {
                map.put(key, value);
            }
        }
        // Create a new Stash with the map contents
        Stash stash = new Stash();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            stash.put(entry.getKey(), entry.getValue());
        }
        return stash;
    }

    static Document envelopeToDocument(Envelope envelope) {
        Document doc = new Document();
        doc.put("id", envelope.id());
        doc.put("createdAt", Date.from(envelope.createdAt()));
        doc.put("deleted", envelope.deleted());

        // The auth plugin's mark, stored verbatim alongside the payload in the same
        // document, and never read by anything in this package.
        if (envelope.auth() != null) {
            doc.put("auth", stashToDocument(envelope.auth()));
        }

        if (envelope.payload() != null) {
            doc.put("payload", stashToDocument(envelope.payload()));
        }

        return doc;
    }

    static Envelope documentToEnvelope(Document doc) {
        if (doc == null) {
            return null;
        }

        String id = doc.getString("id");
        Instant createdAt = doc.getDate("createdAt").toInstant();
        boolean deleted = doc.getBoolean("deleted", false);

        // Convert MongoDB Document to Stash
        Document payloadDoc = doc.get("payload", Document.class);
        Stash payload = payloadDoc != null ? documentToStash(payloadDoc) : null;

        Document authDoc = doc.get("auth", Document.class);
        Stash auth = authDoc != null ? documentToStash(authDoc) : null;

        return new Envelope(id, payload, createdAt, deleted, auth);
    }
}
