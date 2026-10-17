package com.meshql.cert.egg;

/**
 * The query templates of the store the egg economy runs on (SQLite; meshql is storage agnostic and the
 * storage certifications prove it per store, so the end-to-end runs once). The query templates of one store, written in its query language. The egg economy
 * needs three shapes: a document by id, every document, and the documents whose
 * payload field equals a template argument.
 */
public interface EggQueries {
    String byId();

    String all();

    /** Documents whose payload {@code field} equals the template argument {@code arg}. */
    String byField(String field, String arg);

    static EggQueries sqlite() {
        return new EggQueries() {
            public String byId() { return "id = '{{id}}'"; }
            public String all() { return "1 = 1"; }
            public String byField(String field, String arg) {
                return "json_extract(payload, '$." + field + "') = '{{" + arg + "}}'";
            }
        };
    }
}
