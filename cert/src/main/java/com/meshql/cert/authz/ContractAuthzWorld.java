package com.meshql.cert.authz;

import com.fasterxml.jackson.databind.JsonNode;
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

    /** Widget name -> MeshQL id, discovered from storage after a REST create. */
    public final Map<String, String> ids = new HashMap<>();

    /** Named instants captured mid-scenario, for the temporal reads. */
    public final Map<String, Long> stamps = new HashMap<>();

    /** Widget names returned by the most recent list or vector query. */
    public List<String> names = new ArrayList<>();

    /** The most recent temporal read, kept so a later Then step can assert on it. */
    public JsonNode temporalResult;

    /** Status of the most recent delete. */
    public Integer deleteStatus;
}
