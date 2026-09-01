package com.meshql.cert.authz;

import com.meshql.core.Plugin;
import com.meshql.core.config.StorageConfig;

/**
 * One scenario's worth of storage: an empty store, the plugin that reaches it,
 * and the query templates written in that store's query language.
 *
 * @param plugin  a plugin instance dedicated to this scenario
 * @param storage the storage config naming the fresh table, collection, topic or
 *                directory the scenario writes into
 * @param queries the backend's {@code getById} and {@code getByKind} templates
 */
public record ContractAuthzBackend(
        Plugin plugin,
        StorageConfig storage,
        ContractAuthzQueries queries
) {
}
