package com.meshql.cert;

import com.meshql.core.Plugin;
import com.meshql.core.config.StorageConfig;
import io.cucumber.java.Before;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract hooks for farm certification tests.
 * Database plugin implementations should extend this class and:
 * 1. Provide the Plugin instance
 * 2. Set up and tear down the server
 * 3. Create repositories for farm, coop, and hen entities
 */
public abstract class FarmHooks {
    private static final Logger logger = LoggerFactory.getLogger(FarmHooks.class);
    protected final FarmWorld world;

    public FarmHooks(FarmWorld world) {
        this.world = world;
    }

    /**
     * Subclasses must implement this to provide the plugin to test.
     */
    protected abstract Plugin createPlugin();

    /**
     * Subclasses can override this to provide storage type name.
     * Default is "test".
     */
    protected String getStorageType() {
        return "test";
    }

    /**
     * Subclasses must implement this to start the server.
     * The server should be stored in world.server and should listen on world.port.
     */
    protected abstract void startServer() throws Exception;

    /**
     * Subclasses must implement this to stop the server.
     */
    protected abstract void stopServer();

    /**
     * Subclasses can override this to create custom storage configs for each entity type.
     * Default implementation creates generic StorageConfig instances.
     */
    protected StorageConfig createFarmStorageConfig() {
        return new StorageConfig(getStorageType());
    }

    protected StorageConfig createCoopStorageConfig() {
        return new StorageConfig(getStorageType());
    }

    protected StorageConfig createHenStorageConfig() {
        return new StorageConfig(getStorageType());
    }

    @Before
    public void setUp() throws Exception {
        logger.info("Setting up farm certification test");

        // Create plugin
        Plugin plugin = createPlugin();
        world.plugins.put(getStorageType(), plugin);

        // Create repositories for each entity type
        StorageConfig farmStorage = createFarmStorageConfig();
        StorageConfig coopStorage = createCoopStorageConfig();
        StorageConfig henStorage = createHenStorageConfig();

        world.repositories.put("farm", plugin.createRepository(farmStorage));
        world.repositories.put("coop", plugin.createRepository(coopStorage));
        world.repositories.put("hen", plugin.createRepository(henStorage));

        // Start server (subclass responsibility)
        startServer();

        logger.info("Farm test setup complete");
    }

    public void tearDown() {
        logger.info("Tearing down farm certification test");
        stopServer();
    }

    // GraphQL schema constants that subclasses can use

    protected static final String FARM_SCHEMA = """
        type Query {
          getById(id: ID, at: Float): Farm
        }

        type Farm {
          name: String!
          id: ID
          coops: [Coop]
        }

        type Coop {
          name: String!
          id: ID
          hens: [Hen]
        }

        type Hen {
          name: String!
          coop: Coop
          eggs: Int
          id: ID
        }
        """;

    protected static final String COOP_SCHEMA = """
        type Farm {
          name: String!
          id: ID
        }

        type Query {
          getByName(name: String, at: Float): Coop
          getById(id: ID, at: Float): Coop
          getByFarm(id: ID, at: Float): [Coop]
        }

        type Coop {
          name: String!
          farm: Farm!
          id: ID
          hens: [Hen]
        }

        type Hen {
          name: String!
          eggs: Int
          id: ID
        }
        """;

    protected static final String HEN_SCHEMA = """
        type Farm {
          name: String!
          id: ID
          coops: [Coop]
        }

        type Coop {
          name: String!
          farm: Farm!
          id: ID
        }

        type Query {
          getByName(name: String, at: Float): [Hen]
          getById(id: ID, at: Float): Hen
          getByCoop(id: ID, at: Float): [Hen]
        }

        type Hen {
          name: String!
          coop: Coop
          eggs: Int
          id: ID
        }
        """;
}
