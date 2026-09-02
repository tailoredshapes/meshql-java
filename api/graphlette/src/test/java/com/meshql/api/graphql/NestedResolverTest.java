package com.meshql.api.graphql;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Searcher;
import com.meshql.core.config.QueryConfig;
import com.meshql.core.config.RootConfig;
import com.meshql.core.config.SingletonResolverConfig;
import com.meshql.core.config.VectorResolverConfig;
import com.tailoredshapes.stash.Stash;
import graphql.schema.DataFetcher;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.tailoredshapes.stash.Stash.stash;
import static com.tailoredshapes.underbar.ocho.Die.rethrow;
import static com.tailoredshapes.underbar.ocho.UnderBar.list;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for nested resolvers in Graphlette.
 * Tests that VectorResolver and SingletonResolver work correctly in a real GraphQL query.
 */
class NestedResolverTest {
    private static Server authorServer;
    private static Server bookServer;
    private static final int AUTHOR_PORT = 4570;
    private static final int BOOK_PORT = 4571;
    private static HttpClient httpClient;

    private static final String AUTHOR_SCHEMA = """
            type Query {
                getAuthor(id: ID!, at: Float): Author
            }

            type Author {
                id: ID!
                name: String!
                books: [Book]
            }

            type Book {
                id: ID!
                title: String!
            }
            """;

    private static final String BOOK_SCHEMA = """
            type Query {
                getBook(id: ID!, at: Float): Book
                getBooksByAuthor(id: ID!, at: Float): [Book]
            }

            type Book {
                id: ID!
                title: String!
                author: Author
            }

            type Author {
                id: ID!
                name: String!
            }
            """;

    @BeforeAll
    static void setUp() throws Exception {
        httpClient = HttpClient.newBuilder().build();

        // Create mock searchers that return test data
        Searcher authorSearcher = createAuthorSearcher();
        Searcher bookSearcher = createBookSearcher();

        // Setup Author service with VectorResolver for books
        Auth auth = new NoAuth();

        VectorResolverConfig booksResolver = VectorResolverConfig.builder()
                .name("books")
                .id("id")
                .queryName("getBooksByAuthor")
                .url("http://localhost:" + BOOK_PORT + "/graphql")
                .build();

        DTOFactory authorDtoFactory = new DTOFactory(list(), list(booksResolver), list(), list(), stash());

        RootConfig authorRootConfig = RootConfig.builder()
                .singleton("getAuthor", "{\"id\": \"{{id}}\"}")
                .build();

        Map<String, DataFetcher> authorFetchers = Root.create(authorSearcher, authorDtoFactory, auth, authorRootConfig);
        Graphlette authorGraphlette = new Graphlette(authorFetchers, AUTHOR_SCHEMA);

        authorServer = new Server();
        ServerConnector authorConnector = new ServerConnector(authorServer);
        authorConnector.setPort(AUTHOR_PORT);
        authorServer.addConnector(authorConnector);
        ServletContextHandler authorContext = new ServletContextHandler(ServletContextHandler.SESSIONS);
        authorContext.setContextPath("/");
        authorServer.setHandler(authorContext);
        authorContext.addServlet(new ServletHolder(authorGraphlette), "/graphql");
        authorServer.start();

        // Setup Book service with SingletonResolver for author
        SingletonResolverConfig authorResolver = SingletonResolverConfig.builder()
                .name("author")
                .id("authorId")
                .queryName("getAuthor")
                .url("http://localhost:" + AUTHOR_PORT + "/graphql")
                .build();

        DTOFactory bookDtoFactory = new DTOFactory(list(authorResolver), list(), list(), list(), stash());

        RootConfig bookRootConfig = RootConfig.builder()
                .singleton("getBook", "{\"id\": \"{{id}}\"}")
                .vector("getBooksByAuthor", "{\"authorId\": \"{{authorId}}\"}")
                .build();

        Map<String, DataFetcher> bookFetchers = Root.create(bookSearcher, bookDtoFactory, auth, bookRootConfig);
        Graphlette bookGraphlette = new Graphlette(bookFetchers, BOOK_SCHEMA);

        bookServer = new Server();
        ServerConnector bookConnector = new ServerConnector(bookServer);
        bookConnector.setPort(BOOK_PORT);
        bookServer.addConnector(bookConnector);
        ServletContextHandler bookContext = new ServletContextHandler(ServletContextHandler.SESSIONS);
        bookContext.setContextPath("/");
        bookServer.setHandler(bookContext);
        bookContext.addServlet(new ServletHolder(bookGraphlette), "/graphql");
        bookServer.start();

        // Give servers time to start
        Thread.sleep(500);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (authorServer != null) authorServer.stop();
        if (bookServer != null) bookServer.stop();
    }

