package com.meshql.api.graphql;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.meshql.auth.casbin.CasbinAuth;
import com.meshql.auth.jwt.JWTSubAuthorizer;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.config.RootConfig;
import com.meshql.repositories.memory.InMemoryRepository;
import com.meshql.repositories.memory.InMemorySearcher;
import com.meshql.repositories.memory.InMemoryStore;
import com.tailoredshapes.stash.Stash;
import graphql.schema.DataFetcher;
import org.casbin.jcasbin.main.EnforceResult;
import org.casbin.jcasbin.main.Enforcer;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.tailoredshapes.stash.Stash.stash;
import static com.tailoredshapes.underbar.ocho.UnderBar.list;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MeshQL resolves identity to capabilities <em>once</em>, at the boundary, and
 * then carries the answer down. {@link CasbinAuth} asks Casbin for a subject's
 * roles and stops there; it never calls {@code enforcer.enforce(...)}, and every
 * check below it is a local intersection of that token set against a record's
 * {@code authorized_tokens}.
 *
 * <p>That absence is a design decision, not an oversight, and it does not look
 * like one. A scanner sees an authorization library whose decision method is
 * never called and reports a finding; a reviewer sees the same thing and
 * "fixes" it. The cost of the fix is invisible until production: a deeper
 * {@code enforce} is not defence in depth, it is the same decision recomputed
 * per layer, and against a remote policy engine it is a network call per layer.
 * The meshlettes get chatty and nobody remembers why.
 *
 * <p>So the decision is executable. The enforcer here counts. A read through the
 * graphlette must produce <b>zero</b> policy evaluations - while still producing
 * the right, filtered answer, which is what stops this from being a test that
 * passes because nothing happened.
 *
 * @see CasbinAuth
 */
class PolicyEvaluationCountTest {

    private static final int PORT = 4573;
    private static final String API_PATH = "/counted/graphql";

    private static final String SCHEMA = """
            type Query {
                getById(id: ID!): Doc
                getByType(type: String!): [Doc]
            }

            type Doc {
                id: ID!
                name: String!
                type: String
            }
            """;

    /**
     * A Casbin RBAC model. The matcher exists to make the file well-formed - if
     * MeshQL is behaving, nothing ever evaluates it.
     */
    private static final String MODEL = """
            [request_definition]
            r = sub, obj, act

            [policy_definition]
            p = sub, obj, act

            [role_definition]
            g = _, _

            [policy_effect]
            e = some(where (p.eft == allow))

            [matchers]
            m = g(r.sub, p.sub) && r.obj == p.obj && r.act == p.act
            """;

    /** alice -> [tenant-a], bob -> [tenant-b]. */
    private static final String POLICY = """
            p, tenant-a, data, read
            p, tenant-b, data, read
            g, alice, tenant-a
            g, bob, tenant-b
            """;

    private static Server server;
    private static HttpClient httpClient;
    private static String baseUrl;
    private static CountingEnforcer enforcer;

    /**
     * An {@link Enforcer} that records how often anybody asks it to evaluate a
     * policy, and how often anybody asks it to resolve a subject's roles.
     *
     * <p>Both counters matter. Zero evaluations is only meaningful alongside a
     * non-zero number of role lookups: it has to be zero because the read path
     * does not need policy evaluation, not because the read path never reached
     * Casbin at all.
     */
    static class CountingEnforcer extends Enforcer {
        final AtomicInteger evaluations = new AtomicInteger();
        final AtomicInteger roleLookups = new AtomicInteger();

        CountingEnforcer(String modelPath, String policyPath) {
            super(modelPath, policyPath);
        }

        void reset() {
            evaluations.set(0);
            roleLookups.set(0);
        }

        @Override
        public boolean enforce(Object... rvals) {
            evaluations.incrementAndGet();
            return super.enforce(rvals);
        }

        @Override
        public boolean enforceWithMatcher(String matcher, Object... rvals) {
            evaluations.incrementAndGet();
            return super.enforceWithMatcher(matcher, rvals);
        }

