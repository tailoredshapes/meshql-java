package com.meshql.repos.certification;

import com.github.jknack.handlebars.Template;
import com.meshql.auth.token.TokenSession;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import com.meshql.core.Searcher;
import com.tailoredshapes.stash.Stash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.tailoredshapes.stash.Stash.stash;
import static com.tailoredshapes.underbar.ocho.UnderBar.list;
import static org.junit.jupiter.api.Assertions.*;

public abstract class SearcherCertification {

    public record SearcherTemplates(
            Template findById,
            Template findByName,
            Template findAllByType,
            Template findByNameAndType) {
    }

    public SearcherTemplates templates;
    /** The caller every scenario runs as: an ordinary token holder. */
    public AuthSession session = new TokenSession(list("TOKEN"));
    public Repository repository;
    public Searcher searcher;
    public List<Envelope> saved = new ArrayList<>();

    public abstract void init();

    @BeforeEach
    public void setUp() throws Exception {
        List<Envelope> testData = List.of(
                new Envelope(null, stash("name", "Bruce", "count", 1, "type", "A"), null, false, null),
                new Envelope(null,stash("name", "Charlie", "count", 2, "type", "A"), null, false, null),
                new Envelope(null,stash("name", "Danny", "count", 3, "type", "A"), null, false, null),
                new Envelope(null,stash("name", "Ewan", "count", 4, "type", "A"), null, false, null),
                new Envelope(null,stash("name", "Fred", "count", 5, "type", "B"), null, false, null),
                new Envelope(null,stash("name", "Greg", "count", 6, "type", "B"), null, false, null),
                new Envelope(null,stash("name", "Henry", "count", 7, "type", "B"), null, false, null),
                new Envelope(null,stash("name", "Ian", "count", 8, "type", "B"), null, false, null),
                new Envelope(null,stash("name", "Gretchen", "count", 9, "type", "B"), null, false, null),
                new Envelope(null,stash("name", "Casie", "count", 9, "type", "A"), null, false, null)
        );

        init();

        saved = repository.createMany(new ArrayList<>(testData), session);

        // Find Gretchen and remove her
        Envelope gretchen = saved.stream()
                .filter(e -> "Gretchen".equals(e.payload().get("name")))
                .findFirst()
                .orElseThrow();
        repository.remove(gretchen.id(), session);

        // Find Casie and update to Cassie
        Envelope cassie = saved.stream()
                .filter(e -> "Casie".equals(e.payload().get("name")))
                .findFirst()
                .orElseThrow();

        Envelope updatedCass = new Envelope(
                cassie.id(),
                stash("name", "Cassie", "count", 10, "type", "A"),
                null,
                false,
                null
        );

        repository.create(updatedCass, session);
    }

    @Test
    public void shouldReturnEmptyResultForNonExistentId() {
        String id = "non-existent-id";

        Stash result = searcher.find(
                templates.findById,
                stash("id", id),
                session,
                System.currentTimeMillis()
        );

        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldFindById() {
        String id = saved.get(0).id();

        Stash result = searcher.find(
                templates.findById,
                stash("id", id),
                session,
                System.currentTimeMillis()
        );

        assertEquals(saved.get(0).payload().get("name"), result.get("name"));
        assertEquals(1.0, result.asDouble("count"));
    }

    @Test
    public void shouldFindByName() {
        String id = (String) saved.get(3).payload().get("name");

        Stash result = searcher.find(
                templates.findByName,
                stash("id", id),
                session,
                System.currentTimeMillis()
        );

        assertEquals(saved.get(3).payload().get("name"), result.get("name"));
    }

    @Test
    public void shouldFindAllByType() {
        String id = "A";

        List<Stash> result = searcher.findAll(
                templates.findAllByType,
                stash("id", id),
                session,
                System.currentTimeMillis()
        );

        assertEquals(5, result.size());

        Stash charlie = result.stream()
                .filter(f -> "Charlie".equals(f.get("name")))
                .findFirst()
                .orElse(null);

        assertNotNull(charlie);
        assertEquals(2.0, charlie.asDouble("count"));
    }

    @Test
    public void shouldFindAllByTypeAndName() {
        List<Stash> result = searcher.findAll(
                templates.findByNameAndType,
                stash("id", "foo", "name", "Henry", "type", "B"),
                session,
                System.currentTimeMillis()
        );

        assertEquals(1, result.size());
        assertEquals("Henry", result.get(0).get("name"));
    }

    @Test
    public void shouldReturnEmptyArrayForNonExistentType() {
        String id = "C"; // Type "C" does not exist in the test data

        List<Stash> result = searcher.findAll(
                templates.findAllByType,
                stash("id", id),
                session,
                System.currentTimeMillis()
        );

        assertTrue(result.isEmpty());
    }

    @Test
    public void shouldRespectLimitArgument() {
        // Type "A" has 5 documents (Bruce, Charlie, Danny, Ewan, Cassie)
        List<Stash> unlimited = searcher.findAll(
                templates.findAllByType,
                stash("id", "A"),
                session,
                System.currentTimeMillis()
        );
        assertEquals(5, unlimited.size());

        List<Stash> limited = searcher.findAll(
                templates.findAllByType,
                stash("id", "A", "limit", 2),
                session,
                System.currentTimeMillis()
        );
        assertEquals(2, limited.size());
    }

    @Test
    public void shouldHandleSearchWithEmptyQueryParameters() {
        List<Stash> result = searcher.findAll(
                templates.findByNameAndType,
                new Stash(),
                session,
                System.currentTimeMillis()
        );

        assertTrue(result.isEmpty());
    }

    /**
     * "Honesty" as-of field: opt-in via GraphQL schema, mirroring how "id"
     * already works. find/findAll must merge createdAt (ISO-8601 instant)
     * into the returned Stash so a `.graphql` type can declare
     * `createdAt: String` and get it resolved for free.
     */
    @Test
    public void shouldIncludeCreatedAtInResults() {
        String id = saved.get(0).id();

        Stash found = searcher.find(
                templates.findById,
                stash("id", id),
                session,
                System.currentTimeMillis()
        );

        assertTrue(found.containsKey("createdAt"), "find() result must include createdAt");
        assertDoesNotThrow(() -> java.time.Instant.parse((String) found.get("createdAt")),
                "createdAt must be a valid ISO-8601 instant string");

        List<Stash> all = searcher.findAll(
                templates.findAllByType,
                stash("id", "A"),
                session,
                System.currentTimeMillis()
        );
        assertFalse(all.isEmpty());
        for (Stash row : all) {
            assertTrue(row.containsKey("createdAt"), "findAll() result must include createdAt");
            assertDoesNotThrow(() -> java.time.Instant.parse((String) row.get("createdAt")),
                    "createdAt must be a valid ISO-8601 instant string");
        }
    }
}
