package com.meshql.repositories.postgres;

import com.meshql.core.Plugin;
import com.meshql.core.Repository;
import com.meshql.core.Searcher;
import com.meshql.core.config.StorageConfig;

import javax.sql.DataSource;

/**
 * Wrapper to adapt PostgresRepository/PostgresSearcher to the Plugin interface for certification.
 */
public class PostgresPluginWrapper implements Plugin {
    private final DataSource dataSource;
    private final String tableName;

    public PostgresPluginWrapper(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = tableName;
    }

    @Override
    public Searcher createSearcher(StorageConfig config) {
        return new PostgresSearcher(dataSource, tableName);
    }

    @Override
    public Repository createRepository(StorageConfig config) {
        return new PostgresRepository(dataSource, tableName);
    }

    @Override
    public void cleanUp() {
        // No-op - testcontainers handles cleanup
    }
}
