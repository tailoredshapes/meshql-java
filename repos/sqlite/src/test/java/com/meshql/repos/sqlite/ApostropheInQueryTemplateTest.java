package com.meshql.repos.sqlite;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.tailoredshapes.stash.Stash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteDataSource;

import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;

import static com.tailoredshapes.stash.Stash.stash;
import static com.tailoredshapes.underbar.ocho.Die.rethrow;
import static com.tailoredshapes.underbar.ocho.UnderBar.list;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Values that contain characters Handlebars would escape if query templates were
 * rendered with the default HTML escaping strategy.
 *
 * <p>A query template such as {@code json_extract(payload, '$.name') = '{{name}}'}
 * renders a user-supplied value into SQL. Under
 * {@code EscapingStrategy.HTML_ENTITY} - Handlebars' default - an apostrophe
 * became {@code &#x27;} and an ampersand became {@code &amp;}, so a search for
 * {@code O'Brien} queried for {@code O&#x27;Brien} and silently returned
 * nothing. Every one of these tests failed before
 * {@code RDBMSSearcher.compileQuery} started escaping for SQL string literals
 * instead of for HTML.
 *
 * <p>The last test is the other half of the bargain: the value must still be
 * unable to end the literal it sits inside.
 */
public class ApostropheInQueryTemplateTest {

    private SQLiteRepository repository;
    private SQLiteSearcher searcher;

    @BeforeEach
    public void init() throws Exception {
        File dbFile = Files.createTempFile("meshql_apostrophe_test_", ".db").toFile();
        dbFile.deleteOnExit();

        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());

        repository = new SQLiteRepository(dataSource.getConnection(), "apostrophe_test");
        repository.initialize();

        Auth noAuth = new NoAuth();
        searcher = new SQLiteSearcher(dataSource, "apostrophe_test");
    }

    private Template findByName() {
        return searcher.compileQuery("json_extract(payload, '$.name') = '{{name}}'");
    }

    /**
     * What the searcher actually renders. Documented so nobody has to guess again.
     */
    /** The caller these scenarios run as: an ordinary token holder. */
    private static final com.meshql.core.AuthSession SESSION =
            new com.meshql.auth.token.TokenSession(list("TOKEN"));

    @Test
    public void templateEscapesApostropheForSqlNotForHtml() {
        String rendered = rethrow(() -> findByName().apply(stash("name", "O'Brien")));

        System.out.println("[apostrophe] rendered filter: " + rendered);

        assertFalse(rendered.contains("&#x27;"),
                "Handlebars HTML-escaped the apostrophe; rendered was: " + rendered);
        assertEquals("json_extract(payload, '$.name') = 'O''Brien'", rendered);
    }

    /**
     * The other characters HTML_ENTITY would mangle. None of them mean anything
     * inside a single-quoted SQL literal, so none of them should be touched.
     */
    @Test
    public void templateLeavesOtherHtmlSignificantCharactersAlone() {
        Template t = searcher.compileQuery("{{value}}");

        assertEquals("a&b", rethrow(() -> t.apply(stash("value", "a&b"))));
        assertEquals("a<b", rethrow(() -> t.apply(stash("value", "a<b"))));
        assertEquals("a>b", rethrow(() -> t.apply(stash("value", "a>b"))));
        assertEquals("a\"b", rethrow(() -> t.apply(stash("value", "a\"b"))));
        assertEquals("a`b", rethrow(() -> t.apply(stash("value", "a`b"))));
        assertEquals("a=b", rethrow(() -> t.apply(stash("value", "a=b"))));
        assertEquals("a\\b", rethrow(() -> t.apply(stash("value", "a\\b"))));
    }

    /**
     * End to end: store O'Brien, then find him through a query template.
     */
    @Test
    public void searcherFindsAValueContainingAnApostrophe() {
        repository.create(
                new Envelope(null, stash("name", "O'Brien", "type", "person"), null, false, null),
                SESSION);

        Stash found = searcher.find(findByName(), stash("name", "O'Brien"), SESSION,
                Instant.now().toEpochMilli());

        System.out.println("[apostrophe] singleton result: " + found);

        assertFalse(found.isEmpty(),
                "Searching for O'Brien returned nothing - the value was mangled on the way into SQL");
        assertEquals("O'Brien", found.get("name"));
    }

    /**
     * Same thing through findAll, which goes via the vector template.
     */
    @Test
    public void searcherFindsAllValuesContainingAnApostrophe() {
        repository.create(new Envelope(null, stash("name", "O'Brien", "type", "irish'sur"), null, false, null),
                SESSION);
        repository.create(new Envelope(null, stash("name", "O'Connor", "type", "irish'sur"), null, false, null),
                SESSION);
        repository.create(new Envelope(null, stash("name", "Smith", "type", "plain"), null, false, null),
                SESSION);

        Template findAllByType = searcher.compileQuery("json_extract(payload, '$.type') = '{{type}}'");

        List<Stash> found = searcher.findAll(findAllByType, stash("type", "irish'sur"), SESSION,
                Instant.now().toEpochMilli());

        System.out.println("[apostrophe] vector result size: " + found.size() + " -> " + found);

        assertEquals(2, found.size(), "Expected both apostrophe-typed records back; got " + found);
    }

    /**
     * Ampersands and angle brackets, end to end.
     */
    @Test
    public void searcherFindsValuesContainingAmpersandsAndAngleBrackets() {
        for (String name : List.of("Tom & Jerry", "a<b", "a>b", "say \"hi\"")) {
            repository.create(new Envelope(null, stash("name", name), null, false, null), SESSION);
        }

        for (String name : List.of("Tom & Jerry", "a<b", "a>b", "say \"hi\"")) {
            Stash found = searcher.find(findByName(), stash("name", name), SESSION,
                    Instant.now().toEpochMilli());
            System.out.println("[apostrophe] lookup " + name + " -> " + found);
            assertFalse(found.isEmpty(), "Searching for '" + name + "' returned nothing");
            assertEquals(name, found.get("name"));
        }
    }

    /**
     * The half of this that must not regress: letting the apostrophe through
     * must not let a value end the literal it sits inside.
     *
     * <p>{@code ' OR 1=1 --} is treated as a name to look for, not as SQL. It
     * matches nothing, and the record that is there is not returned.
     */
    @Test
    public void aQuoteInAValueStillCannotEndTheLiteral() {
        repository.create(new Envelope(null, stash("name", "Smith"), null, false, null), SESSION);

        String breakout = "' OR 1=1 --";

        String rendered = rethrow(() -> findByName().apply(stash("name", breakout)));
        System.out.println("[apostrophe] breakout attempt renders as: " + rendered);
        assertEquals("json_extract(payload, '$.name') = ''' OR 1=1 --'", rendered);

        Stash found = searcher.find(findByName(), stash("name", breakout), SESSION,
                Instant.now().toEpochMilli());

        System.out.println("[apostrophe] breakout attempt result: " + found);
        assertTrue(found.isEmpty(),
                "A quote in the value ended the SQL literal and matched a record it should not have: " + found);
    }

    /**
     * And a value that is exactly a quote is stored and found like any other.
     */
    @Test
    public void searcherRoundTripsAValueThatIsJustAQuote() {
        repository.create(new Envelope(null, stash("name", "'"), null, false, null), SESSION);

        Stash found = searcher.find(findByName(), stash("name", "'"), SESSION,
                Instant.now().toEpochMilli());

        System.out.println("[apostrophe] lone-quote result: " + found);
        assertEquals("'", found.get("name"));
    }

    /**
     * Handlebars' bare default is still HTML escaping. This is here so that the
     * reason the override in {@code RDBMSSearcher} exists stays visible: delete
     * the override and the searcher goes back to this.
     */
    @Test
    public void handlebarsDefaultWouldStillHtmlEscape() {
        Template def = rethrow(() -> new Handlebars().compileInline("{{name}}"));
        assertEquals("O&#x27;Brien", rethrow(() -> def.apply(stash("name", "O'Brien"))));
    }
}
