package com.meshql.cert;

import com.fasterxml.jackson.databind.JsonNode;
import com.meshql.core.Plugin;
import com.meshql.core.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-scenario state for the authorization certification.
 */
public class AuthzWorld {
    /** Shared environment, created once per adapter in {@code @BeforeAll}. */
    public AuthzEnv env;
    /** Running server (Object to avoid a compile-time dependency on the server module). */
    public Object server;
    /**
     * Direct storage access, used only by the "tokens survive the write path" scenario.
     * Every other assertion goes through HTTP, because that is where the defects live.
     */
    public Repository repository;
    public Map<String, Plugin> plugins = new HashMap<>();

    /**
     * A per-scenario workspace value stamped into every document, so scenarios stay
     * isolated from each other even though they share one datastore.
     */
    public String workspace;

    /** Label -> MeshQL id, discovered via GraphQL after a REST create. */
    public final Map<String, String> ids = new HashMap<>();

    public JsonNode lastData;
    public int lastStatus;
    public Long firstStamp;

    public String getBaseUrl() {
        return env != null ? env.getPlatformUrl() : null;
    }
}
