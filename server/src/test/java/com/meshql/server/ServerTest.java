package com.meshql.server;

import com.meshql.auth.noop.NoAuth;
import com.meshql.core.*;
import com.meshql.core.config.GraphletteConfig;
import com.meshql.core.config.QueryConfig;
import com.meshql.core.config.RootConfig;
import com.meshql.core.config.StorageConfig;
import com.tailoredshapes.stash.Stash;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Server class.
 */
class ServerTest {
    private Server server;
    private Path schemaFile;
    private TestPlugin testPlugin;
    private static final int TEST_PORT = 14044;

    @BeforeEach
    void setUp() throws Exception {
        // Create a test GraphQL schema
        schemaFile = Files.createTempFile("test_schema", ".graphql");
        Files.writeString(schemaFile, """
            type Query {
              getById(id: ID): TestEntity
            }

            type TestEntity {
              id: ID
              name: String
            }
            """);

        // Create test plugin
        testPlugin = new TestPlugin();

        // Create server
        server = new Server(Map.of("test", testPlugin));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (schemaFile != null) {
            Files.deleteIfExists(schemaFile);
        }
    }

    @Test
    void testServerStartsAndStops() throws Exception {
        StorageConfig storageConfig = new StorageConfig("test");

        Config config = Config.builder()
            .port(TEST_PORT)
            .graphlette(GraphletteConfig.builder()
                .path("/test/graph")
                .storage(storageConfig)
                .schema(schemaFile.toString())
                .rootConfig(RootConfig.builder()
                    .singleton("getById", "{\"id\": \"{{id}}\"}")))
            .build();

        server.init(config);
        assertTrue(true, "Server should start without exceptions");

        server.stop();
        assertTrue(true, "Server should stop without exceptions");
    }

    @Test
    void testHealthEndpoint() throws Exception {
        StorageConfig storageConfig = new StorageConfig("test");

        Config config = Config.builder()
            .port(TEST_PORT)
            .graphlette(GraphletteConfig.builder()
                .path("/test/graph")
                .storage(storageConfig)
                .schema(schemaFile.toString())
                .rootConfig(RootConfig.builder()
                    .singleton("getById", "{\"id\": \"{{id}}\"}")))
            .build();

        server.init(config);

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + TEST_PORT + "/health"))
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("ok"));
    }

    @Test
    void testGraphletteRegistration() throws Exception {
        StorageConfig storageConfig = new StorageConfig("test");

        Config config = Config.builder()
            .port(TEST_PORT)
            .graphlette(GraphletteConfig.builder()
                .path("/test/graph")
                .storage(storageConfig)
                .schema(schemaFile.toString())
                .rootConfig(RootConfig.builder()
                    .singleton("getById", "{\"id\": \"{{id}}\"}")))
            .build();

        server.init(config);

        // Verify the graphlette endpoint exists by making a request
        HttpClient client = HttpClient.newHttpClient();
        String query = "{\"query\":\"{getById(id:\\\"test123\\\"){id name}}\"}";
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:" + TEST_PORT + "/test/graph"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(query))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("data"), "Response should contain 'data' field");
    }

    /**
     * Test plugin for unit tests
     */
    private static class TestPlugin implements Plugin {
        @Override
        public Searcher createSearcher(StorageConfig config) {
            return new TestSearcher();
        }

        @Override
        public Repository createRepository(StorageConfig config) {
            return new TestRepository();
        }

        @Override
        public void cleanUp() {
            // No-op
        }
    }

    /**
     * Test searcher that returns mock data
     */
    private static class TestSearcher implements Searcher {
        @Override
        public Stash find(com.github.jknack.handlebars.Template queryTemplate, Stash args, AuthSession session, long timestamp) {
            Stash result = new Stash();
            result.put("id", "test123");
            result.put("name", "Test Entity");
            return result;
        }

        @Override
        public List<Stash> findAll(com.github.jknack.handlebars.Template queryTemplate, Stash args, AuthSession session, long timestamp) {
            return List.of(find(queryTemplate, args, session, timestamp));
        }
    }

    /**
     * Test repository (not used in these tests)
     */
    private static class TestRepository implements Repository {
        @Override
        public Envelope create(Envelope envelope, AuthSession session) {
            return session.stamp(new Envelope(
                "test123",
                envelope.payload(),
                Instant.now(),
                false,
                null
            ));
        }

        @Override
        public List<Envelope> createMany(List<Envelope> envelopes, AuthSession session) {
            return envelopes.stream()
                .map(e -> create(e, session))
                .collect(java.util.stream.Collectors.toList());
        }

        @Override
        public Optional<Envelope> read(String id, AuthSession session, Instant createdAt) {
            return Optional.empty();
        }

        @Override
        public List<Envelope> readMany(List<String> ids, AuthSession session) {
            return Collections.emptyList();
        }

        @Override
        public Boolean remove(String id, AuthSession session) {
            return true;
        }

        @Override
        public Map<String, Boolean> removeMany(List<String> ids, AuthSession session) {
            return Collections.emptyMap();
        }

        @Override
        public List<Envelope> list(AuthSession session) {
            return Collections.emptyList();
        }

        @Override
        public List<com.meshql.core.VersionRef> listVersions(String id, AuthSession session) {
            return Collections.emptyList();
        }

        @Override
        public Optional<Envelope> readVersion(String id, String token, AuthSession session) {
            return Optional.empty();
        }
    }
}
