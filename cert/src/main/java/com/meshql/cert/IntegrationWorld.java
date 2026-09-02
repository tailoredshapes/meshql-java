package com.meshql.cert;

import com.github.jknack.handlebars.Template;
import com.meshql.core.*;
import com.meshql.core.config.StorageConfig;
import com.tailoredshapes.stash.Stash;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IntegrationWorld {
    // Configuration (provided by test implementation)
    public Plugin plugin;
    public StorageConfig storageConfig;
    public SearcherTestTemplates templates;
    /**
     * The caller every scenario runs as. There is no unset session: a suite that
     * left this null would be asserting against a caller entitled to nothing.
     */
    public AuthSession session;

    // Test state
    public Repository repository;
    public Searcher searcher;
    public Map<String, Envelope> envelopes = new HashMap<>();
    public Map<String, Long> timestamps = new HashMap<>();
    public Long testStartTime;
    public Stash searchResult;
    public List<Stash> searchResults;
    public Boolean removeResult;
    public Map<String, Boolean> removeResults;
}
