package com.meshql.repos.sqlite;

import com.meshql.core.Plugin;
import com.meshql.core.Repository;
import com.meshql.core.Searcher;
import com.meshql.core.config.StorageConfig;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.tailoredshapes.underbar.ocho.Die.rethrow;
import static com.tailoredshapes.underbar.ocho.UnderBar.each;

public class SQLitePlugin implements Plugin {
    Map<String, SQLiteDataSource> dataSources = new ConcurrentHashMap<>();

    @Override
    public Searcher createSearcher(StorageConfig config) {
        assert config instanceof SQLiteConfig;
        SQLiteConfig c = (SQLiteConfig)config;
        var dataSource = buildDatasource(c);
        return new SQLiteSearcher(dataSource, c.table);
    }

    private SQLiteDataSource buildDatasource(SQLiteConfig c) {
        return dataSources.computeIfAbsent(c.file, file -> {
            var dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + file);
            try (var conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            } catch (java.sql.SQLException e) {
                throw new RuntimeException("Failed to enable WAL mode for " + file, e);
            }
            return dataSource;
        });
    }

    @Override
    public Repository createRepository(StorageConfig config) {
        assert config instanceof SQLiteConfig;
        SQLiteConfig c = (SQLiteConfig)config;
        var dataSource = buildDatasource(c);
        SQLiteRepository sqLiteRepository = new SQLiteRepository(rethrow(() -> dataSource.getConnection()), c.table, c.indexedFields);
        sqLiteRepository.initialize();
        return sqLiteRepository;
    }

    @Override
    public boolean isHealthy() {
        try {
            for (SQLiteDataSource ds : dataSources.values()) {
                try (var conn = ds.getConnection()) {
                    if (!conn.isValid(1)) return false;
                }
            }
            return !dataSources.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void cleanUp() {
        each(dataSources, (k,v) -> {
           if(!k.startsWith(":memory:")){
               File file = new File(k);
               file.delete();
           }
        });
    }
}
