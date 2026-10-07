package com.meshql.cert.authz;

import com.fasterxml.jackson.databind.JsonNode;
import com.meshql.auth.token.TokenSession;
import com.meshql.core.Auth;
import com.meshql.core.AuthSession;
import com.meshql.core.Plugin;
import com.meshql.core.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-scenario state for the shared authorization contract.
 *
 * <p>There is no workspace field here, and that absence is the design. The
 * contract's {@code Background} stands up a server and nothing else, so every
 * scenario starts against an empty store — see {@link ContractAuthzHooks}.
 */
public class ContractAuthzWorld {
    /** The server and its URLs, rebuilt for each scenario. */
    public ContractAuthzEnv env;
    /** Running server (Object to avoid a compile-time dependency on the server module). */
    public Object server;
    /**
     * Direct storage access. Used to resolve a widget name to its id, which is
     * harness plumbing, and by the two scenarios that inspect the stored envelope
     * to prove the write path kept the tokens the Auth resolved.
     */
    public Repository repository;
    public Map<String, Plugin> plugins = new HashMap<>();

    /**
     * The session the harness reads storage with. It is whichever plugin is under
     * test answering {@link Auth#systemSession()}, asked for explicitly, and it
     * defaults to the token plugin's because that is what a server configured the
     * documented way resolves.
     */
    public AuthSession systemSession = TokenSession.system();

    /**
     * Start this scenario's server with the given auth plugin, or with the one the
     * server resolves from its own configuration when {@code auth} is null.
     *
     * <p>Starting happens from a step rather than from the hooks because the
     * plugin is a property of the scenario: {@code authz.feature} says "an
     * authorizing MeshQL server", and {@code auth_plugin.feature} names the plugin
     * it wants.
     */
    public void startServer(Auth auth) throws Exception {
        startServer(auth, null);
    }

    /**
     * Start this scenario's server with the widget restlette enforcing
     * {@code restletteSchema} ({@code schema.feature}), or the permissive default
     * when it is null.
     */
    public void startServer(Auth auth, com.networknt.schema.JsonSchema restletteSchema) throws Exception {
        stopServer();
        server = env.buildService(plugins, auth, restletteSchema);
        if (auth != null) {
            systemSession = auth.systemSession();
        }
    }

    public void stopServer() throws Exception {
        if (server != null) {
            // Server.stop() also cleans up the plugins it was given, so only the
            // server that is actually running may be stopped.
            server.getClass().getMethod("stop").invoke(server);
            server = null;
        }
    }

    /** Widget name -> MeshQL id, discovered from storage after a REST create. */
    public final Map<String, String> ids = new HashMap<>();

    /** Named instants captured mid-scenario, for the temporal reads. */
    public final Map<String, Long> stamps = new HashMap<>();

    /** Widget names returned by the most recent list or vector query. */
    public List<String> names = new ArrayList<>();

    /** The most recent temporal read, kept so a later Then step can assert on it. */
    public JsonNode temporalResult;

    /**
     * The entries of the most recent version listing, so a later Then step can
     * assert on their order, their URLs, or what the last one says.
     */
    public List<JsonNode> versions = new ArrayList<>();

    /** Status of the most recent delete. */
    public Integer deleteStatus;

    /** Status of the most recent write a scenario expects might be refused. */
    public Integer writeStatus;
}
