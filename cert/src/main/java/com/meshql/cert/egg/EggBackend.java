package com.meshql.cert.egg;

import com.meshql.core.Plugin;
import com.meshql.core.config.StorageConfig;

import java.util.function.Function;

/**
 * One scenario's worth of storage for the egg economy: the plugin, a storage config
 * for each entity naming a table, collection or file no earlier scenario wrote to,
 * and the store's query templates.
 *
 * @param storageFor the storage of an entity, by entity name; the same config for the
 *                   same entity every time it is asked
 */
public record EggBackend(Plugin plugin, Function<String, StorageConfig> storageFor, EggQueries queries) {
}
