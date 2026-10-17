package com.meshql.core.config;

import com.networknt.schema.JsonSchema;

import java.util.List;

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
        JsonSchema schema,
        boolean frozen,
        List<String> writers
) {
    /**
     * @param frozen  the entity is create-only: a document is never replaced or
     *                removed, so PUT and DELETE on {@code /{id}} answer 405. A new
     *                state is a new document. This is how an event is declared.
     * @param writers the credentials that may write (POST, PUT, DELETE, bulk POST);
     *                every other caller, anonymous included, gets 403. A
     *                credential is the {@code sub} claim of the bearer JWT, or the
     *                bearer token itself when it is not a JWT. Null or empty means
     *                anyone may write, which is the default. Reads are never
     *                restricted by it. This is how a projection is declared:
     *                the front end is not in the list, its worker is.
     */
    public RestletteConfig {
        writers = writers == null ? List.of() : List.copyOf(writers);
    }

    /** An open, replaceable restlette: the shape before {@code frozen} and {@code writers} existed. */
    public RestletteConfig(String path, int port, StorageConfig storage, JsonSchema schema) {
        this(path, port, storage, schema, false, List.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String path;
        private int port = 3033;
        private StorageConfig storage;
        private JsonSchema schema;
        private boolean frozen = false;
        private List<String> writers = List.of();

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

        /** Declare the entity create-only (an event): PUT and DELETE answer 405. */
        public Builder frozen(boolean frozen) {
            this.frozen = frozen;
            return this;
        }

        /** Restrict writes to these credentials (a projection): everyone else gets 403. */
        public Builder writers(List<String> writers) {
            this.writers = writers;
            return this;
        }

        public RestletteConfig build() {
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("restlette path is required");
            }
            if (storage == null) {
                throw new IllegalArgumentException("restlette storage is required");
            }
            return new RestletteConfig(path, port, storage, schema, frozen, writers);
        }
    }
}
