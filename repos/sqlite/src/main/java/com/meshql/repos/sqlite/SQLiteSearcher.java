package com.meshql.repos.sqlite;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Template;
import com.meshql.repositories.rdbms.RDBMSSearcher;

import javax.sql.DataSource;

import static com.tailoredshapes.underbar.ocho.Die.rethrow;

public class SQLiteSearcher extends RDBMSSearcher {
    private static Handlebars h = new Handlebars();

    private static final Template SINGLETON_QUERY_TEMPLATE = rethrow(() -> h.compileInline("SELECT *\n" +
            "        FROM {{_name}}\n" +
            "        WHERE {{{filters}}}\n" +
            "         AND created_at <= {{_createdAt}}\n" +
            "         AND deleted = 0\n" +
            "        ORDER BY created_at DESC"));

    /**
     * SQL template for finding multiple records.
     */
    private static final Template VECTOR_QUERY_TEMPLATE = rethrow(() -> h.compileInline("WITH latest AS (\n" +
            "        SELECT\n" +
            "            id,\n" +
            "            MAX(created_at) AS max_created_at\n" +
            "        FROM {{_name}}\n" +
            "        WHERE {{{filters}}}\n" +
            "            AND created_at <= {{_createdAt}}\n" +
            "            AND deleted = 0\n" +
            "        GROUP BY id\n" +
            "        )\n" +
            "        SELECT t1.*\n" +
            "        FROM {{_name}} t1\n" +
            "        JOIN latest t2\n" +
            "        ON t1.id = t2.id\n" +
            "        AND t1.created_at = t2.max_created_at\n" +
            "        WHERE t1.deleted = 0"));

    public SQLiteSearcher(DataSource dataSource, String tableName) {
        // SQLite uses INTEGER for timestamps (milliseconds), so no precision adjustment needed
        super(SINGLETON_QUERY_TEMPLATE, VECTOR_QUERY_TEMPLATE, dataSource, tableName, (t) -> t);
    }
}
