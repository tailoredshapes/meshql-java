package com.meshql.cert.steps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.cert.AuthzCredentials;
import com.meshql.cert.AuthzWorld;
import com.meshql.cert.HttpClient;
import com.meshql.core.Envelope;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step definitions for the authorization certification.
 *
 * <p>Every authorization assertion goes over HTTP. The only direct storage access is
 * (a) resolving a document label to its MeshQL id, which is harness plumbing and must
 * keep working even when the read path is broken, and (b) the one scenario that
 * deliberately inspects the stored envelope to prove the write path kept the tokens.
 */
public class AuthzSteps {
    private static final Logger logger = LoggerFactory.getLogger(AuthzSteps.class);

    private final AuthzWorld world;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AuthzSteps(AuthzWorld world) {
        this.world = world;
        this.httpClient = new HttpClient();
        this.objectMapper = new ObjectMapper();
    }

    // ---------------------------------------------------------------- background

    @Given("an authorizing MeshQL server is running with the plugin")
    public void serverIsRunning() {
        assertNotNull(world.server, "Server should be initialized by the adapter's hooks");
        assertNotNull(world.env, "AuthzEnv should be initialized by the adapter's hooks");
        assertNotNull(world.repository,
                "A Repository handle is needed to inspect stored envelopes and resolve ids");
    }

    @Given("a fresh workspace")
    public void freshWorkspace() {
        // Scenarios share one datastore; a per-scenario workspace keeps the vector
        // query from seeing documents another scenario created.
        world.workspace = "ws-" + UUID.randomUUID();
        world.ids.clear();
        logger.debug("Fresh workspace: {}", world.workspace);
    }

    // -------------------------------------------------------------------- writes

