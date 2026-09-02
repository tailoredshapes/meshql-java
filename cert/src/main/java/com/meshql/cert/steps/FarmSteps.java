package com.meshql.cert.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.cert.FarmWorld;
import com.meshql.cert.HttpClient;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import com.tailoredshapes.stash.Stash;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step definitions for farm certification tests.
 */
public class FarmSteps {
    private static final Logger logger = LoggerFactory.getLogger(FarmSteps.class);
    private final FarmWorld world;
    private final HttpClient httpClient;
    private final Handlebars handlebars;
    private final ObjectMapper objectMapper;

    public FarmSteps(FarmWorld world) {
        this.world = world;
        this.httpClient = new HttpClient();
        this.handlebars = new Handlebars();
        this.objectMapper = new ObjectMapper();
    }

    @Given("a MeshQL server is running with the plugin")
    public void serverIsRunning() {
        assertNotNull(world.server, "Server should be initialized by hooks");
    }

    @Given("I have created {string}:")
    public void createEntities(String entityType, DataTable dataTable) throws Exception {
        Repository repository = getRepositoryForEntity(entityType);

        List<Map<String, String>> rows = dataTable.asMaps();
        for (Map<String, String> row : rows) {
            String name = row.get("name");
            String dataTemplate = row.get("data");

            // Process template to replace {{ids.type.name}} placeholders
            String processedData = processTemplate(dataTemplate);

            // Convert JavaScript object notation to JSON and parse to Stash
            String jsonData = convertJsToJson(processedData);
            Stash payload = jsonToStash(jsonData);

            // Create entity via Repository
            Envelope envelope = new Envelope(null, payload, null, false, null);
            Envelope created = repository.create(envelope, FarmWorld.SESSION);

            // Store ID in env (persistent across scenarios)
            world.env.ids.get(entityType).put(name, created.id());

            logger.info("Created {} '{}' with ID: {} - All IDs: {}", entityType, name, created.id(), world.env.ids);
        }
        logger.info("Finished creating {}, IDs map: {}", entityType, world.env.ids);
    }

    @Given("I have updated {string}:")
    public void updateEntities(String entityType, DataTable dataTable) throws Exception {
        Repository repository = getRepositoryForEntity(entityType);

        List<Map<String, String>> rows = dataTable.asMaps();
        for (Map<String, String> row : rows) {
            String name = row.get("name");
            String dataTemplate = row.get("data");

            // Get existing ID from env
            String id = world.env.ids.get(entityType).get(name);
            if (id == null) {
                throw new IllegalStateException("Entity " + entityType + "." + name + " not found");
            }

            // Process template and convert to Stash
            String processedData = processTemplate(dataTemplate);
            String jsonData = convertJsToJson(processedData);
            Stash payload = jsonToStash(jsonData);

            // Create new version via Repository
            Envelope envelope = new Envelope(id, payload, null, false, null);
            repository.create(envelope, FarmWorld.SESSION);

            logger.debug("Updated {} '{}' (ID: {})", entityType, name, id);
        }
    }

    @Given("I have captured the first timestamp")
    public void captureFirstTimestamp() throws InterruptedException {
        world.env.firstStamp = System.currentTimeMillis();
        logger.debug("Captured first timestamp: {}", world.env.firstStamp);
        // Sleep to ensure subsequent database operations get strictly later timestamps
        // This prevents race conditions between System.currentTimeMillis() and database clock
        Thread.sleep(5);
    }

    @When("I query the {string} graph:")
    public void queryGraph(String entityType, String queryTemplate) throws Exception {
        // Process query template to replace placeholders
        String processedQuery = processQueryTemplate(queryTemplate);

        // Execute GraphQL query
        String url = world.env.getPlatformUrl() + "/" + entityType + "/graph";
        JsonNode data = httpClient.graphql(url, processedQuery);

        // Store result
        world.queryResult = data;

        // Also extract array results if present
        if (data != null) {
            JsonNode firstField = data.fields().hasNext() ? data.fields().next().getValue() : null;
            if (firstField != null && firstField.isArray()) {
                world.queryResults = new JsonNode[firstField.size()];
                for (int i = 0; i < firstField.size(); i++) {
                    world.queryResults[i] = firstField.get(i);
                }
            } else {
                world.queryResults = firstField != null ? new JsonNode[]{firstField} : new JsonNode[0];
            }
        }

        logger.debug("Query result: {}", data);
    }

    @Then("the farm name should be {string}")
    public void farmNameShouldBe(String expectedName) {
        JsonNode result = getQueryRootResult();
        String actualName = result.get("name").asText();
        assertEquals(expectedName, actualName);
    }

    @Then("the coop name should be {string}")
    public void coopNameShouldBe(String expectedName) {
        JsonNode result = getFirstCoopFromResult();
        // If result is null, we're querying coops directly (not from hens)
        if (result == null) {
            result = getQueryRootResult();
        }
        assertNotNull(result, "Coop result should not be null");
        String actualName = result.get("name").asText();
        assertEquals(expectedName, actualName);
    }

