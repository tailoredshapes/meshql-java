package com.meshql.cert.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.cert.AuthzCredentials;
import com.meshql.cert.HttpClient;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bindings for the shared contract's {@code versions.feature}.
 *
 * <p>Every step goes over the wire, against the same widget restlette the
 * authorization contract drives, so what is certified is the REST surface a
 * caller actually gets rather than the {@code Repository} method underneath it.
 * The background, the create step and the delete step come from
 * {@link ContractAuthzSteps}; what is added here is the update, and the
 * assertions about a version listing.
 */
public class ContractVersionsSteps {

    private final ContractAuthzWorld world;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ContractVersionsSteps(ContractAuthzWorld world) {
        this.world = world;
        this.httpClient = new HttpClient();
        this.objectMapper = new ObjectMapper();
    }

    // ---------------------------------------------------------------- writes

    @When("{string} updates widget {string} to kind {string}")
    public void updatesWidget(String caller, String name, String kind) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("name", name, "kind", kind));

        JsonNode response = httpClient.put(
                world.env.restUrl() + "/" + widgetId(name),
                body,
                AuthzCredentials.headerFor(caller));

        assertEquals(200, httpClient.getLastStatus(),
                "'" + caller + "' updating widget '" + name + "' should succeed, got: " + response);
    }

    // ------------------------------------------------------------- listings

    @Then("the version list for widget {string} seen by {string} should have {int} entries")
    public void versionListHasEntries(String name, String caller, int expected) throws IOException {
        world.versions = fetchVersions(name, caller);
        assertEquals(expected, world.versions.size(),
                "version list for '" + name + "' seen by '" + caller + "': " + world.versions);
    }

    @Then("the version list for widget {string} seen by {string} should have {int} entry")
    public void versionListHasOneEntry(String name, String caller, int expected) throws IOException {
        versionListHasEntries(name, caller, expected);
    }

    /**
     * The entries arrive oldest first.
     *
     * <p>The timestamps are parsed rather than compared as text. An instant with
     * no fractional part is written without one, and {@code "…:00Z"} sorts after
     * {@code "…:00.500Z"} lexicographically, so a string comparison would call a
     * correctly ordered list wrong roughly once in a thousand runs.
     */
    @Then("that version list should be ordered oldest first")
    public void orderedOldestFirst() {
        List<Instant> stamps = new ArrayList<>();
        for (JsonNode entry : requireVersions()) {
            JsonNode createdAt = entry.get("created_at");
            assertNotNull(createdAt, "version entry carried no created_at: " + entry);
            stamps.add(Instant.parse(createdAt.asText()));
        }

        List<Instant> sorted = new ArrayList<>(stamps);
        java.util.Collections.sort(sorted);
        assertEquals(sorted, stamps, "version list is not oldest first: " + stamps);
    }

    @Then("every entry in that version list should carry a URL")
    public void everyEntryCarriesAUrl() {
        for (JsonNode entry : requireVersions()) {
            assertNotNull(entry.get("url"), "entry carried no url: " + entry);
        }
    }

    @Then("no entry in that version list should carry a URL")
    public void noEntryCarriesAUrl() {
        for (JsonNode entry : requireVersions()) {
            assertTrue(entry.get("url") == null,
                    "entry carried a url it should not have: " + entry);
            assertTrue(entry.path("unauthorized").asBoolean(false),
                    "an unreadable version must say so: " + entry);
            assertNotNull(entry.get("created_at"),
                    "an unreadable version still carries when it happened: " + entry);
        }
    }

    @Then("the last entry in that version list should be marked deleted")
    public void lastEntryIsDeleted() {
        List<JsonNode> versions = requireVersions();
        JsonNode last = versions.get(versions.size() - 1);
        assertTrue(last.path("deleted").asBoolean(false),
                "the last version should be the deletion: " + last);
    }

    @Then("listing the versions of widget {string} as {string} twice should give the same URLs")
    public void listingTwiceIsStable(String name, String caller) throws IOException {
        List<JsonNode> first = fetchVersions(name, caller);
        List<JsonNode> second = fetchVersions(name, caller);

        assertEquals(urlsOf(first), urlsOf(second),
                "a version's address changed between two listings");
        world.versions = first;
    }

    // --------------------------------------------------------- one version

    @Then("reading version {int} of widget {string} as {string} should return kind {string}")
    public void readingVersionReturnsKind(int n, String name, String caller, String kind) throws IOException {
        if (world.versions.isEmpty()) {
            world.versions = fetchVersions(name, caller);
        }

        JsonNode body = httpClient.get(
                world.env.platformUrl() + urlOf(n), AuthzCredentials.headerFor(caller));

        assertEquals(200, httpClient.getLastStatus(),
                "reading version " + n + " of '" + name + "': " + body);
        assertEquals(kind, body.path("kind").asText(null),
                "version " + n + " of '" + name + "' should be the version as it was then: " + body);
    }

    @Then("reading version token {string} of widget {string} as {string} should not be found")
    public void unknownTokenIsNotFound(String token, String name, String caller) throws IOException {
        String url = world.env.restUrl() + "/" + widgetId(name) + "/versions/" + token;

        JsonNode body = httpClient.get(url, AuthzCredentials.headerFor(caller));

        assertEquals(404, httpClient.getLastStatus(),
                "an unknown version token must not be found: " + body);
    }

    @Then("reading version {int} of widget {string} as {string} should be refused")
    public void readingVersionIsRefused(int n, String name, String caller) throws IOException {
        // The URL is discovered as a caller who *can* see it, then presented by one
        // who cannot - otherwise the listing would hand out no URL to try.
        world.versions = fetchVersions(name, AuthzCredentials.USER_A);

        JsonNode body = httpClient.get(
                world.env.platformUrl() + urlOf(n), AuthzCredentials.headerFor(caller));

        int status = httpClient.getLastStatus();
        assertTrue(status == 403 || status == 404,
                "'" + caller + "' must not read version " + n + " of '" + name
                        + "', got " + status + ": " + body);
    }

    // ------------------------------------------------------------- helpers

    /** {@code GET /<entity>/api/<id>/versions} as a caller. */
    private List<JsonNode> fetchVersions(String name, String caller) throws IOException {
        String id = widgetId(name);
        JsonNode body = httpClient.get(
                world.env.restUrl() + "/" + id + "/versions", AuthzCredentials.headerFor(caller));

        assertEquals(200, httpClient.getLastStatus(),
                "version listing for '" + name + "' failed: " + body);
        assertEquals(id, body.path("id").asText(null),
                "a version listing names the document it belongs to: " + body);

        JsonNode versions = body.get("versions");
        assertNotNull(versions, "version listing carried no `versions` array: " + body);
        assertTrue(versions.isArray(), "`versions` should be an array: " + body);

        List<JsonNode> entries = new ArrayList<>();
        versions.forEach(entries::add);
        return entries;
    }

    private String urlOf(int n) {
        List<JsonNode> versions = requireVersions();
        assertTrue(n >= 1 && n <= versions.size(),
                "no version " + n + "; the list holds " + versions.size());

        JsonNode url = versions.get(n - 1).get("url");
        assertNotNull(url, "version " + n + " carried no url");
        return url.asText();
    }

    private List<String> urlsOf(List<JsonNode> versions) {
        List<String> urls = new ArrayList<>();
        for (JsonNode entry : versions) {
            urls.add(entry.path("url").asText(""));
        }
        return urls;
    }

    private List<JsonNode> requireVersions() {
        assertFalse(world.versions.isEmpty(), "No version listing has been fetched");
        return world.versions;
    }

    private String widgetId(String name) {
        String id = world.ids.get(name);
        assertNotNull(id, "No widget named '" + name + "' was created");
        return id;
    }
}
