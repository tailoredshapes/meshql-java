package com.meshql.cert.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.cert.AuthzCredentials;
import com.meshql.cert.HttpClient;
import com.meshql.auth.token.TokenMark;
import com.meshql.core.Envelope;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bindings for the shared contract's {@code authz.feature}.
 *
 * <p>Every authorization assertion goes over HTTP: writes and deletes through the
 * restlette, reads through the graphlette, credentials in the trusted identity
 * header the edge sets. Two steps reach past HTTP into storage, both on purpose —
 * resolving a widget name to its id, which must keep working even when the read
 * path is broken, and inspecting the stored envelope to prove the write path kept
 * the tokens the Auth resolved.
 *
 * <p>Callers arrive as names in the feature file. {@code alice} and {@code bob}
 * hold distinct, non-wildcard credentials, {@code root} holds every token, and
 * {@code anonymous} sends no credentials at all;
 * {@link AuthzCredentials#headerFor(String)} maps them.
 */
public class ContractAuthzSteps {
    private static final Logger logger = LoggerFactory.getLogger(ContractAuthzSteps.class);

    /**
     * Separation between a captured instant and the writes on either side of it.
     * Timestamps are millisecond-precision, so without a gap "as of before beta"
     * can land in the same millisecond as beta's creation.
     */
    private static final long CLOCK_GAP_MS = 50;

    private final ContractAuthzWorld world;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ContractAuthzSteps(ContractAuthzWorld world) {
        this.world = world;
        this.httpClient = new HttpClient();
        this.objectMapper = new ObjectMapper();
    }

    // ---------------------------------------------------------------- background

    @Given("an authorizing MeshQL server is running")
    public void serverIsRunning() throws Exception {
        assertNotNull(world.env, "The adapter's hooks should have built an environment");
        assertNotNull(world.repository,
                "A Repository handle is needed to resolve ids and inspect stored envelopes");

        // Authorization is configured the documented way, on the server's own
        // config, so the server is what has to resolve the plugin and push it into
        // both the restlette and the graphlette.
        world.startServer(null);
        assertNotNull(world.server, "The server should be running");
    }

    // -------------------------------------------------------- writes and deletes

    @When("{string} creates a widget {string} of kind {string}")
    public void createsWidget(String caller, String name, String kind) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", name, "kind", kind));

        JsonNode response = httpClient.post(
                world.env.restUrl(), body, AuthzCredentials.headerFor(caller));

        assertEquals(201, httpClient.getLastStatus(),
                "'" + caller + "' creating widget '" + name + "' should succeed, got: " + response);

        // The restlette answers with the payload, not the envelope: the URL is the
        // identifier. Resolve the id from storage, which is harness plumbing rather
        // than the thing under test.
        String id = resolveIdFromStorage(name);
        assertNotNull(id, "Widget '" + name + "' should be in storage after a successful create");
        world.ids.put(name, id);

        logger.debug("'{}' created widget '{}' -> {}", caller, name, id);
    }

    @When("{string} deletes widget {string}")
    public void deletesWidget(String caller, String name) throws Exception {
        String id = widgetId(name);
        httpClient.delete(world.env.restUrl() + "/" + id, AuthzCredentials.headerFor(caller));
        world.deleteStatus = httpClient.getLastStatus();
        logger.debug("'{}' deleting widget '{}' returned {}", caller, name, world.deleteStatus);
    }

    @When("the current instant is captured as {string}")
    public void captureInstant(String key) throws InterruptedException {
        Thread.sleep(CLOCK_GAP_MS);
        world.stamps.put(key, System.currentTimeMillis());
        Thread.sleep(CLOCK_GAP_MS);
    }

    // ---------------------------------------------------------------------- reads

    @When("{string} queries widgets of kind {string}")
    public void queriesByKind(String caller, String kind) throws IOException {
        world.names = namesOf(vectorQuery(caller, kind, null), "getByKind");
    }

    @When("{string} queries widgets of kind {string} as of {string}")
    public void queriesByKindAt(String caller, String kind, String stamp) throws IOException {
        world.names = namesOf(vectorQuery(caller, kind, requireStamp(stamp)), "getByKind");
    }

    @When("{string} lists all widgets")
    public void listsAllWidgets(String caller) throws IOException {
        JsonNode body = httpClient.get(world.env.restUrl(), AuthzCredentials.headerFor(caller));
        assertEquals(200, httpClient.getLastStatus(),
                "'" + caller + "' listing widgets should succeed, got: " + body);

        List<String> names = new ArrayList<>();
        for (JsonNode item : body) {
            JsonNode name = item.get("name");
            if (name != null && !name.isNull()) {
                names.add(name.asText());
            }
        }
        world.names = names;
    }

    @When("{string} reads widget {string} by id as of {string}")
    public void readsByIdAt(String caller, String name, String stamp) throws IOException {
        world.temporalResult = readById(caller, name, requireStamp(stamp));
    }

    @Then("{string} can read widget {string} by id")
    public void canReadById(String caller, String name) throws IOException {
        JsonNode found = readById(caller, name, null);
        assertTrue(isVisible(found),
                "'" + caller + "' should see widget '" + name + "' through the graphlette, got: " + found);
        assertEquals(name, found.get("name").asText(),
                "The graphlette returned the wrong widget");

        // And through the restlette, which reads by a different code path.
        JsonNode rest = httpClient.get(
                world.env.restUrl() + "/" + widgetId(name), AuthzCredentials.headerFor(caller));
        assertEquals(200, httpClient.getLastStatus(),
                "'" + caller + "' should see widget '" + name + "' through the restlette, got: " + rest);
    }

    @Then("{string} cannot read widget {string} by id")
    public void cannotReadById(String caller, String name) throws IOException {
        JsonNode found = readById(caller, name, null);
        assertFalse(isVisible(found),
                "'" + caller + "' must not see widget '" + name + "' through the graphlette, got: " + found);

        JsonNode rest = httpClient.get(
                world.env.restUrl() + "/" + widgetId(name), AuthzCredentials.headerFor(caller));
        assertEquals(404, httpClient.getLastStatus(),
                "'" + caller + "' must not see widget '" + name + "' through the restlette, got: " + rest);
    }

    @Then("the temporal read should find it")
    public void temporalReadFindsIt() {
        assertNotNull(world.temporalResult, "No temporal read was made");
        assertTrue(isVisible(world.temporalResult),
                "The record should exist at that instant, got: " + world.temporalResult);
    }

    @Then("the temporal read should find nothing")
    public void temporalReadFindsNothing() {
        assertFalse(isVisible(world.temporalResult),
                "`at:` rewinds data, not authorization. This caller reached a record it holds no "
                        + "token for by asking for an earlier instant: " + world.temporalResult);
    }

    @Then("the result should be exactly {string}")
    public void resultShouldBeExactly(String expectedCsv) {
        List<String> expected = new ArrayList<>();
        for (String part : expectedCsv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                expected.add(trimmed);
            }
        }
        Collections.sort(expected);

        List<String> actual = new ArrayList<>(world.names);
        Collections.sort(actual);

        assertEquals(expected, actual,
                "A query spanning both callers' records is the only shape that tells a working "
                        + "authorization filter from an inert one");
    }

    // ------------------------------------------------------------------- deletes

    @Then("the delete should be refused")
    public void deleteRefused() {
        assertNotNull(world.deleteStatus, "No delete was attempted");
        assertTrue(world.deleteStatus == 404 || world.deleteStatus == 403,
                "A caller deleting a record it holds no token for must be refused, got HTTP "
                        + world.deleteStatus);
    }

    @Then("the delete should succeed")
    public void deleteSucceeded() {
        assertNotNull(world.deleteStatus, "No delete was attempted");
        assertEquals(200, world.deleteStatus, "The owner's delete should succeed");
    }

    // ------------------------------------------------------------ storage checks

    @Then("the stored envelope for widget {string} should carry the tokens {string} resolves to")
    public void storedEnvelopeCarriesTokens(String name, String caller) {
        List<String> expected = AuthzCredentials.expectedTokensFor(caller);
        assertFalse(expected.isEmpty(),
                "'" + caller + "' resolves to no tokens, so this step would assert nothing");

        List<String> actual = storedTokens(name);
        assertFalse(actual.isEmpty(),
                "The stored envelope for '" + name + "' carries no authorized tokens: the write path "
                        + "dropped what the Auth resolved, so the record is readable by everyone");
        assertEquals(expected, actual,
                "The stored envelope for '" + name + "' should carry the tokens '" + caller
                        + "' resolves to");
    }

    @Then("the stored envelope for widget {string} should carry no tokens")
    public void storedEnvelopeCarriesNoTokens(String name) {
        List<String> actual = storedTokens(name);
        assertTrue(actual.isEmpty(),
                "A record written without credentials must stay public, but it was stamped with "
                        + actual);
    }

    // ------------------------------------------------------------------- helpers

    private JsonNode readById(String caller, String name, Long at) throws IOException {
        String id = widgetId(name);
        String query = at == null
                ? "{ getById(id: \"%s\") { id name kind } }".formatted(id)
                : "{ getById(id: \"%s\", at: %d) { id name kind } }".formatted(id, at);
        return graphql(caller, query).get("getById");
    }

    private JsonNode vectorQuery(String caller, String kind, Long at) throws IOException {
        String query = at == null
                ? "{ getByKind(kind: \"%s\") { id name kind } }".formatted(kind)
                : "{ getByKind(kind: \"%s\", at: %d) { id name kind } }".formatted(kind, at);
        return graphql(caller, query);
    }

    /**
     * Run a GraphQL query as a caller and return its {@code data}, failing on any
     * GraphQL error. An error that reads as an empty result would otherwise look
     * exactly like a correctly filtered one.
     */
    private JsonNode graphql(String caller, String query) throws IOException {
        String body = objectMapper.writeValueAsString(Map.of("query", query));
        JsonNode response = httpClient.post(
                world.env.graphUrl(), body, AuthzCredentials.headerFor(caller));

        JsonNode errors = response.get("errors");
        if (errors != null && !errors.isNull() && !errors.isEmpty()) {
            throw new AssertionError("GraphQL errors for " + caller + ": " + errors);
        }

        JsonNode data = response.get("data");
        assertNotNull(data, "GraphQL returned no data for '" + caller + "': " + response);
        return data;
    }

    private List<String> namesOf(JsonNode data, String field) {
        JsonNode results = data.get(field);
        assertNotNull(results, field + " missing from the response: " + data);

        List<String> names = new ArrayList<>();
        for (JsonNode node : results) {
            JsonNode name = node.get("name");
            if (name != null && !name.isNull()) {
                names.add(name.asText());
            }
        }
        return names;
    }

    /**
     * A record counts as visible only when the query actually returned it. An
     * unauthorized or missing singleton comes back either as GraphQL null or as an
     * object whose fields are all null, because the Searcher returns an empty
     * payload that the DTO factory still wraps. Both shapes mean "not visible".
     */
    private boolean isVisible(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        JsonNode id = node.get("id");
        return id != null && !id.isNull();
    }

    private String widgetId(String name) {
        String id = world.ids.get(name);
        assertNotNull(id, "No widget named '" + name + "' was created");
        return id;
    }

    private long requireStamp(String key) {
        Long stamp = world.stamps.get(key);
        assertNotNull(stamp, "No instant was captured as '" + key + "'");
        return stamp;
    }

    /**
     * Resolve a widget name to its id by scanning storage as a caller entitled to
     * everything. Goes through the Repository rather than a read surface so it keeps
     * working when the read path is broken — otherwise a broken read path would hide
     * the very failures these scenarios assert.
     *
     * <p>The entitlement is an explicit system session, not the absence of
     * credentials. Presenting no tokens once bypassed filtering everywhere, which is
     * exactly the hole {@code An uncredentialed caller reaches public records and
     * nothing else} exists to catch: a harness that reads that way would keep working
     * while every anonymous caller read everything.
     */
    private String resolveIdFromStorage(String name) {
        for (Envelope envelope : world.repository.list(world.systemSession)) {
            if (envelope.payload() != null && name.equals(envelope.payload().get("name"))) {
                return envelope.id();
            }
        }
        return null;
    }

    private List<String> storedTokens(String name) {
        Optional<Envelope> envelope =
                world.repository.read(widgetId(name), world.systemSession, Instant.now());
        assertTrue(envelope.isPresent(), "Widget '" + name + "' is not in storage at all");
        // The token plugin's mark shape, which is what this suite certifies.
        return new ArrayList<>(TokenMark.tokensOf(envelope.get()));
    }
}
