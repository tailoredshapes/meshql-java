package com.meshql.core;

import com.meshql.core.config.StorageConfig;

/**
 * Factory for a storage backend.
 *
 * <p>Neither factory method takes an {@link Auth} any more. A Searcher used to
 * be handed one so it could filter reads, which meant a deployment could have
 * authorization enabled on its write path and a different - or absent - Auth on
 * its read path. Authorization now travels with the request, as an
 * {@link AuthSession} passed to each call, so there is nothing left to
 * disagree about.
 */
public interface Plugin {
    Searcher createSearcher(StorageConfig config);

    Repository createRepository(StorageConfig config);

    void cleanUp();

    default boolean isHealthy() {
        return true;
    }
}
