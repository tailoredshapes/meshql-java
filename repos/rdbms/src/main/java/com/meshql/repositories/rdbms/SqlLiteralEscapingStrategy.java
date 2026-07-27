package com.meshql.repositories.rdbms;

import com.github.jknack.handlebars.EscapingStrategy;

/**
 * Escapes a value for interpolation inside a single-quoted SQL string literal.
 *
 * <p>Query templates are SQL fragments written by administrators, e.g.
 * <pre>json_extract(payload, '$.name') = '{{name}}'</pre>
 * The quotes belong to the template; the value belongs to the user. The only
 * character that can end the literal early is the single quote, and ANSI SQL
 * says the way to put one inside a literal is to double it. So
 * {@code O'Brien} renders as {@code O''Brien}, which SQLite and PostgreSQL both
 * read back as {@code O'Brien}: the value survives and the quote can never
 * close the literal.
 *
 * <p>This replaces Handlebars' default {@link EscapingStrategy#HTML_ENTITY},
 * which turned {@code '} into {@code &amp;#x27;} and {@code &amp;} into
 * {@code &amp;amp;}. That also made breakout impossible, but by corrupting the
 * value: a search for {@code O'Brien} queried for {@code O&amp;#x27;Brien} and
 * matched nothing. HTML entities are the wrong alphabet for SQL. Nothing else -
 * {@code &amp;}, {@code <}, {@code >}, {@code "}, backtick - has any meaning
 * inside a single-quoted literal, so it is passed through untouched.
 *
 * <p><b>Dialect note:</b> this is the ANSI rule and is correct for SQLite and
 * for PostgreSQL with {@code standard_conforming_strings} on (the default since
 * 9.1). A dialect that treats backslash as an escape character inside string
 * literals - MySQL, unless {@code NO_BACKSLASH_ESCAPES} is set - needs a
 * strategy that doubles backslashes too. Doubling them here would corrupt
 * values on SQLite and PostgreSQL, where a backslash is just a backslash, so
 * such a dialect must supply its own strategy rather than widen this one.
 */
public class SqlLiteralEscapingStrategy implements EscapingStrategy {

    public static final SqlLiteralEscapingStrategy INSTANCE = new SqlLiteralEscapingStrategy();

    @Override
    public CharSequence escape(CharSequence value) {
        if (value == null) {
            return null;
        }

        String s = value.toString();
        if (s.indexOf('\'') < 0) {
            return s;
        }

        return s.replace("'", "''");
    }
}
