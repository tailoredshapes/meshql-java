package com.meshql.cert.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.cert.AuthzCredentials;
import com.meshql.cert.HttpClient;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Bindings for the shared contract's {@code schema.feature}.
 *
 * <p>The scenario's schema becomes the widget restlette's configured schema, so
 * what is certified is the validation a deployment gets, not a check the harness
 * makes. The "tries to" writes record their status without asserting it; the
 * listing, version and read steps come from {@link ContractAuthzSteps} and
 * {@link ContractVersionsSteps}.
 */
public class ContractSchemaSteps {

    private final ContractAuthzWorld world;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public ContractSchemaSteps(ContractAuthzWorld world) {
        this.world = world;
        this.httpClient = new HttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Given("an authorizing MeshQL server whose widgets must match the JSON Schema:")
    public void serverWithSchema(String schemaText) throws Exception {
        assertNotNull(world.env, "The adapter's hooks should have built an environment");
        JsonNode node = objectMapper.readTree(schemaText);
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(node);
        world.startServer(null, schema);
        assertNotNull(world.server, "The server should be running");
    }

    @When("{string} tries to create a widget {string} of kind {string}")
    public void triesToCreate(String caller, String name, String kind) throws Exception {
        post(caller, Map.of("name", name, "kind", kind));
    }

    @When("{string} tries to create a widget {string} with no kind")
    public void triesToCreateWithoutKind(String caller, String name) throws Exception {
        post(caller, Map.of("name", name));
    }

    @When("{string} tries to update widget {string} to kind {string}")
    public void triesToUpdate(String caller, String name, String kind) throws Exception {
        String id = world.ids.get(name);
        assertNotNull(id, "No widget named '" + name + "' was created");
        httpClient.put(world.env.restUrl() + "/" + id,
                objectMapper.writeValueAsString(Map.of("name", name, "kind", kind)),
                AuthzCredentials.headerFor(caller));
        world.writeStatus = httpClient.getLastStatus();
    }

    @Then("the write should be refused as invalid")
    public void refusedAsInvalid() {
        assertNotNull(world.writeStatus, "No write was attempted");
        assertEquals(400, world.writeStatus,
                "A write the restlette's JSON Schema refuses must be answered 400");
    }

    private void post(String caller, Map<String, String> body) throws Exception {
        httpClient.post(world.env.restUrl(), objectMapper.writeValueAsString(body), AuthzCredentials.headerFor(caller));
        world.writeStatus = httpClient.getLastStatus();
    }
}
