package com.meshql.cert.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Template;
import com.meshql.cert.IntegrationWorld;
import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class SearcherSteps {
    private final IntegrationWorld world;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SearcherSteps(IntegrationWorld world) {
        this.world = world;
    }

    @Given("a fresh repository and searcher instance")
    public void createFreshRepositoryAndSearcher() {
        world.repository = world.plugin.createRepository(world.storageConfig);
        world.searcher = world.plugin.createSearcher(world.storageConfig);
    }

    @Given("I have created and saved the following test dataset:")
    public void createAndSaveTestDataset(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps();
        List<Envelope> envelopes = rows.stream()
            .map(row -> {
                Stash payload = convertToStash(row);
                return new Envelope(null, payload, null, false, null);
            })
            .collect(Collectors.toList());

        List<Envelope> saved = world.repository.createMany(envelopes, world.session);

        for (Envelope envelope : saved) {
            String name = (String) envelope.payload().get("name");
            world.envelopes.put(name, envelope);
        }
    }

    @Given("I have removed envelope {string}")
    public void removeEnvelope(String name) {
        Envelope envelope = world.envelopes.get(name);
        if (envelope == null || envelope.id() == null) {
            throw new IllegalStateException("Envelope \"" + name + "\" not found");
        }
        world.repository.remove(envelope.id(), world.session);
    }

    @Given("I have updated envelope {string} to {string} with count {int}")
    public void updateEnvelope(String oldName, String newName, int count) {
        Envelope envelope = world.envelopes.get(oldName);
        if (envelope == null) {
            throw new IllegalStateException("Envelope \"" + oldName + "\" not found");
        }

        Stash newPayload = new Stash(envelope.payload());
        newPayload.put("name", newName);
        newPayload.put("count", count);

        Envelope updated = world.repository.create(
            new Envelope(
                envelope.id(),
                newPayload,
                null,
                false,
                envelope.auth()
            ),
            world.session
        );

        world.envelopes.remove(oldName);
        world.envelopes.put(newName, updated);
    }

    @When("I search using template {string} with parameters:")
    public void searchUsingTemplate(String templateName, DataTable dataTable) {
        Map<String, String> params = dataTable.asMaps().get(0);
        Stash args = prepareSearchParams(templateName, params);

        Template template = getTemplate(templateName);
        world.searchResult = world.searcher.find(template, args, world.session, System.currentTimeMillis());
    }

    @When("I search all using template {string} with parameters:")
    public void searchAllUsingTemplate(String templateName, DataTable dataTable) {
        Map<String, String> params = dataTable.asMaps().get(0);
        Stash args = prepareSearchParams(templateName, params);

        Template template = getTemplate(templateName);
        world.searchResults = world.searcher.findAll(template, args, world.session, System.currentTimeMillis());
    }

    @When("I search all using template {string} with a limit of {int} and parameters:")
    public void searchAllUsingTemplateWithLimit(String templateName, int limit, DataTable dataTable) {
        Map<String, String> params = dataTable.asMaps().get(0);
        Stash args = prepareSearchParams(templateName, params);
        args.put("limit", limit);

        Template template = getTemplate(templateName);
        world.searchResults = world.searcher.findAll(template, args, world.session, System.currentTimeMillis());
    }

    @Then("the search result should be empty")
    public void searchResultShouldBeEmpty() {
        assertNotNull(world.searchResult);
        assertTrue(world.searchResult.isEmpty());
    }

    @Then("the search result should have name {string}")
    public void searchResultShouldHaveName(String expectedName) {
        assertNotNull(world.searchResult);
        assertEquals(expectedName, world.searchResult.get("name"));
    }

    @Then("the search result should have count {int}")
    public void searchResultShouldHaveCount(int expectedCount) {
        assertNotNull(world.searchResult);
        Object count = world.searchResult.get("count");
        assertEquals(expectedCount, ((Number) count).intValue());
    }

    @Then("I should receive exactly {int} result(s)")
    public void shouldReceiveExactlyResults(int count) {
        assertNotNull(world.searchResults);
        assertEquals(count, world.searchResults.size());
    }

    @Then("the results should include an envelope with name {string} and count {int}")
    public void resultsShouldIncludeEnvelopeWithNameAndCount(String name, int count) {
        assertNotNull(world.searchResults);
        boolean found = world.searchResults.stream()
            .anyMatch(r -> name.equals(r.get("name")) &&
                count == ((Number) r.get("count")).intValue());
        assertTrue(found, "Expected to find envelope with name=" + name + " and count=" + count);
    }

    @Then("the results should include an envelope with name {string}")
    public void resultsShouldIncludeEnvelopeWithName(String name) {
        assertNotNull(world.searchResults);
        boolean found = world.searchResults.stream()
            .anyMatch(r -> name.equals(r.get("name")));
        assertTrue(found, "Expected to find envelope with name=" + name);
    }

    @Then("the search result should have a valid createdAt")
    public void searchResultShouldHaveValidCreatedAt() {
        assertNotNull(world.searchResult);
        assertValidCreatedAt(world.searchResult);
    }

    @Then("every result should have a valid createdAt")
    public void everyResultShouldHaveValidCreatedAt() {
        assertNotNull(world.searchResults);
        assertFalse(world.searchResults.isEmpty(), "Expected at least one result to carry a createdAt");
        world.searchResults.forEach(this::assertValidCreatedAt);
    }

    // Helper methods
    /**
     * Assert the "honesty" as-of field: every searcher merges the envelope's
     * createdAt into the result alongside its id, so a caller can tell how
     * fresh a read is instead of rendering stale data silently.
     */
    private void assertValidCreatedAt(Stash result) {
        Object createdAt = result.get("createdAt");
        assertNotNull(createdAt, "Expected the search result to carry a createdAt field");

        try {
            assertNotNull(Instant.parse(createdAt.toString()));
        } catch (DateTimeParseException e) {
            fail("Expected createdAt \"" + createdAt + "\" to be an ISO-8601 instant");
        }
    }

    private Stash convertToStash(Map<String, String> row) {
        Stash stash = new Stash();
        for (Map.Entry<String, String> entry : row.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Try to parse as number
            try {
                if (value.contains(".")) {
                    stash.put(key, Double.parseDouble(value));
                } else {
                    stash.put(key, Integer.parseInt(value));
                }
            } catch (NumberFormatException e) {
                stash.put(key, value);
            }
        }
        return stash;
    }

    private Stash prepareSearchParams(String templateName, Map<String, String> params) {
        Stash args = new Stash();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            // Replace envelope names with actual IDs for findById templates
            if ("findById".equals(templateName) && "id".equals(key) && world.envelopes.containsKey(value)) {
                Envelope envelope = world.envelopes.get(value);
                if (envelope != null && envelope.id() != null) {
                    args.put(key, envelope.id());
                } else {
                    args.put(key, value);
                }
            } else {
                args.put(key, value);
            }
        }
        return args;
    }

    private Template getTemplate(String templateName) {
        try {
            Field field = world.templates.getClass().getField(templateName);
            return (Template) field.get(world.templates);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalArgumentException("Template \"" + templateName + "\" not found", e);
        }
    }
}