        @Override
        public EnforceResult enforceEx(Object... rvals) {
            evaluations.incrementAndGet();
            return super.enforceEx(rvals);
        }

        @Override
        public EnforceResult enforceExWithMatcher(String matcher, Object... rvals) {
            evaluations.incrementAndGet();
            return super.enforceExWithMatcher(matcher, rvals);
        }

        @Override
        public List<Boolean> batchEnforce(List<List<String>> rules) {
            evaluations.addAndGet(rules == null ? 1 : rules.size());
            return super.batchEnforce(rules);
        }

        @Override
        public List<Boolean> batchEnforceWithMatcher(String matcher, List<List<String>> rules) {
            evaluations.addAndGet(rules == null ? 1 : rules.size());
            return super.batchEnforceWithMatcher(matcher, rules);
        }

        @Override
        public List<String> getRolesForUser(String name) {
            roleLookups.incrementAndGet();
            return super.getRolesForUser(name);
        }
    }

    @BeforeAll
    static void setUp() throws Exception {
        baseUrl = "http://localhost:" + PORT + API_PATH;
        httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();

        Path modelFile = Files.createTempFile("policy_count_model", ".conf");
        Path policyFile = Files.createTempFile("policy_count_policy", ".csv");
        modelFile.toFile().deleteOnExit();
        policyFile.toFile().deleteOnExit();
        Files.writeString(modelFile, MODEL);
        Files.writeString(policyFile, POLICY);

        enforcer = new CountingEnforcer(modelFile.toString(), policyFile.toString());
        Auth auth = new CasbinAuth(enforcer, new JWTSubAuthorizer());

        InMemoryStore store = new InMemoryStore();
        InMemoryRepository repository = new InMemoryRepository(store);

        repository.create(new Envelope("doc-a",
                stash("name", "Alice's document", "type", "memo"),
                null, false, null), list("tenant-a"));
        repository.create(new Envelope("doc-b",
                stash("name", "Bob's document", "type", "memo"),
                null, false, null), list("tenant-b"));

        InMemorySearcher searcher = new InMemorySearcher(store, auth);

        RootConfig rootConfig = RootConfig.builder()
                .singleton("getById", "id = '{{id}}'")
                .vector("getByType", "type = '{{type}}'")
                .build();

        DTOFactory dtoFactory = new DTOFactory(list(), list(), list(), list(), stash());
        Map<String, DataFetcher> fetchers = Root.create(searcher, dtoFactory, auth, rootConfig);
        Graphlette graphlette = new Graphlette(fetchers, SCHEMA);

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

    @BeforeEach
    void resetCounters() {
        enforcer.reset();
    }

    /**
     * <b>If you are here because you added an {@code enforce} call and this went
     * red: that is what this test is for.</b>
     *
     * <p>Authorization in MeshQL is decided once, at the boundary, by resolving a
     * subject to a token set. Everything downstream intersects that set against
     * a record's {@code authorized_tokens} locally. Re-asking the policy engine
     * further down does not make the answer safer - it is the same answer - and
     * it makes every layer pay for it, over the network if the engine is remote.
     *
     * <p>If you genuinely need a policy evaluation on the read path, that is a
     * change to the architecture and it needs its own argument. Do not raise this
     * number to make a build go green.
     */
    @Test
    void readPathMakesNoPolicyEvaluations() throws IOException, InterruptedException {
        JsonObject data = query(
                "{ getById(id: \\\"doc-a\\\") { id name type } }",
                bearer("alice"));

        JsonObject doc = data.getAsJsonObject("getById");
        assertNotNull(doc, "The read returned nothing, so this test proves nothing");
        assertEquals("Alice's document", doc.get("name").getAsString());

        assertTrue(enforcer.roleLookups.get() > 0,
                "Casbin was never consulted at all, so a count of zero evaluations is vacuous");

        assertEquals(0, enforcer.evaluations.get(),
                "A read through the graphlette evaluated " + enforcer.evaluations.get()
                        + " Casbin policies. Identity is resolved to a token set once, at the "
                        + "boundary; every check below that is a local intersection. Re-evaluating "
                        + "the policy downstream recomputes a decision already made, and against a "
                        + "remote policy engine it is a network call per layer. See CasbinAuth.");
    }

    /**
     * The same for a vector read, which touches every record rather than one.
     * A per-record {@code enforce} is the version of this mistake that scales
     * worst, so count it separately.
     */
    @Test
    void vectorReadPathMakesNoPolicyEvaluations() throws IOException, InterruptedException {
        JsonObject data = query(
                "{ getByType(type: \\\"memo\\\") { id name } }",
                bearer("alice"));

        JsonArray docs = data.getAsJsonArray("getByType");
        assertNotNull(docs);
        assertEquals(1, docs.size(),
                "Expected alice to see only her own document; got " + docs);
        assertEquals("Alice's document",
                docs.get(0).getAsJsonObject().get("name").getAsString());

        assertTrue(enforcer.roleLookups.get() > 0,
                "Casbin was never consulted at all, so a count of zero evaluations is vacuous");

        assertEquals(0, enforcer.evaluations.get(),
                "A vector read evaluated " + enforcer.evaluations.get() + " Casbin policies - "
                        + "one per record is exactly the cost this design exists to avoid. "
                        + "See CasbinAuth.");
    }

    /**
     * And the filtering really is filtering: bob does not see alice's document.
     * Without this, "zero evaluations" would be equally true of a system that had
     * simply stopped authorizing.
     *
     * <p>The raw response is inspected rather than the {@code data} object because
     * a singleton miss resolves to an empty record, which the non-null fields in
     * the schema then turn into a GraphQL error. Either shape is a refusal; what
     * matters is that alice's document is nowhere in the response.
     */
    @Test
    void authorizationStillExcludesWithoutEvaluatingPolicy() throws IOException, InterruptedException {
        String body = post(
                "{ getById(id: \\\"doc-a\\\") { id name } }",
                bearer("bob"));

        assertFalse(body.contains("Alice"),
                "bob read alice's document; the token intersection is not being applied: " + body);

        assertEquals(0, enforcer.evaluations.get(),
                "Excluding a record cost " + enforcer.evaluations.get() + " policy evaluations. "
                        + "Exclusion is a set intersection on tokens already resolved, not a "
                        + "question for the policy engine. See CasbinAuth.");
    }

    /**
     * Identity is resolved once per request, not once per record. Two documents
     * come back through one request on a single role lookup.
     */
    @Test
    void identityIsResolvedOncePerRequestNotOncePerRecord() throws IOException, InterruptedException {
        JsonObject data = query(
                "{ getByType(type: \\\"memo\\\") { id name } }",
                bearer("bob"));

        assertNotNull(data.getAsJsonArray("getByType"));

        assertEquals(1, enforcer.roleLookups.get(),
                "Casbin resolved roles " + enforcer.roleLookups.get() + " times for one request. "
                        + "Identity resolves to capabilities once, at the boundary, and the result "
                        + "is propagated. See CasbinAuth.");
    }

    private static JsonObject query(String graphql, String authorization)
            throws IOException, InterruptedException {
        String body = post(graphql, authorization);

        JsonObject json = JsonParser.parseString(body).getAsJsonObject();
        assertTrue(json.get("errors") == null || json.get("errors").isJsonNull(),
                "GraphQL returned errors: " + json);

        return json.getAsJsonObject("data");
    }

    private static String post(String graphql, String authorization)
            throws IOException, InterruptedException {
        String body = "{\"query\": \"" + graphql + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());

        return response.body();
    }

    /**
     * An unsigned JWT. {@code JWTSubAuthorizer} decodes rather than verifies, by
     * design - the edge authenticates, MeshQL authorizes.
     */
    private static String bearer(String subject) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"sub\":\"" + subject + "\"}").getBytes(StandardCharsets.UTF_8));
        return "Bearer " + header + "." + payload + ".unsigned";
    }
}
