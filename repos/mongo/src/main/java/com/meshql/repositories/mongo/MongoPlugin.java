package com.meshql.repositories.mongo;

import com.meshql.core.*;
import com.meshql.core.config.StorageConfig;
import com.mongodb.client.*;
import org.bson.Document;

import java.util.HashMap;
import java.util.Map;

public class MongoPlugin implements Plugin {
    private final Auth auth;
    private final Map<String, MongoClient> clients = new HashMap<>();

    public MongoPlugin(Auth auth) {
        this.auth = auth;
    }

    private MongoClient getOrCreateClient(MongoConfig config) {
        return clients.computeIfAbsent(config.uri, k -> MongoClients.create(k));
    }

    @Override
    public Searcher createSearcher(StorageConfig sc) {
        return createSearcher(sc, null);
    }

    @Override
    public Searcher createSearcher(StorageConfig sc, Auth auth) {
        MongoConfig config = (MongoConfig) sc;
        MongoClient mongoClient = getOrCreateClient(config);
        MongoDatabase database = mongoClient.getDatabase(config.db);
        MongoCollection<Document> collection = database.getCollection(config.collection);
        return new MongoSearcher(collection, auth != null ? auth : this.auth);
    }

    @Override
    public Repository createRepository(StorageConfig sc, Auth auth) {
        MongoConfig config = (MongoConfig) sc;
        MongoClient mongoClient = getOrCreateClient(config);
        MongoDatabase database = mongoClient.getDatabase(config.db);
        MongoCollection<Document> collection = database.getCollection(config.collection);
        return new MongoRepository(collection);
    }

    @Override
    public void cleanUp() {
        clients.values().forEach(MongoClient::close);
        clients.clear();
    }

    @Override
    public boolean isHealthy() {
        try {
            for (MongoClient client : clients.values()) {
                client.listDatabaseNames().first();
            }
            return !clients.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }
}
