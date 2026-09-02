package com.meshql.repos.sqlite;

import com.meshql.core.Plugin;
import com.meshql.core.Repository;
import com.meshql.core.Searcher;
import com.meshql.core.config.StorageConfig;

import javax.sql.DataSource;

/**
 * Wrapper to adapt SQLiteRepository/SQLiteSearcher to the Plugin interface for certification.
 */
public class SQLitePluginWrapper implements Plugin {
    private final DataSource dataSource;
    private final String tableName;

    public SQLitePluginWrapper(DataSource dataSource, String tableName) {
        this.dataSource = dataSource;
        this.tableName = tableName;
    }

    @Override
    public Searcher createSearcher(StorageConfig config) {
        return new SQLiteSearcher(dataSource, tableName);
    }

    @Override
    public Repository createRepository(StorageConfig config) {
        try {
            return new SQLiteRepository(dataSource.getConnection(), tableName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create SQLiteRepository", e);
        }
    }

    @Override
    public void cleanUp() {
        // No-op
    }
}
