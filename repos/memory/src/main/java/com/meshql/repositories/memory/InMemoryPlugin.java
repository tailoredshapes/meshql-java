package com.meshql.repositories.memory;

import com.meshql.core.*;
import com.meshql.core.config.StorageConfig;

public class InMemoryPlugin implements Plugin {
    private final InMemoryStore store = new InMemoryStore();

    @Override
    public Searcher createSearcher(StorageConfig config) {
        return new InMemorySearcher(store);
    }

    @Override
    public Repository createRepository(StorageConfig config) {
        return new InMemoryRepository(store);
    }

    @Override
    public void cleanUp() {
        // No cleanup needed
    }
}
