package com.meshql.core;

import com.meshql.core.config.StorageConfig;

public interface Plugin {
    /**
     * Create a Searcher using whatever Auth the plugin was constructed with.
     *
     * @deprecated Prefer {@link #createSearcher(StorageConfig, Auth)} so the Auth the
     *             server resolved from its own configuration is the one that enforces
     *             read authorization. This overload leaves the two free to disagree.
     */
    @Deprecated
    Searcher createSearcher(StorageConfig config);

    /**
     * Create a Searcher that authorizes reads with the supplied Auth.
     *
     * <p>Read authorization happens inside the Searcher, which filters results with
     * {@code Auth.isAuthorized}. If the Searcher's Auth is not the one the server
     * configured, a deployment can have authorization enabled on its write path and
     * silently disabled on its read path. Passing it explicitly closes that gap.
     *
     * <p>The default delegates to the single-argument form for backwards
     * compatibility; implementations should override it.
     *
     * @param auth The Auth the server resolved, or null to fall back to the plugin's own
     */
    @SuppressWarnings("deprecation")
    default Searcher createSearcher(StorageConfig config, Auth auth) {
        return createSearcher(config);
    }

    Repository createRepository(StorageConfig config, Auth auth);
    void cleanUp();

    default boolean isHealthy() {
        return true;
    }
}
