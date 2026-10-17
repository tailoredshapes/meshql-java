package com.meshql.cert.egg;

import com.fasterxml.jackson.databind.JsonNode;
import com.meshql.core.Plugin;
import com.meshql.core.Repository;

import java.util.HashMap;
import java.util.Map;

/** One scenario's state for the egg economy feature. */
public class EggWorld {
    public EggBackend backend;
    public Plugin plugin;
    public EggEconomyEnv env;
    public Object server;
    public EventBridge bridge;
    /** Repositories on the stores, the harness's window onto what was stored (a restlette does not return ids). */
    public final Map<String, Repository> repositories = new HashMap<>();

    /** Documents made through the front end, by "entity:name". */
    public final Map<String, String> ids = new HashMap<>();
    public String lastHen;
    public String lastFarm;

    /** The last event the front end posted: its entity, and the document it sent. */
    public String lastEventEntity;
    public JsonNode lastEvent;

    public int lastStatus;
    public String lastBody;

    public final Map<String, Long> stamps = new HashMap<>();
    public JsonNode graphResponse;

    public void stop() throws Exception {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
        if (server != null) {
            server.getClass().getMethod("stop").invoke(server);
            server = null;
        }
    }
}
