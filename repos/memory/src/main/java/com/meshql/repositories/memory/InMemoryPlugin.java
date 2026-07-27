package com.meshql.repositories.memory;

import com.meshql.auth.noop.NoAuth;
import com.meshql.core.*;
import com.meshql.core.config.StorageConfig;

public class InMemoryPlugin implements Plugin {
    private final InMemoryStore store = new InMemoryStore();

    @Override
    public Searcher createSearcher(StorageConfig config) {
        return createSearcher(config, null);
    }

    @Override
    public Searcher createSearcher(StorageConfig config, Auth auth) {
        return new InMemorySearcher(store, auth != null ? auth : new NoAuth());
    }

    @Override
    public Repository createRepository(StorageConfig config, Auth auth) {
        return new InMemoryRepository(store);
    }

    @Override
    public void cleanUp() {
        // No cleanup needed
    }
}
