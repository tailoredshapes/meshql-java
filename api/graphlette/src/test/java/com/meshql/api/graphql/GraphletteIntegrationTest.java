package com.meshql.api.graphql;

import com.google.gson.Gson;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;

import com.meshql.core.config.*;
import com.meshql.repositories.memory.InMemoryRepository;
import com.meshql.repositories.memory.InMemorySearcher;
import com.meshql.repositories.memory.InMemoryStore;
import com.tailoredshapes.stash.Stash;
import graphql.schema.DataFetcher;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;


import static com.tailoredshapes.stash.Stash.stash;
import static com.tailoredshapes.underbar.ocho.Die.rethrow;
import static com.tailoredshapes.underbar.ocho.UnderBar.list;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GraphletteIntegrationTest {
    private static Server server;
    private static final int PORT = 4569;
    private static final String API_PATH = "/test/graphql";
    private static HttpClient httpClient;
    private static String BASE_URL;

    private static final String TEST_SCHEMA = """
            type Query {
                testObject(id: ID!): TestObject
                getByTitle(title: String!): [TestObject]
            }

            type TestObject {
                id: ID!
                title: String!
                content: String
                tags: [String]
            }
            """;

    @BeforeAll
    static void setUp() throws Exception {
        BASE_URL = "http://localhost:" + PORT + API_PATH;
        httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

        Auth auth = new NoAuth();

        InMemoryStore store = new InMemoryStore();
        InMemoryRepository inMemoryRepository = new InMemoryRepository(store);

        Stash payload = stash(
                "title", "Test Object 1",
                "content", "This is test content",
                "tags", list("test", "graphql")
        );

        InMemorySearcher searcher = new InMemorySearcher(store);

        inMemoryRepository.create(new Envelope(
            "testobj1", payload, null, false, null
        ), auth.systemSession());


        RootConfig rootConfig = RootConfig.builder()
                .singleton("testObject", "id = '{{id}}'")
                .vector("getByTitle", "title = '{{title}}'")
                .build();

        DTOFactory dtoFactory = new DTOFactory(list(), list(), list(), list(), stash());
        Map<String, DataFetcher> fetchers = Root.create(searcher, dtoFactory, auth, rootConfig);

        Graphlette graphlette = new Graphlette(fetchers, TEST_SCHEMA);

        // Setup Jetty 12 server
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(PORT);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        server.setHandler(context);

        context.addServlet(new ServletHolder(graphlette), API_PATH);

        server.start();
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void testSingltonQueries() throws IOException, InterruptedException {
        String graphqlQuery = "{\"query\": \"{ testObject(id: \\\"testobj1\\\") { id title content tags } }\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(graphqlQuery))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
        assertNotNull(jsonResponse.get("data"));

        JsonObject data = jsonResponse.getAsJsonObject("data");
        JsonObject testObject = data.getAsJsonObject("testObject");

        assertEquals("Test Object 1", testObject.get("title").getAsString());
        assertEquals("This is test content", testObject.get("content").getAsString());
    }

    @Test
    void testVectorQueries() throws IOException, InterruptedException {
        String graphqlQuery = "{\"query\": \"{ getByTitle(title: \\\"Test Object 1\\\") { id title } }\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(graphqlQuery))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());

        JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
        assertNotNull(jsonResponse.get("data"));

        JsonObject data = jsonResponse.getAsJsonObject("data");
        assertNotNull(data.getAsJsonArray("getByTitle"));
    }

    @Test
    void testInvalidQuery() throws IOException, InterruptedException {
        String invalidQuery = "{\"query\": \"{ nonExistentField { id } }\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(invalidQuery))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode()); // GraphQL returns 200 even for errors

        JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
        assertNotNull(jsonResponse.get("errors"));
    }
}
