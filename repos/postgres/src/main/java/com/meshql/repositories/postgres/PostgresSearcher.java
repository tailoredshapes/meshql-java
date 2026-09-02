package com.meshql.repositories.postgres;

import com.github.jknack.handlebars.Handlebars;
import com.meshql.repositories.rdbms.RDBMSSearcher;

import javax.sql.DataSource;

import java.sql.Timestamp;

import static com.tailoredshapes.underbar.ocho.Die.rethrow;

public class PostgresSearcher extends RDBMSSearcher {
    private  static Handlebars handlebars = new Handlebars();
    private static final String SINGLETON_QUERY_TEMPLATE = "SELECT e.* " +
            "FROM {{_name}} e " +
            "WHERE {{{filters}}} " +
            "  AND e.created_at <= '{{_createdAt}}' " +
            "  AND e.deleted = false " +
            "ORDER BY e.created_at DESC";

    /**
     * SQL template for finding multiple records.
     */
    private static final String VECTOR_QUERY_TEMPLATE = "SELECT DISTINCT ON (e.id) e.* FROM {{_name}} e " +
            "WHERE {{{filters}}}" +
            "  AND e.created_at <= '{{_createdAt}}' " +
            "  AND e.deleted = false " +
            "ORDER BY e.id, e.created_at DESC";

    public PostgresSearcher(DataSource dataSource, String tableName) {
        // Add 1ms buffer to account for microsecond precision in PostgreSQL timestamps
        // vs millisecond precision in System.currentTimeMillis()
        super(rethrow(() -> handlebars.compileInline(SINGLETON_QUERY_TEMPLATE)), rethrow(() -> handlebars.compileInline(VECTOR_QUERY_TEMPLATE)), dataSource, tableName, (t) -> new Timestamp(t + 1).toInstant());
    }
}
