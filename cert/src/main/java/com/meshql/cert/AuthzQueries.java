package com.meshql.cert;

/**
 * Database-specific query templates for the authorization certification.
 *
 * <p>Deliberately minimal: one singleton lookup by id and one <em>vector</em> lookup
 * by workspace. The vector query is the one that matters — a singleton query passes
 * even when the authorization filter is completely inert, because the caller only
 * ever asks for a record they already know the id of. A list query that spans two
 * callers' records is the only shape that can tell a working filter from a dead one.
 */
public class AuthzQueries {
    public final String docById;
    public final String docByWorkspace;

    public AuthzQueries(String docById, String docByWorkspace) {
        this.docById = docById;
        this.docByWorkspace = docByWorkspace;
    }

    public static AuthzQueries sqliteQueries() {
        return new AuthzQueries(
                "id = '{{id}}'",
                "json_extract(payload, '$.workspace') = '{{workspace}}'"
        );
    }

    public static AuthzQueries postgresQueries() {
        return new AuthzQueries(
                "id = '{{id}}'",
                "payload->>'workspace' = '{{workspace}}'"
        );
    }

    public static AuthzQueries mysqlQueries() {
        return new AuthzQueries(
                "id = '{{id}}'",
                "JSON_EXTRACT(payload, '$.workspace') = '{{workspace}}'"
        );
    }

    public static AuthzQueries mongoQueries() {
        return new AuthzQueries(
                "{\"id\": \"{{id}}\"}",
                "{\"payload.workspace\": \"{{workspace}}\"}"
        );
    }

    /** The in-memory searcher understands a simple {@code field = 'value'} grammar. */
    public static AuthzQueries memoryQueries() {
        return new AuthzQueries(
                "id = '{{id}}'",
                "workspace = '{{workspace}}'"
        );
    }
}
