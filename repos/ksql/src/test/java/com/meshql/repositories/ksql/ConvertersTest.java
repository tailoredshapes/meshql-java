package com.meshql.repositories.ksql;

import com.meshql.auth.token.TokenMark;
import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.meshql.repositories.ksql.Converters.*;
import static com.tailoredshapes.stash.Stash.stash;
import static org.junit.jupiter.api.Assertions.*;

public class ConvertersTest {

    @Test
    void envelopeToJsonShouldSerializeCorrectly() {
        Instant now = Instant.now();
        Stash payload = stash("name", "Test", "count", 3);
        Envelope envelope = new Envelope("abc-123", payload, now, false, TokenMark.of(List.of("TOKEN")));

        String json = envelopeToJson(envelope);

        assertNotNull(json);
        assertTrue(json.contains("\"created_at\":" + now.toEpochMilli()));
        assertTrue(json.contains("\"deleted\":false"));
        assertTrue(json.contains("\"payload\""));
        assertTrue(json.contains("\"authorized_tokens\""));
    }

    @Test
    void envelopeToJsonShouldHandleNullPayload() {
        Instant now = Instant.now();
        Envelope envelope = new Envelope("abc-123", null, now, false, null);

        String json = envelopeToJson(envelope);

        assertNotNull(json);
        assertTrue(json.contains("\"payload\":\"{}\""));
        assertTrue(json.contains("\"authorized_tokens\":\"{}\""));
    }

    @Test
    void rowToEnvelopeShouldDeserializeUppercaseKeys() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ID", "abc-123");
        row.put("PAYLOAD", "{\"name\":\"Test\",\"count\":3}");
        row.put("CREATED_AT", 1707000000000L);
        row.put("DELETED", false);
        row.put("AUTHORIZED_TOKENS", "{\"tokens\":[\"TOKEN\"]}");

        Envelope envelope = rowToEnvelope("abc-123", row);

        assertEquals("abc-123", envelope.id());
        assertEquals("Test", envelope.payload().get("name"));
        assertEquals(3, ((Number) envelope.payload().get("count")).intValue());
        assertEquals(Instant.ofEpochMilli(1707000000000L), envelope.createdAt());
        assertFalse(envelope.deleted());
        assertEquals(List.of("TOKEN"), TokenMark.tokensOf(envelope));
    }

    @Test
    void rowToEnvelopeShouldHandleLowercaseKeys() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "def-456");
        row.put("payload", "{\"name\":\"Lower\"}");
        row.put("created_at", 1707000000000L);
        row.put("deleted", true);
        row.put("authorized_tokens", "{\"tokens\":[\"A\",\"B\"]}");

        Envelope envelope = rowToEnvelope("def-456", row);

        assertEquals("def-456", envelope.id());
        assertEquals("Lower", envelope.payload().get("name"));
        assertTrue(envelope.deleted());
        assertEquals(List.of("A", "B"), TokenMark.tokensOf(envelope));
    }

    @Test
    void parsePayloadShouldHandleNestedObjects() {
        String json = "{\"name\":\"Test\",\"nested\":{\"key\":\"value\"}}";

        Stash result = parsePayload(json);

        assertEquals("Test", result.get("name"));
        assertTrue(result.get("nested") instanceof Stash);
        assertEquals("value", ((Stash) result.get("nested")).get("key"));
    }

    @Test
    void parsePayloadShouldHandleEmptyString() {
        Stash result = parsePayload("");
        assertTrue(result.isEmpty());
    }

    @Test
    void parsePayloadShouldHandleNull() {
        Stash result = parsePayload(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void parseAuthShouldReadTheMarkBackVerbatim() {
        Stash auth = parseAuth("{\"tokens\":[\"A\",\"B\",\"C\"]}");
        assertEquals(List.of("A", "B", "C"), auth.get("tokens"));
    }

    @Test
    void parseAuthShouldHandleAnEmptyMark() {
        Stash auth = parseAuth("{}");
        assertTrue(auth.isEmpty());
    }

    @Test
    void parseAuthShouldHandleNull() {
        assertNull(parseAuth(null));
    }

    @Test
    void stashToMapAndBackShouldRoundTrip() {
        Stash original = stash("name", "Test", "count", 42);
        Map<String, Object> map = stashToMap(original);
        Stash result = mapToStash(map);

        assertEquals("Test", result.get("name"));
        assertEquals(42, result.get("count"));
    }

    @Test
    void schemaColumnParsingShouldWork() {
        List<String> columns = KsqlHttpClient.parseSchemaColumns(
                "`ID` STRING KEY, `PAYLOAD` STRING, `CREATED_AT` BIGINT, `DELETED` BOOLEAN, `AUTHORIZED_TOKENS` STRING");

        assertEquals(5, columns.size());
        assertEquals("ID", columns.get(0));
        assertEquals("PAYLOAD", columns.get(1));
        assertEquals("CREATED_AT", columns.get(2));
        assertEquals("DELETED", columns.get(3));
        assertEquals("AUTHORIZED_TOKENS", columns.get(4));
    }

    @Test
    void schemaColumnParsingShouldHandleEmpty() {
        List<String> columns = KsqlHttpClient.parseSchemaColumns("");
        assertTrue(columns.isEmpty());
    }
}
