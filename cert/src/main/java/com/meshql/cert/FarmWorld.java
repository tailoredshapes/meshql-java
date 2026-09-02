package com.meshql.cert;

import com.fasterxml.jackson.databind.JsonNode;
import com.meshql.auth.token.TokenSession;
import com.meshql.core.AuthSession;
import com.meshql.core.Plugin;
import com.meshql.core.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared state for farm certification tests.
 * Matches TypeScript FarmTestWorld pattern - lightweight with references to shared FarmEnv.
 */
public class FarmWorld {
    /**
     * The session the farm suite writes and reads with. It holds no tokens, so
     * everything it writes is stamped markless and stays public - which is what
     * this suite was doing before, when it passed an empty token list, and what
     * it needs, because it reads back over unauthenticated HTTP.
     */
    public static final AuthSession SESSION = new TokenSession(java.util.List.of());

    // Reference to shared environment (persistent across scenarios)
    public FarmEnv env;

    // Server reference (shared, created in BeforeAll)
    public Object server;

    // Legacy fields for compatibility with repositories map
    public Map<String, Repository> repositories = new HashMap<>();
    public Map<String, Plugin> plugins = new HashMap<>();

    // Per-scenario state (reset each scenario)
    public JsonNode queryResult;
    public JsonNode[] queryResults;
    public Long now;

    // Convenience accessors that delegate to env
    public String getBaseUrl() {
        return env != null ? env.getPlatformUrl() : null;
    }

    public int getPort() {
        return env != null ? env.getPort() : 0;
    }
}
