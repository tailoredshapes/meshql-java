package com.meshql.core.config;

import com.networknt.schema.JsonSchema;

/**
 * One REST surface.
 *
 * <p>There is no {@code tokens} field. It used to name a list of authorized
 * tokens the handler added to everything it wrote, which the read path then knew
 * nothing about: a record stamped with the restlette's token was no longer
 * public, but no caller was ever granted that token, so writes and reads
 * disagreed about who could see what. Stamping a record is the auth plugin's
 * job now - see {@code AuthSession.stamp} - and a deployment that wants every
 * record in a restlette marked says so in its plugin, where the read side can
 * see the same rule.
 */
public record RestletteConfig(
        String path,
        int port,
        StorageConfig storage,
        JsonSchema schema
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String path;
        private int port = 3033;
        private StorageConfig storage;
        private JsonSchema schema;

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder storage(StorageConfig storage) {
            this.storage = storage;
            return this;
        }

        public Builder schema(JsonSchema schema) {
            this.schema = schema;
            return this;
        }

        public RestletteConfig build() {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("restlette path is required");
            }
            if (storage == null) {
                throw new IllegalArgumentException("restlette storage is required");
            }
            return new RestletteConfig(path, port, storage, schema);
        }
    }
}
