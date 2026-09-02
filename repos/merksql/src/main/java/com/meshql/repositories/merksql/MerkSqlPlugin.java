package com.meshql.repositories.merksql;

import com.meshql.core.*;
import com.meshql.core.config.StorageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class MerkSqlPlugin implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(MerkSqlPlugin.class);

    private final Map<String, Long> handles = new HashMap<>();
    private final Map<String, MerkSqlStore> stores = new HashMap<>();

    private long getOrCreateHandle(MerkSqlConfig config) {
        String brokerDir = config.dataDir + "/" + config.topic;
        return handles.computeIfAbsent(brokerDir, k -> {
            logger.info("Opening merksql broker at: {}", brokerDir);
            return MerkSqlNative.create(brokerDir);
        });
    }

    private MerkSqlStore getOrCreateStore(MerkSqlConfig config) {
        String key = config.dataDir + ":" + config.topic;
        return stores.computeIfAbsent(key, k -> {
            long handle = getOrCreateHandle(config);
            return new MerkSqlStore(handle, config.topic);
        });
    }

    @Override
    public Repository createRepository(StorageConfig sc) {
        MerkSqlConfig config = (MerkSqlConfig) sc;
        MerkSqlStore store = getOrCreateStore(config);
        return new MerkSqlRepository(store);
    }

    @Override
    public Searcher createSearcher(StorageConfig sc) {
        MerkSqlConfig config = (MerkSqlConfig) sc;
        MerkSqlStore store = getOrCreateStore(config);
        return new MerkSqlSearcher(store);
    }

    @Override
    public void cleanUp() {
        handles.values().forEach(handle -> {
            try {
                MerkSqlNative.stopAllQueries(handle);
                MerkSqlNative.destroy(handle);
            } catch (Exception e) {
                logger.warn("Error destroying merksql handle", e);
            }
        });
        handles.clear();
        stores.clear();
    }

    /**
     * Create a MerkSqlConfig with a temporary data directory.
     * Useful for tests.
     */
    public static MerkSqlConfig tempConfig(String topic) {
        try {
            Path tempDir = Files.createTempDirectory("merksql-test-");
            tempDir.toFile().deleteOnExit();
            MerkSqlConfig config = new MerkSqlConfig();
            config.dataDir = tempDir.toString();
            config.topic = topic;
            return config;
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp directory", e);
        }
    }
}
