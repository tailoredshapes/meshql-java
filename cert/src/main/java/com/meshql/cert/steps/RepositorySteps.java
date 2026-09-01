package com.meshql.cert.steps;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.cert.IntegrationWorld;
import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class RepositorySteps {
    private final IntegrationWorld world;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RepositorySteps(IntegrationWorld world) {
        this.world = world;
    }

    @Given("a fresh repository instance")
    public void createFreshRepository() {
        if (world.plugin == null) {
            throw new IllegalStateException("Plugin not provided to test");
        }
        world.repository = world.plugin.createRepository(world.storageConfig, world.auth);
    }

    @Given("I have created envelopes:")
    public void createEnvelopes(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps();
        for (Map<String, String> row : rows) {
            String name = row.get("name");
            Stash payload = convertToStash(row);

            Envelope envelope = world.repository.create(
                new Envelope(null, payload, null, false, null),
                world.tokens
            );
            world.envelopes.put(name, envelope);
        }
    }

    @Given("I create envelopes:")
    public void iCreateEnvelopes(DataTable dataTable) {
        createEnvelopes(dataTable);
    }

    @When("I create many envelopes:")
    public void createManyEnvelopes(DataTable dataTable) {
        List<Map<String, String>> rows = dataTable.asMaps();
        List<Envelope> newEnvelopes = rows.stream()
            .map(row -> new Envelope(null, convertToStash(row), null, false, null))
            .collect(Collectors.toList());

        List<Envelope> created = world.repository.createMany(newEnvelopes, world.tokens);

        // Key on the stored payload name so the step holds whatever order the backend returns.
        for (Envelope envelope : created) {
            world.envelopes.put(String.valueOf(envelope.payload().get("name")), envelope);
        }
    }

    @When("I create a new version of envelope {string}:")
    public void createNewVersion(String name, DataTable dataTable) {
        Envelope existingEnvelope = world.envelopes.get(name);
        if (existingEnvelope == null) {
            throw new IllegalStateException("Envelope \"" + name + "\" not found");
        }

        Map<String, String> row = dataTable.asMaps().get(0);
        Stash payload = convertToStash(row);

        Envelope newVersion = world.repository.create(
            new Envelope(
                existingEnvelope.id(),
                payload,
                null,
                false,
                existingEnvelope.authorizedTokens()
            ),
            world.tokens
        );

        world.envelopes.put(name, newVersion);
    }

    @When("^I read envelopes (\\[.*\\]) by their IDs$")
    public void readEnvelopesByIds(String namesJson) {
        List<String> names = parseJsonArray(namesJson);
        List<String> ids = names.stream()
            .map(name -> {
                Envelope envelope = world.envelopes.get(name);
                if (envelope == null || envelope.id() == null) {
                    throw new IllegalStateException("Envelope \"" + name + "\" not found or has no ID");
                }
                return envelope.id();
            })
            .collect(Collectors.toList());

        if (ids.size() == 1) {
            Optional<Envelope> result = world.repository.read(ids.get(0), world.tokens, Instant.now());
            world.searchResult = result.map(Envelope::payload).orElse(null);
            world.searchResults = result.map(e -> Collections.singletonList(e.payload()))
                .orElse(Collections.emptyList());
        } else {
            List<Envelope> results = world.repository.readMany(ids, world.tokens);
            world.searchResults = results.stream()
                .map(Envelope::payload)
                .collect(Collectors.toList());
        }
    }

    @When("I remove envelope {string}")
    public void removeEnvelope(String name) {
        Envelope envelope = world.envelopes.get(name);
        if (envelope == null || envelope.id() == null) {
            throw new IllegalStateException("Envelope \"" + name + "\" not found or has no ID");
        }
        world.removeResult = world.repository.remove(envelope.id(), world.tokens);
    }

    @When("^I remove envelopes (\\[.*\\]) by their IDs$")
    public void removeEnvelopesByIds(String namesJson) {
        List<String> names = parseJsonArray(namesJson);
        List<String> ids = names.stream()
            .map(name -> {
                Envelope envelope = world.envelopes.get(name);
                if (envelope == null || envelope.id() == null) {
                    throw new IllegalStateException("Envelope \"" + name + "\" not found or has no ID");
                }
                return envelope.id();
            })
            .collect(Collectors.toList());

        world.removeResults = world.repository.removeMany(ids, world.tokens);
    }

    @When("I list all envelopes")
    public void listAllEnvelopes() {
        List<Envelope> results = world.repository.list(world.tokens);
        world.searchResults = results.stream()
            .map(Envelope::payload)
            .collect(Collectors.toList());
    }

    @Then("reading envelope {string} at timestamp {string} should return version {string}")
    public void readEnvelopeAtTimestamp(String name, String timestampLabel, String expectedVersion) {
        Envelope envelope = world.envelopes.get(name);
        if (envelope == null || envelope.id() == null) {
            throw new IllegalStateException("Envelope \"" + name + "\" not found");
        }

        Long timestamp = world.timestamps.get(timestampLabel);
        if (timestamp == null) {
            throw new IllegalStateException("Timestamp \"" + timestampLabel + "\" not found");
        }

        Optional<Envelope> result = world.repository.read(
            envelope.id(),
            world.tokens,
            Instant.ofEpochMilli(timestamp)
        );

        assertTrue(result.isPresent());
        assertEquals(expectedVersion, result.get().payload().get("version"));
    }

    @Then("^reading envelopes (\\[.*\\]) by their IDs should return nothing$")
    public void readingEnvelopesShouldReturnNothing(String namesJson) {
        List<String> names = parseJsonArray(namesJson);
        List<String> ids = names.stream()
            .map(name -> {
                Envelope envelope = world.envelopes.get(name);
                if (envelope == null || envelope.id() == null) {
                    throw new IllegalStateException("Envelope \"" + name + "\" not found or has no ID");
                }
                return envelope.id();
            })
            .collect(Collectors.toList());

        if (ids.size() == 1) {
            Optional<Envelope> result = world.repository.read(ids.get(0), world.tokens, Instant.now());
            assertTrue(result.isEmpty());
        } else {
            List<Envelope> results = world.repository.readMany(ids, world.tokens);
            assertEquals(0, results.size());
        }
    }

    @Then("the envelopes should have generated IDs")
    public void envelopesShouldHaveGeneratedIds() {
        for (Envelope envelope : world.envelopes.values()) {
            assertNotNull(envelope.id());
            assertTrue(envelope.id() instanceof String);
        }
    }

    @Then("the envelopes created_at should be greater than or equal to the test start time")
    public void envelopesCreatedAtShouldBeValid() {
        for (Envelope envelope : world.envelopes.values()) {
            assertNotNull(envelope.createdAt());
            assertTrue(envelope.createdAt().toEpochMilli() >= world.testStartTime);
        }
    }

    @Then("the envelopes deleted flag should be disabled")
    public void envelopesDeletedFlagShouldBeDisabled() {
        for (Envelope envelope : world.envelopes.values()) {
            assertFalse(envelope.deleted());
        }
    }

    @Then("I should receive {int} envelope(s)")
    public void shouldReceiveEnvelopes(int count) {
        assertNotNull(world.searchResults);
        assertEquals(count, world.searchResults.size());
    }

    @Then("I should receive exactly {int} envelope(s)")
    public void shouldReceiveExactlyEnvelopes(int count) {
        assertNotNull(world.searchResults);
        assertEquals(count, world.searchResults.size());
    }

    @Then("the payload {string} should be {string}")
    public void payloadShouldBe(String key, String value) {
        Stash result = world.searchResult != null ? world.searchResult :
            (world.searchResults != null && !world.searchResults.isEmpty() ? world.searchResults.get(0) : null);
        assertNotNull(result);
        assertEquals(value, result.get(key));
    }

    @Then("the remove operations should return true")
    public void removeOperationsShouldReturnTrue() {
        assertNotNull(world.removeResults);
        for (Boolean result : world.removeResults.values()) {
            assertTrue(result);
        }
    }

    @Then("the remove operation should return true")
    public void removeOperationShouldReturnTrue() {
        assertNotNull(world.removeResult);
        assertTrue(world.removeResult);
    }

    @Then("listing all envelopes should show exactly {int} envelope(s)")
    public void listingAllEnvelopesShouldShow(int count) {
        List<Envelope> results = world.repository.list(world.tokens);
        assertEquals(count, results.size());
    }

    // Helper methods
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

    private List<String> parseJsonArray(String json) {
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON array: " + json, e);
        }
    }
}
