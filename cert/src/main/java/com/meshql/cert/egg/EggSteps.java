package com.meshql.cert.egg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Envelope;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Steps of {@code features/proposed/egg-economy/egg_economy.feature}.
 *
 * <p>The scenarios talk to the deployment over HTTP as a front end would: writes through
 * the restlettes, reads through restlettes and graphlettes. A restlette answers a create
 * with the payload, not the id, so the harness resolves ids from storage; that is
 * plumbing, not the thing under test. "Eventually" polls until true or the timeout,
 * because the worker runs behind the harness's bridge.
 */
public class EggSteps {
    /** How long an "eventually" step waits for the worker. */
    private static final Duration EVENTUALLY = Duration.ofSeconds(10);
    /** Storage clocks and the JVM's are not the same clock; keep an instant clear of its neighbours. */
    private static final long CLOCK_GAP_MS = 15;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private final EggWorld world;

    public EggSteps(EggWorld world) {
        this.world = world;
    }

    // ------------------------------------------------------------- deployment

    @Given("an egg economy deployment is running")
    public void deploymentIsRunning() throws Exception {
        assertNotNull(world.env, "The adapter's hooks should have prepared an environment");
        EggHooks.deploy(world);
    }

    @Given("a farm {string} with a coop {string} and a hen {string}")
    public void farmCoopHen(String farm, String coop, String hen) throws Exception {
        String farmId = make("farm", farm, obj("name", farm, "farm_type", "family", "zone", "north"));
        String coopId = make("coop", coop, obj("name", coop, "farm_id", farmId, "capacity", 10, "coop_type", "barn"));
        make("hen", hen, obj("name", hen, "coop_id", coopId, "breed", "leghorn"));
        world.lastFarm = farm;
        world.lastHen = hen;
    }

    // ----------------------------------------------------------------- events

    @When("the hen {string} lays {int} eggs of quality {string} at {string}")
    public void henLays(String hen, int eggs, String quality, String timestamp) throws Exception {
        String henId = id("hen", hen);
        String coopId = payloadOf("hen", henId).get("coop_id").asText();
        String farmId = payloadOf("coop", coopId).get("farm_id").asText();
        ObjectNode report = obj("hen_id", henId, "coop_id", coopId, "farm_id", farmId,
                "eggs", eggs, "quality", quality, "timestamp", timestamp);
        post("lay_report", report, EggEconomyEnv.bearer(EggEconomyEnv.FRONT_END));
        world.lastEventEntity = "lay_report";
        world.lastEvent = report;
    }

    @When("the front end writes a hen_productivity of {int} total eggs")
    public void frontEndWritesProjection(int total) throws Exception {
        post("hen_productivity", obj("hen_id", id("hen", world.lastHen), "farm_id", id("farm", world.lastFarm),
                "total_eggs", total, "quality_rate", 1.0), EggEconomyEnv.bearer(EggEconomyEnv.FRONT_END));
    }

    @Then("the posting was accepted")
    public void postingAccepted() {
        assertEquals(201, world.lastStatus, "the last POST should have answered 201, body: " + world.lastBody);
    }

    @Then("a lay report of the hen {string} can be read by its id")
    public void layReportCanBeRead(String hen) throws Exception {
        Response read = call("GET", url("lay_report", lastEventId()), null, null);
        assertEquals(200, read.status, "reading the lay report by its id: " + read.body);
        assertEquals(id("hen", hen), MAPPER.readTree(read.body).get("hen_id").asText());
    }

    @Then("replacing that lay report is refused")
    public void replacingIsRefused() throws Exception {
        Response put = call("PUT", url("lay_report", lastEventId()), ((ObjectNode) world.lastEvent.deepCopy()).put("eggs", 99), null);
        assertEquals(405, put.status, "a PUT of an event should answer 405, body: " + put.body);
    }

    @Then("deleting that lay report is refused")
    public void deletingIsRefused() throws Exception {
        Response delete = call("DELETE", url("lay_report", lastEventId()), null, null);
        assertEquals(405, delete.status, "a DELETE of an event should answer 405, body: " + delete.body);
    }

    // ------------------------------------------------------------ projections

    @Then("the hen {string} has produced {int} eggs eventually")
    public void henHasProduced(String hen, int eggs) throws Exception {
        String henId = id("hen", hen);
        eventually("hen " + hen + " has produced " + eggs + " eggs", () -> {
            for (JsonNode p : list("hen_productivity")) {
                if (henId.equals(p.path("hen_id").asText()) && p.path("total_eggs").asInt(-1) == eggs) {
                    return true;
                }
            }
            return false;
        });
    }

    @Then("no hen_productivity of {int} total eggs exists")
    public void noProductivityExists(int total) throws Exception {
        // Read the store itself, not the API: the question is whether the write landed.
        for (Envelope e : world.repositories.get("hen_productivity").list(new NoAuth().systemSession())) {
            assertFalse(e.payload().containsKey("total_eggs") && ((Number) e.payload().get("total_eggs")).intValue() == total,
                    "a hen_productivity of " + total + " total eggs exists: " + e.payload());
        }
        for (JsonNode p : list("hen_productivity")) {
            assertFalse(p.path("total_eggs").asInt(-1) == total, "the API lists a hen_productivity of " + total + ": " + p);
        }
    }

    // ------------------------------------------------------------------ graph

    @Given("the current instant is captured as {string}")
    public void captureInstant(String label) throws InterruptedException {
        Thread.sleep(CLOCK_GAP_MS);
        world.stamps.put(label, System.currentTimeMillis());
        Thread.sleep(CLOCK_GAP_MS);
    }

