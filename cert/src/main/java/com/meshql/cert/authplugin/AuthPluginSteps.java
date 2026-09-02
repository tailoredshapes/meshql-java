package com.meshql.cert.authplugin;

import com.meshql.cert.authz.ContractAuthzWorld;
import com.meshql.core.Auth;
import io.cucumber.java.en.Given;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The one step {@code auth_plugin.feature} adds: naming the plugin the server runs.
 *
 * <p>Every other step it uses is shared with {@code authz.feature} and lives in
 * {@code ContractAuthzSteps}, because the two suites drive the same HTTP surfaces
 * the same way. What differs is only which plugin is answering.
 */
public class AuthPluginSteps {
    private final ContractAuthzWorld world;

    public AuthPluginSteps(ContractAuthzWorld world) {
        this.world = world;
    }

    @Given("a MeshQL server running the {string} auth plugin")
    public void serverRunningPlugin(String name) throws Exception {
        assertNotNull(world.env, "The adapter's hooks should have built an environment");
        assertNotNull(world.repository,
                "A Repository handle is needed to resolve ids and inspect stored envelopes");

        world.startServer(pluginNamed(name));
        assertNotNull(world.server, "The server should be running");
    }

    private static Auth pluginNamed(String name) {
        return switch (name) {
            case "refuse-reads" -> new RefuseReadsAuth();
            case "owner" -> new OwnerAuth();
            default -> throw new IllegalArgumentException("Unknown auth plugin: " + name);
        };
    }
}
