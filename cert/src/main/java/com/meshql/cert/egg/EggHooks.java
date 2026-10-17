package com.meshql.cert.egg;

import com.meshql.core.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * The per-scenario lifecycle every store shares when it runs the egg economy
 * feature: a store of its own (a fresh table, collection or file per entity) and a
 * server of its own on a port of its own, so one scenario's documents never show up
 * in the next one's.
 *
 * <p>Subclasses supply {@link #freshBackend()} and wire Cucumber to
 * {@link #startScenario()} and {@link #stopScenario()} from their own {@code @Before}
 * and {@code @After} methods, because Cucumber discovers hooks on the glue class it
 * instantiates and this class is abstract. The server and the harness's bridge to the
 * worker are started by the feature's first step.
 */
public abstract class EggHooks {
    private static final Logger logger = LoggerFactory.getLogger(EggHooks.class);

    protected final EggWorld world;

    protected EggHooks(EggWorld world) {
        this.world = world;
    }

    /** An empty store, the plugin that reaches it, and its query templates. Called once per scenario. */
    protected abstract EggBackend freshBackend() throws Exception;

    public void startScenario() throws Exception {
        world.backend = freshBackend();
        world.plugin = world.backend.plugin();
        // Creating a repository creates its table, collection or file: before the first request.
        for (EggEconomy.Entity e : EggEconomy.ENTITIES) {
            world.repositories.put(e.name(), world.plugin.createRepository(world.backend.storageFor().apply(e.name())));
        }
        world.env = new EggEconomyEnv(EggEconomyEnv.freePort(), world.backend);
        logger.debug("Egg economy scenario prepared on port {}", world.env.port());
    }

    public void stopScenario() throws Exception {
        world.stop();
        world.repositories.clear();
    }

    /** What the feature's first step does: serve the deployment, and connect the worker to its events. */
    public static void deploy(EggWorld world) throws Exception {
        Map<String, Plugin> plugins = new HashMap<>();
        plugins.put(world.backend.storageFor().apply("farm").type, world.plugin);
        world.server = world.env.start(plugins);
        world.bridge = new EventBridge(world.plugin, world.backend, new HenWorker(world.env));
    }
}