    @When("caller {string} creates document {string}")
    public void callerCreatesDocument(String caller, String label) throws Exception {
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "name", label,
                "workspace", world.workspace));

        httpClient.post(world.env.restUrl(), body, AuthzCredentials.headerFor(caller));
        world.lastStatus = httpClient.getLastStatus();

        assertEquals(201, world.lastStatus,
                "Creating '" + label + "' as " + caller + " should succeed");

        // REST deliberately does not return the envelope id (the URL is the identifier),
        // so resolve it from storage. This is harness plumbing, not the thing under test.
        String id = resolveIdFromStorage(label);
        assertNotNull(id, "Document '" + label + "' should exist in storage after create");
        world.ids.put(label, id);

        logger.debug("Caller {} created '{}' -> {}", caller, label, id);
    }

    @When("caller {string} renames document {string} to {string}")
    public void callerRenamesDocument(String caller, String label, String newName) throws Exception {
        String id = requireId(label);
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "name", newName,
                "workspace", world.workspace));

        httpClient.put(world.env.restUrl() + "/" + id, body, AuthzCredentials.headerFor(caller));
        world.lastStatus = httpClient.getLastStatus();

        assertEquals(200, world.lastStatus,
                "Renaming '" + label + "' as " + caller + " should succeed");
    }

    @When("caller {string} deletes document {string}")
    public void callerDeletesDocument(String caller, String label) throws Exception {
        String id = requireId(label);
        httpClient.delete(world.env.restUrl() + "/" + id, AuthzCredentials.headerFor(caller));
        world.lastStatus = httpClient.getLastStatus();
        logger.debug("Caller {} delete of '{}' returned {}", caller, label, world.lastStatus);
    }

    @When("I capture the current timestamp")
    public void captureTimestamp() throws InterruptedException {
        world.firstStamp = System.currentTimeMillis();
        // Ensure any subsequent write lands strictly after the captured instant.
        Thread.sleep(10);
    }

    // --------------------------------------------------------------------- reads

    @Then("caller {string} can read document {string} by id")
    public void callerCanReadById(String caller, String label) throws Exception {
        JsonNode node = readById(caller, label, null);
        assertTrue(isVisible(node),
                "Caller " + caller + " should be able to read '" + label + "', got: " + node);
        assertEquals(requireId(label), node.get("id").asText());
    }

    @Then("caller {string} cannot read document {string} by id")
    public void callerCannotReadById(String caller, String label) throws Exception {
        JsonNode node = readById(caller, label, null);
        assertFalse(isVisible(node),
                "Caller " + caller + " must NOT be able to read '" + label + "', but got: " + node);
    }

    @Then("caller {string} can read document {string} by id at the captured timestamp")
    public void callerCanReadByIdAt(String caller, String label) throws Exception {
        assertNotNull(world.firstStamp, "No timestamp captured");
        JsonNode node = readById(caller, label, world.firstStamp);
        assertTrue(isVisible(node),
                "Caller " + caller + " should be able to read '" + label + "' as of "
                        + world.firstStamp + ", got: " + node);
    }

    @Then("caller {string} cannot read document {string} by id at the captured timestamp")
    public void callerCannotReadByIdAt(String caller, String label) throws Exception {
        assertNotNull(world.firstStamp, "No timestamp captured");
        JsonNode node = readById(caller, label, world.firstStamp);
        assertFalse(isVisible(node),
                "Rewinding to " + world.firstStamp + " must rewind data, not authorization: "
                        + "caller " + caller + " must NOT reach '" + label + "', but got: " + node);
    }

    @Then("caller {string} listing the workspace sees exactly:")
    public void callerListsWorkspace(String caller, DataTable table) throws Exception {
        List<String> expected = new ArrayList<>(table.asList());
        Collections.sort(expected);

        String query = """
                {
                  getByWorkspace(workspace: "%s") {
                    id
                    name
                  }
                }
                """.formatted(world.workspace);

        JsonNode data = httpClient.graphql(world.env.graphUrl(), query, AuthzCredentials.headerFor(caller));
        assertNotNull(data, "GraphQL returned no data for caller " + caller);

        JsonNode results = data.get("getByWorkspace");
        assertNotNull(results, "getByWorkspace missing from response: " + data);

        List<String> actual = new ArrayList<>();
        for (JsonNode n : results) {
            JsonNode name = n.get("name");
            if (name != null && !name.isNull()) {
                actual.add(name.asText());
            }
        }
        Collections.sort(actual);

        assertEquals(expected, actual,
                "Caller " + caller + " should see exactly their own records in a list query. "
                        + "A list query is the only shape that distinguishes a working "
                        + "authorization filter from an inert one.");
    }

    // ------------------------------------------------------------ storage checks

    @Then("the stored envelope for {string} has authorized tokens {string}")
    public void storedEnvelopeHasTokens(String label, String expectedCsv) {
        List<String> expected = List.of(expectedCsv.split("\\s*,\\s*"));
        List<String> actual = storedTokens(label);

        assertFalse(actual.isEmpty(),
                "The tokens Auth resolved for the writer were lost on the way to storage: "
                        + "envelope for '" + label + "' stored no authorized tokens. "
                        + "Everything written through the restlette is therefore world-readable.");
        assertEquals(expected, actual,
                "Stored authorized tokens should be exactly what Auth resolved for the writer");
    }

    @Then("the stored envelope for {string} has no authorized tokens")
    public void storedEnvelopeHasNoTokens(String label) {
        List<String> actual = storedTokens(label);
        assertTrue(actual.isEmpty(),
                "A caller presenting no credentials must produce a public record "
                        + "(empty authorized tokens), but got: " + actual);
    }

    // ------------------------------------------------------------------- helpers

    private JsonNode readById(String caller, String label, Long at) throws IOException {
        String id = requireId(label);
        String query = at == null
                ? "{ getById(id: \"%s\") { id name } }".formatted(id)
                : "{ getById(id: \"%s\", at: %d) { id name } }".formatted(id, at);

        JsonNode data = httpClient.graphql(world.env.graphUrl(), query, AuthzCredentials.headerFor(caller));
        world.lastData = data;
        if (data == null) {
            return null;
        }
        return data.get("getById");
    }

    /**
     * A record is "visible" only if the query actually returned it. An unauthorized
     * or missing singleton comes back either as GraphQL null or as an object whose
     * fields are all null (the Searcher returns an empty payload, which the DTO
     * factory still wraps), so both shapes must count as not visible.
     */
    private boolean isVisible(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        JsonNode id = node.get("id");
        return id != null && !id.isNull();
    }

    private String requireId(String label) {
        String id = world.ids.get(label);
        assertNotNull(id, "No id recorded for document '" + label + "'");
        return id;
    }

    /**
     * Resolve a document label to its id by scanning storage with no credentials.
     * Uses the Repository directly so it keeps working even when the read path is
     * broken - otherwise a broken read path would hide the very failures we assert.
     */
    private String resolveIdFromStorage(String label) {
        List<Envelope> all = world.repository.list(Collections.emptyList());
        for (Envelope e : all) {
            if (e.payload() == null) {
                continue;
            }
            Object name = e.payload().get("name");
            Object ws = e.payload().get("workspace");
            if (label.equals(name) && world.workspace.equals(ws)) {
                return e.id();
            }
        }
        return null;
    }

    private List<String> storedTokens(String label) {
        String id = requireId(label);
        Optional<Envelope> envelope = world.repository.read(id, Collections.emptyList(), java.time.Instant.now());
        assertTrue(envelope.isPresent(), "Envelope for '" + label + "' not found in storage");
        List<String> tokens = envelope.get().authorizedTokens();
        return tokens == null ? List.of() : new ArrayList<>(tokens);
    }
}
