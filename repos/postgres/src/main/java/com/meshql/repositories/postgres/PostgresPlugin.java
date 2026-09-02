package com.meshql.repositories.postgres;

import com.meshql.core.Plugin;
import com.meshql.core.Repository;
import com.meshql.core.Searcher;
import com.meshql.core.config.StorageConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL plugin implementation.
 * Manages connection pools and creates PostgreSQL repositories and searchers.
 */
public class PostgresPlugin implements Plugin {
    private final Map<String, HikariDataSource> dataSources = new HashMap<>();

    @Override
    public Searcher createSearcher(StorageConfig config) {
        PostgresConfig pgConfig = (PostgresConfig) config;
        DataSource dataSource = getOrCreateDataSource(pgConfig);
        return new PostgresSearcher(dataSource, pgConfig.table);
    }

    @Override
    public Repository createRepository(StorageConfig config) {
        PostgresConfig pgConfig = (PostgresConfig) config;
        DataSource dataSource = getOrCreateDataSource(pgConfig);
        return new PostgresRepository(dataSource, pgConfig.table);
    }

    @Override
    public void cleanUp() {
        for (HikariDataSource dataSource : dataSources.values()) {
            dataSource.close();
        }
        dataSources.clear();
    }

    @Override
    public boolean isHealthy() {
        try {
            for (HikariDataSource ds : dataSources.values()) {
                try (var conn = ds.getConnection()) {
                    if (!conn.isValid(1)) return false;
                }
            }
            return !dataSources.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Gets or creates a DataSource for the given config.
     * Reuses DataSources for the same connection URL.
     */
    private DataSource getOrCreateDataSource(PostgresConfig config) {
        String key = config.uri != null ? config.uri : "default";

        return dataSources.computeIfAbsent(key, k -> {
            HikariConfig hikariConfig = new HikariConfig();
            hikariConfig.setJdbcUrl(config.uri);

            if (config.username != null) {
                hikariConfig.setUsername(config.username);
            }
            if (config.password != null) {
                hikariConfig.setPassword(config.password);
            }

            hikariConfig.setMaximumPoolSize(config.maxPoolSize);
            hikariConfig.setMinimumIdle(config.minIdle);

            return new HikariDataSource(hikariConfig);
        });
    }
}