    @Test
    void testVectorResolverReturnsNestedData() throws Exception {
        // Query author with nested books (VectorResolver)
        String query = """
                {
                  "query": "{ getAuthor(id: \\"author1\\") { id name books { id title } } }"
                }
                """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + AUTHOR_PORT + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Response status: " + response.statusCode());
        System.out.println("Response body: " + response.body());

        assertEquals(200, response.statusCode());

        JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
        assertNotNull(jsonResponse.get("data"), "Response should have data");

        JsonObject data = jsonResponse.getAsJsonObject("data");
        JsonObject author = data.getAsJsonObject("getAuthor");

        assertNotNull(author, "Author should not be null");
        assertEquals("author1", author.get("id").getAsString());
        assertEquals("Jane Doe", author.get("name").getAsString());

        // Check nested books (VectorResolver)
        assertNotNull(author.get("books"), "Books field should not be null");
        assertTrue(author.get("books").isJsonArray(), "Books should be an array");
        assertEquals(2, author.getAsJsonArray("books").size(), "Should have 2 books");

        JsonObject firstBook = author.getAsJsonArray("books").get(0).getAsJsonObject();
        assertEquals("book1", firstBook.get("id").getAsString());
        assertEquals("GraphQL Basics", firstBook.get("title").getAsString());
    }

    @Test
    void testSingletonResolverReturnsNestedData() throws Exception {
        // Query book with nested author (SingletonResolver)
        String query = """
                {
                  "query": "{ getBook(id: \\"book1\\") { id title author { id name } } }"
                }
                """;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + BOOK_PORT + "/graphql"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(query))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.println("Response status: " + response.statusCode());
        System.out.println("Response body: " + response.body());

        assertEquals(200, response.statusCode());

        JsonObject jsonResponse = JsonParser.parseString(response.body()).getAsJsonObject();
        assertNotNull(jsonResponse.get("data"), "Response should have data");

        JsonObject data = jsonResponse.getAsJsonObject("data");
        JsonObject book = data.getAsJsonObject("getBook");

        assertNotNull(book, "Book should not be null");
        assertEquals("book1", book.get("id").getAsString());
        assertEquals("GraphQL Basics", book.get("title").getAsString());

        // Check nested author (SingletonResolver)
        assertNotNull(book.get("author"), "Author field should not be null");
        assertTrue(book.get("author").isJsonObject(), "Author should be an object");

        JsonObject author = book.getAsJsonObject("author");
        assertEquals("author1", author.get("id").getAsString());
        assertEquals("Jane Doe", author.get("name").getAsString());
    }

    // Mock searchers that return test data
    private static Searcher createAuthorSearcher() {
        return new Searcher() {
            @Override
            public Stash find(com.github.jknack.handlebars.Template queryTemplate, Stash args, com.meshql.core.AuthSession session, long timestamp) {
                String id = (String) args.get("id");
                if ("author1".equals(id)) {
                    return stash(
                            "id", "author1",
                            "name", "Jane Doe"
                    );
                }
                return new Stash();
            }

            @Override
            public List<Stash> findAll(com.github.jknack.handlebars.Template queryTemplate, Stash args, com.meshql.core.AuthSession session, long timestamp) {
                return Collections.emptyList();
            }
        };
    }

    private static Searcher createBookSearcher() {
        return new Searcher() {
            @Override
            public Stash find(com.github.jknack.handlebars.Template queryTemplate, Stash args, com.meshql.core.AuthSession session, long timestamp) {
                String id = (String) args.get("id");
                if ("book1".equals(id)) {
                    return stash(
                            "id", "book1",
                            "title", "GraphQL Basics",
                            "authorId", "author1"
                    );
                } else if ("book2".equals(id)) {
                    return stash(
                            "id", "book2",
                            "title", "Advanced GraphQL",
                            "authorId", "author1"
                    );
                }
                return new Stash();
            }

            @Override
            public List<Stash> findAll(com.github.jknack.handlebars.Template queryTemplate, Stash args, com.meshql.core.AuthSession session, long timestamp) {
                // Note: The query parameter is always "id" by convention
                String authorId = (String) args.get("id");
                if ("author1".equals(authorId)) {
                    return list(
                            stash("id", "book1", "title", "GraphQL Basics", "authorId", "author1"),
                            stash("id", "book2", "title", "Advanced GraphQL", "authorId", "author1")
                    );
                }
                return Collections.emptyList();
            }
        };
    }
}