    @When("^I query the \"([^\"]+)\" graph with: (.+)$")
    public void queryGraph(String entity, String query) throws Exception {
        runQuery(entity, query);
    }

    @When("^I query the \"([^\"]+)\" graph as of \"([^\"]+)\" with: (.+)$")
    public void queryGraphAsOf(String entity, String label, String query) throws Exception {
        Long at = world.stamps.get(label);
        assertNotNull(at, "No instant was captured as '" + label + "'");
        runQuery(entity, withAt(query, at));
    }

    @Then("there should be no GraphQL errors")
    public void noGraphQlErrors() {
        assertNotNull(world.graphResponse, "no query was run");
        JsonNode errors = world.graphResponse.get("errors");
        assertTrue(errors == null || errors.isEmpty(), "GraphQL errors: " + errors);
    }

    @Then("the response at {string} should contain the number {int}")
    public void responseContainsNumber(String path, int expected) {
        assertNotNull(world.graphResponse, "no query was run");
        List<JsonNode> found = new ArrayList<>();
        collect(world.graphResponse, path.split("\\."), 0, found);
        assertTrue(found.stream().anyMatch(n -> n.isNumber() && n.asInt() == expected),
                "expected the number " + expected + " at " + path + ", found " + found + " in " + world.graphResponse);
    }

    // ---------------------------------------------------------------- helpers

    private void runQuery(String entity, String query) throws Exception {
        Response r = call("POST", world.env.graphUrl(entity), obj("query", query), null);
        world.graphResponse = MAPPER.readTree(r.body);
    }

    /** "... as of ..." gives the first query field the instant as its {@code at} argument. */
    static String withAt(String query, long at) {
        int open = query.indexOf('(', query.indexOf('{'));
        assertTrue(open > 0, "the query's first field takes arguments, to which `at` can be added: " + query);
        return query.substring(0, open + 1) + "at: " + at + ", " + query.substring(open + 1);
    }

    /** The path fans out across arrays: every node at the end of every branch. */
    private static void collect(JsonNode node, String[] path, int i, List<JsonNode> out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(n -> collect(n, path, i, out));
        } else if (i == path.length) {
            out.add(node);
        } else {
            collect(node.get(path[i]), path, i + 1, out);
        }
    }

    private void eventually(String what, BooleanSupplierThrows check) throws Exception {
        long deadline = System.nanoTime() + EVENTUALLY.toNanos();
        while (System.nanoTime() < deadline) {
            if (check.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("never became true within " + EVENTUALLY.toSeconds() + "s: " + what);
    }

    @FunctionalInterface
    private interface BooleanSupplierThrows {
        boolean getAsBoolean() throws Exception;
    }

    private String make(String entity, String name, ObjectNode doc) throws Exception {
        post(entity, doc, EggEconomyEnv.bearer(EggEconomyEnv.FRONT_END));
        assertEquals(201, world.lastStatus, "creating " + entity + " '" + name + "': " + world.lastBody);
        String id = idByPayload(entity, "name", name);
        world.ids.put(entity + ":" + name, id);
        return id;
    }

    private String id(String entity, String name) {
        String id = world.ids.get(entity + ":" + name);
        assertNotNull(id, "no " + entity + " '" + name + "' was made");
        return id;
    }

    /** The id of the document whose payload has {@code field} equal to {@code value}: a restlette does not answer a create with it. */
    private String idByPayload(String entity, String field, String value) {
        for (Envelope e : world.repositories.get(entity).list(new NoAuth().systemSession())) {
            if (value.equals(e.payload().get(field))) {
                return e.id();
            }
        }
        throw new AssertionError("no " + entity + " with " + field + " = " + value + " in storage");
    }

    private JsonNode payloadOf(String entity, String id) throws Exception {
        Response r = call("GET", url(entity, id), null, null);
        assertEquals(200, r.status, "reading " + entity + " " + id + ": " + r.body);
        return MAPPER.readTree(r.body);
    }

    /** The id of the event just posted: its hen and its timestamp name it (the store may hold numbers as doubles, so compare no more). */
    private String lastEventId() {
        assertNotNull(world.lastEvent, "no event was posted");
        for (Envelope e : world.repositories.get(world.lastEventEntity).list(new NoAuth().systemSession())) {
            if (world.lastEvent.get("hen_id").asText().equals(e.payload().get("hen_id"))
                    && world.lastEvent.get("timestamp").asText().equals(e.payload().get("timestamp"))) {
                return e.id();
            }
        }
        throw new AssertionError("the event is not in storage: " + world.lastEvent);
    }

    private List<JsonNode> list(String entity) throws Exception {
        Response r = call("GET", world.env.apiUrl(entity), null, null);
        assertEquals(200, r.status, "listing " + entity + ": " + r.body);
        List<JsonNode> out = new ArrayList<>();
        MAPPER.readTree(r.body).forEach(out::add);
        return out;
    }

    private String url(String entity, String id) {
        return world.env.apiUrl(entity) + "/" + id;
    }

    private void post(String entity, ObjectNode doc, String authorization) throws Exception {
        Response r = call("POST", world.env.apiUrl(entity), doc, authorization);
        world.lastStatus = r.status;
        world.lastBody = r.body;
    }

    private record Response(int status, String body) {
    }

    private Response call(String method, String url, ObjectNode body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(response.statusCode(), response.body());
    }

    private static ObjectNode obj(Object... keyValues) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i < keyValues.length; i += 2) {
            node.putPOJO((String) keyValues[i], keyValues[i + 1]);
        }
        return node;
    }
}
