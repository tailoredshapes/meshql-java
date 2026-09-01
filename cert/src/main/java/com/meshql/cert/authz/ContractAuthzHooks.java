package com.meshql.cert.authz;

import com.meshql.cert.AuthzCredentials;
import com.meshql.core.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-scenario lifecycle every backend shares when running the shared
 * authorization contract.
 *
 * <h2>Why a whole server per scenario</h2>
 *
 * <p>This repository's own authorization suite has a {@code Given a fresh
 * workspace} step, and every document it writes carries a workspace value that
 * the vector query filters on. That step exists because the backends share one
 * datastore for the length of a suite: without a discriminator, one scenario's
 * records show up in the next scenario's list query.
 *
 * <p>The shared contract has no such step. Its {@code Background} stands up a
 * server and stops, and three of its scenarios then assert over an unfiltered
 * list ({@code lists all widgets}) or over every record of a single kind. Both
 * shapes are only meaningful against a store that starts empty, so the contract
 * is asserting isolation whether or not it says the word.
 *
 * <p>Rather than reintroduce the discriminator the contract deliberately drops,
 * each scenario gets a store of its own — a fresh table, collection, topic or
 * directory, named per scenario by {@link #freshBackend()} — and, because a
 * meshql server binds its storage config at construction time, a server of its
 * own on a port of its own. The expensive resources a backend needs (a
 * container, an embedded database) stay in the subclass's {@code @BeforeAll}
 * and are shared across scenarios; only the store inside them is new.
 *
 * <p>Subclasses supply {@link #freshBackend()} and wire Cucumber to
 * {@link #startScenario()} and {@link #stopScenario()} from their own
 * {@code @Before} and {@code @After} methods. The annotations live on the
 * subclass because Cucumber discovers hooks on the glue class it instantiates,
 * and this class is abstract.
 */
public abstract class ContractAuthzHooks {
    private static final Logger logger = LoggerFactory.getLogger(ContractAuthzHooks.class);

    /** The GraphQL schema and the Casbin fixture are identical for every backend. */
    private static Path schemaFile;
    private static List<String> casbinParams;

    protected final ContractAuthzWorld world;

    protected ContractAuthzHooks(ContractAuthzWorld world) {
        this.world = world;
    }

    /**
     * Build an empty store and a plugin that reaches it. Called once per scenario.
     *
     * <p>Implementations must return a store no earlier scenario has written to.
     */
    protected abstract ContractAuthzBackend freshBackend() throws Exception;

    /** Stand up this scenario's store and server. */
    public void startScenario() throws Exception {
        if (schemaFile == null) {
            schemaFile = ContractAuthzEnv.writeSchemaFile();
            casbinParams = AuthzCredentials.writeCasbinParams();
        }

        ContractAuthzBackend backend = freshBackend();

        Map<String, Plugin> plugins = new HashMap<>();
        plugins.put(backend.storage().type, backend.plugin());

        // Creating the repository is also what creates the table, collection or
        // stream, so it happens before the server takes its first request. The null
        // Auth is deliberate: this handle is the certification's window onto storage,
        // and it opens that window with the wildcard token rather than by holding an
        // Auth of its own. See ContractAuthzSteps.SYSTEM_TOKENS.
        world.repository = backend.plugin().createRepository(backend.storage(), null);

        world.env = new ContractAuthzEnv(
                ContractAuthzEnv.freePort(),
                schemaFile,
                backend.storage(),
                backend.queries(),
                casbinParams);
        world.plugins = plugins;
        world.server = world.env.buildService(plugins);

        world.ids.clear();
        world.stamps.clear();
        world.names.clear();
        world.temporalResult = null;
        world.deleteStatus = null;

        logger.debug("Contract authorization scenario ready on port {}", world.env.getPort());
    }

    /** Tear this scenario's server down so the next scenario starts clean. */
    public void stopScenario() throws Exception {
        if (world.server != null) {
            // Server.stop() also cleans up the plugins it was given.
            world.server.getClass().getMethod("stop").invoke(world.server);
            world.server = null;
        }
        world.repository = null;
        world.env = null;
    }
}
