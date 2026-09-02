package com.meshql.repositories.postgres;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

import static com.tailoredshapes.stash.Stash.stash;
import static com.tailoredshapes.underbar.ocho.Die.rethrow;
import static com.tailoredshapes.underbar.ocho.UnderBar.list;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The PostgreSQL half of the apostrophe question. See
 * {@code com.meshql.repos.sqlite.ApostropheInQueryTemplateTest} for the whole
 * story; the short version is that query templates used to be rendered with
 * Handlebars' default HTML escaping, so a search for {@code O'Brien} generated
 * {@code = 'O&#x27;Brien'} and matched nothing.
 *
 * <p>PostgreSQL is worth checking separately because doubling a quote is only
 * the right escape while {@code standard_conforming_strings} is on - which it
 * has been by default since 9.1, and is here.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ApostropheInQueryTemplateTest {

    @Container
    private static final PostgreSQLContainer postgreSQLContainer = new PostgreSQLContainer("postgres:latest")
            .withUsername("alice")
            .withPassword("face")
            .withDatabaseName("repository");

    private static int tableCounter = 0;

    private PostgresRepository repository;
    private PostgresSearcher searcher;

    @BeforeAll
    public static void startContainer() {
        postgreSQLContainer.start();
    }

    @AfterAll
    public static void stopContainer() {
        postgreSQLContainer.stop();
    }

    @BeforeEach
    public void init() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerName(postgreSQLContainer.getHost());
        dataSource.setPortNumber(postgreSQLContainer.getMappedPort(5432));
        dataSource.setDatabaseName(postgreSQLContainer.getDatabaseName());
        dataSource.setUser(postgreSQLContainer.getUsername());
        dataSource.setPassword(postgreSQLContainer.getPassword());

        String tableName = "apostrophe_test" + (++tableCounter);

        repository = new PostgresRepository(dataSource, tableName);
        repository.initialize();

        Auth noAuth = new NoAuth();
        searcher = new PostgresSearcher(dataSource, tableName);
    }

    private Template findByName() {
        return searcher.compileQuery("payload->>'name' = '{{name}}'");
    }

    /** The caller these scenarios run as: an ordinary token holder. */
    private static final com.meshql.core.AuthSession SESSION =
            new com.meshql.auth.token.TokenSession(list("TOKEN"));

    @Test
    public void templateEscapesApostropheForSqlNotForHtml() {
        String rendered = rethrow(() -> findByName().apply(stash("name", "O'Brien")));

        System.out.println("[apostrophe-pg] rendered filter: " + rendered);

        assertFalse(rendered.contains("&#x27;"),
                "Handlebars HTML-escaped the apostrophe; rendered was: " + rendered);
        assertEquals("payload->>'name' = 'O''Brien'", rendered);
    }

    @Test
    public void searcherFindsAValueContainingAnApostrophe() {
        repository.create(
                new Envelope(null, stash("name", "O'Brien", "type", "person"), null, false, null),
                SESSION);

        Stash found = searcher.find(findByName(), stash("name", "O'Brien"), SESSION,
                Instant.now().toEpochMilli());

        System.out.println("[apostrophe-pg] singleton result: " + found);

        assertFalse(found.isEmpty(),
                "Searching for O'Brien returned nothing - the value was mangled on the way into SQL");
        assertEquals("O'Brien", found.get("name"));
    }

    @Test
    public void searcherFindsAllValuesContainingAnApostrophe() {
        repository.create(new Envelope(null, stash("name", "O'Brien", "type", "irish'sur"), null, false, null),
                SESSION);
        repository.create(new Envelope(null, stash("name", "O'Connor", "type", "irish'sur"), null, false, null),
                SESSION);
        repository.create(new Envelope(null, stash("name", "Smith", "type", "plain"), null, false, null),
                SESSION);

        Template findAllByType = searcher.compileQuery("payload->>'type' = '{{type}}'");

        List<Stash> found = searcher.findAll(findAllByType, stash("type", "irish'sur"), SESSION,
                Instant.now().toEpochMilli());

        System.out.println("[apostrophe-pg] vector result size: " + found.size() + " -> " + found);

        assertEquals(2, found.size(), "Expected both apostrophe-typed records back; got " + found);
    }

    @Test
    public void searcherFindsValuesContainingAmpersandsAndAngleBrackets() {
        List<String> names = List.of("Tom & Jerry", "a<b", "a>b", "say \"hi\"", "a\\b");
        for (String name : names) {
            repository.create(new Envelope(null, stash("name", name), null, false, null), SESSION);
        }

        for (String name : names) {
            Stash found = searcher.find(findByName(), stash("name", name), SESSION,
                    Instant.now().toEpochMilli());
            System.out.println("[apostrophe-pg] lookup " + name + " -> " + found);
            assertFalse(found.isEmpty(), "Searching for '" + name + "' returned nothing");
            assertEquals(name, found.get("name"));
        }
    }

    /**
     * Letting the apostrophe through must not let a value end the literal.
     */
    @Test
    public void aQuoteInAValueStillCannotEndTheLiteral() {
        repository.create(new Envelope(null, stash("name", "Smith"), null, false, null), SESSION);

        String breakout = "' OR 1=1 --";

        String rendered = rethrow(() -> findByName().apply(stash("name", breakout)));
        System.out.println("[apostrophe-pg] breakout attempt renders as: " + rendered);
        assertEquals("payload->>'name' = ''' OR 1=1 --'", rendered);

        Stash found = searcher.find(findByName(), stash("name", breakout), SESSION,
                Instant.now().toEpochMilli());

        System.out.println("[apostrophe-pg] breakout attempt result: " + found);
        assertTrue(found.isEmpty(),
                "A quote in the value ended the SQL literal and matched a record it should not have: " + found);
    }

    /**
     * Doubling a quote is only correct while {@code standard_conforming_strings}
     * is on. If a deployment turns it off, backslashes become escapes and this
     * strategy is no longer sufficient - so pin the assumption down.
     */
    @Test
    public void standardConformingStringsIsOn() throws Exception {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setServerName(postgreSQLContainer.getHost());
        dataSource.setPortNumber(postgreSQLContainer.getMappedPort(5432));
        dataSource.setDatabaseName(postgreSQLContainer.getDatabaseName());
        dataSource.setUser(postgreSQLContainer.getUsername());
        dataSource.setPassword(postgreSQLContainer.getPassword());

        try (var conn = dataSource.getConnection();
                var stmt = conn.createStatement();
                var rs = stmt.executeQuery("SHOW standard_conforming_strings")) {
            assertTrue(rs.next());
            String value = rs.getString(1);
            System.out.println("[apostrophe-pg] standard_conforming_strings = " + value);
            assertEquals("on", value);
        }
    }

    /**
     * Handlebars' bare default is still HTML escaping - which is why
     * {@code RDBMSSearcher.compileQuery} overrides it.
     */
    @Test
    public void handlebarsDefaultWouldStillHtmlEscape() {
        Template def = rethrow(() -> new Handlebars().compileInline("{{name}}"));
        assertEquals("O&#x27;Brien", rethrow(() -> def.apply(stash("name", "O'Brien"))));
    }
}
