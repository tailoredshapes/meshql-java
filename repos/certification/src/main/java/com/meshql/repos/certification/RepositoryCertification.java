package com.meshql.repos.certification;

import com.meshql.auth.token.TokenSession;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import com.tailoredshapes.stash.Stash;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.tailoredshapes.stash.Stash.stash;
import static com.tailoredshapes.underbar.ocho.UnderBar.*;
import static org.junit.jupiter.api.Assertions.*;

public abstract class RepositoryCertification {
    protected Repository repository;

    /**
     * The caller every scenario runs as: an ordinary token holder, not a system
     * session, so a repository that ignored the session entirely would still be
     * caught by the suites that use two callers.
     */
    protected final AuthSession session = new TokenSession(list("TOKEN"));

    public abstract void init();

    @BeforeEach
    public void setUp() throws Exception {
        init();
    }

    @Test
    public void createShouldStoreAndReturnTheEnvelope() {
        Stash payload = stash(
                "name", "Create Test",
                "count", 3);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        Envelope result = repository.create(
                new Envelope(null, payload, null, false, null),
                session);

        assertNotNull(result.id());
        assertNotNull(result.createdAt());
        assertTrue(result.createdAt().isAfter(now) || result.createdAt().equals(now));
        assertFalse(result.deleted());
    }

    @Test
    public void readShouldRetrieveAnExistingEnvelopeById() {
        Stash payload = stash("name", "Read Test", "count", 51);

        Envelope createResult = repository.create(new Envelope(null, payload, null, false, null), session);
        String id = createResult.id();

        Optional<Envelope> result = repository.read(id, session, Instant.now());

        assertTrue(result.isPresent());
        assertEquals("Read Test", result.get().payload().get("name"));
    }

    @Test
    public void listShouldRetrieveAllCreatedEnvelopes() {
        List<Envelope> envelopes = list(
                new Envelope(null, stash("name", "test1", "count", 4), null, false, null),
                new Envelope(null, stash("name", "test2", "count", 45), null, false, null),
                new Envelope(null, stash("name", "test3", "count", 2), null, false, null));

        for (Envelope e : envelopes) {
            repository.create(e, session);
        }

        List<Envelope> result = repository.list(session);
        assertEquals(3,result.size());
    }

    @Test
    public void removeShouldDeleteAnEnvelopeById() {
        Stash payload = stash("name", "Remove Test", "count", 51);

        Envelope createResult = repository.create(new Envelope(null, payload, null, false, null), session);
        String id = createResult.id();

        Boolean result = repository.remove(id, session);
        assertTrue(result);

        Optional<Envelope> readResult = repository.read(id, session, Instant.now());
        assertTrue(readResult.isEmpty());
    }

    @Test
    public void createManyShouldStoreMultipleEnvelopes() {
        List<Envelope> envelopes = list(
                new Envelope(null, stash("name", "test1", "count", 4), null, false, null),
                new Envelope(null, stash("name", "test2", "count", 45), null, false, null),
                new Envelope(null, stash("name", "test3", "count", 2), null, false, null));

        List<Envelope> result = repository.createMany(envelopes, session);
        assertEquals(envelopes.size(), result.size());
    }

    @Test
    public void readManyShouldRetrieveMultipleEnvelopesByIds() {
        List<Envelope> envelopes = list(
                new Envelope(null, stash("name", "test1", "count", 4), null, false, null),
                new Envelope(null, stash("name", "test2", "count", 45), null, false, null),
                new Envelope(null, stash("name", "test3", "count", 2), null, false, null));

        List<Envelope> createResult = repository.createMany(envelopes, session);
        List<String> ids = createResult.subList(0, 2).stream()
                .map(Envelope::id)
                .toList();

        List<Envelope> result = repository.readMany(ids, session);
        assertEquals(2, result.size());
    }

    @Test
    public void removeManyShouldDeleteMultipleEnvelopesByIds() {
        List<Envelope> envelopes = list(
                new Envelope(null, stash("name", "test1", "count", 4), null, false, null),
                new Envelope(null, stash("name", "test2", "count", 45), null, false, null),
                new Envelope(null, stash("name", "test3", "count", 2), null, false, null));

        List<Envelope> createResult = repository.createMany(envelopes, session);
        List<String> ids = map(createResult, Envelope::id);

        List<String> toDelete = ids.subList(0, 2);
        Map<String, Boolean> result = repository.removeMany(toDelete, session);

        for (String id : toDelete) {
            assertTrue(result.get(id));
        }

        List<Envelope> listed = repository.list(session);
        assertEquals(1, listed.size());
    }

    @Test
    public void shouldAllowMultipleVersionsOfTheSameIdAndReadThemByTimestamp() throws InterruptedException {

        Envelope doc1 = repository.create(
                new Envelope(null, stash("version", "v1", "msg", "First version"), null, false, null),
                session);

        Optional<Envelope> docRead1 = repository.read(doc1.id(), session, Instant.now());
        assertTrue(docRead1.isPresent());

        // Wait for a short period to ensure different timestamps
        Thread.sleep(50);

        Envelope doc2 = repository.create(
                new Envelope(doc1.id(), stash("version", "v2", "msg", "Second version"), null, false,
                        doc1.auth()),
                session);

        Optional<Envelope> docRead2 = repository.read(doc1.id(), session, Instant.now());
        Optional<Envelope> docRead3 = repository.read(
                doc1.id(),
                session,
                doc2.createdAt().minusMillis(10));

        assertTrue(docRead2.isPresent());
        assertTrue(docRead3.isPresent());

        assertEquals("v1", docRead1.get().payload().get("version"));
        assertEquals("v2", docRead2.get().payload().get("version"));
        assertEquals("v1", docRead3.get().payload().get("version"));
    }

    @Test
    public void shouldOnlyListTheLatestVersionOfAnId() throws InterruptedException {

        Envelope doc1 = repository.create(
                new Envelope(null, stash("version", "v1", "msg", "First version"), null, false, null),
                session);

        // Wait for a short period to ensure different timestamps
        Thread.sleep(50);

        repository.create(
                new Envelope(doc1.id(), stash("version", "v2", "msg", "Second version"), null, false,
                        doc1.auth()),
                session);

        List<Envelope> allDocs = repository.list(session);

        Envelope ourDoc = first(filter(allDocs, e -> e.id().equals(doc1.id())));

        assertNotNull(ourDoc);
        assertEquals("v2", ourDoc.payload().get("version"));
    }
}