    @Then("there should be {int} {string}")
    public void thereShouldBeCount(int expectedCount, String fieldName) {
        JsonNode result = getQueryRootResult();
        JsonNode field = result.get(fieldName);
        assertNotNull(field, fieldName + " field should exist");
        assertTrue(field.isArray(), fieldName + " should be an array");
        assertEquals(expectedCount, field.size());
    }

    @Then("there should be {int} results")
    public void thereShouldBeResults(int expectedCount) {
        assertNotNull(world.queryResults);
        assertEquals(expectedCount, world.queryResults.length);
    }

    @Then("the result should contain {string} {string}")
    public void resultShouldContain(String fieldName, String expectedValue) {
        // For vector queries (arrays), get the first element
        JsonNode result;
        if (world.queryResults != null && world.queryResults.length > 0) {
            result = world.queryResults[0];
        } else {
            result = getQueryRootResult();
        }
        String actualValue = result.get(fieldName).asText();
        assertEquals(expectedValue, actualValue);
    }

    @Then("the {int} {string} ID should match the saved {string} ID")
    public void idShouldMatchSaved(int index, String entityType, String savedName) {
        String savedId = world.env.ids.get(entityType).get(savedName);
        assertNotNull(savedId, "Saved ID for " + entityType + "." + savedName + " should exist");

        String actualId = world.queryResults[index].get("id").asText();
        assertEquals(savedId, actualId);
    }

    @Then("the {string} ID should match the saved {string} ID")
    public void entityIdShouldMatchSaved(String entityType, String savedName) {
        String savedId = world.env.ids.get(entityType).get(savedName);
        assertNotNull(savedId, "Saved ID for " + entityType + "." + savedName + " should exist");

        JsonNode result = getQueryRootResult();
        String actualId = result.get("id").asText();
        assertEquals(savedId, actualId);
    }

    @Then("the hens should include {string} and {string}")
    public void hensShouldInclude(String name1, String name2) {
        Set<String> henNames = new HashSet<>();
        for (JsonNode hen : world.queryResults) {
            henNames.add(hen.get("name").asText());
        }
        assertTrue(henNames.contains(name1), "Should include hen " + name1);
        assertTrue(henNames.contains(name2), "Should include hen " + name2);
    }

    @Then("the results should include {string}")
    public void resultsShouldInclude(String expectedValue) {
        JsonNode result = getQueryRootResult();
        JsonNode coops = result.get("coops");
        assertNotNull(coops);

        boolean found = false;
        for (JsonNode coop : coops) {
            if (coop.get("name").asText().equals(expectedValue)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Results should include: " + expectedValue);
    }

    @Then("the results should not include {string}")
    public void resultsShouldNotInclude(String unexpectedValue) {
        JsonNode result = getQueryRootResult();
        JsonNode coops = result.get("coops");
        assertNotNull(coops);

        for (JsonNode coop : coops) {
            assertNotEquals(unexpectedValue, coop.get("name").asText(),
                "Results should not include: " + unexpectedValue);
        }
    }

    // Helper methods

    private JsonNode getQueryRootResult() {
        assertNotNull(world.queryResult, "Query result should not be null");
        // Get the first field in the data object (e.g., getById, getByName)
        Iterator<JsonNode> values = world.queryResult.elements();
        assertTrue(values.hasNext(), "Query result should have at least one field");
        return values.next();
    }

    private JsonNode getFirstCoopFromResult() {
        // For queries that return hens, the coop is nested
        if (world.queryResults != null && world.queryResults.length > 0) {
            JsonNode hen = world.queryResults[0];
            return hen.get("coop");
        }
        return null;
    }

    private String processTemplate(String template) throws IOException {
        Template handlebarsTemplate = handlebars.compileInline(template);
        Map<String, Object> context = new HashMap<>();
        context.put("ids", world.env.ids);
        return handlebarsTemplate.apply(context);
    }

    private String processQueryTemplate(String queryTemplate) throws IOException {
        Template template = handlebars.compileInline(queryTemplate);
        Map<String, Object> context = new HashMap<>();
        context.put("ids", world.env.ids);
        context.put("first_stamp", world.env.firstStamp);
        context.put("now", System.currentTimeMillis());
        return template.apply(context);
    }

    /**
     * Convert JavaScript object notation to JSON.
     * e.g., { name: 'value' } -> {"name":"value"}
     */
    private String convertJsToJson(String jsObject) {
        // Simple conversion: wrap keys in quotes and convert single quotes to double quotes
        String result = jsObject.trim();

        // Replace single quotes with double quotes
        result = result.replaceAll("'", "\"");

        // Add quotes around unquoted keys (word: -> "word":)
        result = result.replaceAll("(\\{|,)\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*:", "$1\"$2\":");

        return result;
    }

    private Repository getRepositoryForEntity(String entityType) {
        Repository repository = world.repositories.get(entityType);
        if (repository == null) {
            throw new IllegalStateException("Repository for " + entityType + " not initialized");
        }
        return repository;
    }

    @SuppressWarnings("unchecked")
    private Stash jsonToStash(String json) throws IOException {
        Map<String, Object> map = objectMapper.readValue(json, Map.class);
        Stash stash = new Stash();
        stash.putAll(map);
        return stash;
    }
}
