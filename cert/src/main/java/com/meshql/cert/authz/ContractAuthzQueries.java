package com.meshql.cert.authz;

/**
 * Backend-specific query templates for the shared authorization contract.
 *
 * <p>Two queries, and only the second one carries weight. {@code widgetById} is a
 * singleton lookup, which passes even when the authorization filter is inert
 * because the caller already knows the id it is asking for. {@code widgetByKind}
 * spans both callers' records, so it is the shape that tells a working filter
 * from a dead one.
 */
public class ContractAuthzQueries {
    public final String widgetById;
    public final String widgetByKind;

    public ContractAuthzQueries(String widgetById, String widgetByKind) {
        this.widgetById = widgetById;
        this.widgetByKind = widgetByKind;
    }

    public static ContractAuthzQueries sqliteQueries() {
        return new ContractAuthzQueries(
                "id = '{{id}}'",
                "json_extract(payload, '$.kind') = '{{kind}}'"
        );
    }

    public static ContractAuthzQueries postgresQueries() {
        return new ContractAuthzQueries(
                "id = '{{id}}'",
                "payload->>'kind' = '{{kind}}'"
        );
    }

    public static ContractAuthzQueries mongoQueries() {
        return new ContractAuthzQueries(
                "{\"id\": \"{{id}}\"}",
                "{\"payload.kind\": \"{{kind}}\"}"
        );
    }

    public static ContractAuthzQueries ksqlQueries() {
        return new ContractAuthzQueries(
                "id = '{{id}}'",
                "EXTRACTJSONFIELD(payload, '$.kind') = '{{kind}}'"
        );
    }

    /** The in-memory searcher understands a simple {@code field = 'value'} grammar. */
    public static ContractAuthzQueries memoryQueries() {
        return new ContractAuthzQueries(
                "id = '{{id}}'",
                "kind = '{{kind}}'"
        );
    }
}
