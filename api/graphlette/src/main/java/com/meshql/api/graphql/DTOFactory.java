package com.meshql.api.graphql;

import com.meshql.core.Filler;
import com.meshql.core.config.InternalSingletonResolverConfig;
import com.meshql.core.config.InternalVectorResolverConfig;
import com.meshql.core.config.ResolverConfig;
import com.meshql.core.config.SingletonResolverConfig;
import com.meshql.core.config.VectorResolverConfig;
import com.tailoredshapes.stash.Stash;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.tailoredshapes.stash.Stash.stash;

public class DTOFactory implements Filler {
    private static final Logger logger = LoggerFactory.getLogger(DTOFactory.class);
    private final Map<String, SingletonResolver> singletonResolvers = new HashMap<>();
    private final Map<String, VectorResolver> vectorResolvers = new HashMap<>();
    private final Stash graphlettes;
    private final boolean dataLoaderEnabled;
    private final int connectTimeoutMs;
    private final int requestTimeoutMs;

    /**
     * Creates a DTOFactory with DataLoader enabled by default.
     */
    public DTOFactory(
            List<SingletonResolverConfig> singletonConfigs,
            List<VectorResolverConfig> vectorConfigs,
            List<InternalSingletonResolverConfig> internalSingletonConfigs,
            List<InternalVectorResolverConfig> internalVectorConfigs,
            Stash graphlettes
    ) {
        this(singletonConfigs, vectorConfigs, internalSingletonConfigs, internalVectorConfigs, graphlettes, true, 10_000, 30_000);
    }

    /**
     * Creates a DTOFactory with configurable DataLoader support.
     *
     * @param dataLoaderEnabled When true, resolvers will batch requests using DataLoader.
     *                          When false, resolvers fall back to direct calls (useful for debugging or disabling batching).
     */
    public DTOFactory(
            List<SingletonResolverConfig> singletonConfigs,
            List<VectorResolverConfig> vectorConfigs,
            List<InternalSingletonResolverConfig> internalSingletonConfigs,
            List<InternalVectorResolverConfig> internalVectorConfigs,
            Stash graphlettes,
            boolean dataLoaderEnabled
    ) {
        this(singletonConfigs, vectorConfigs, internalSingletonConfigs, internalVectorConfigs, graphlettes, dataLoaderEnabled, 10_000, 30_000);
    }

    /**
     * Creates a DTOFactory with configurable DataLoader support and federation timeouts.
     */
    public DTOFactory(
            List<SingletonResolverConfig> singletonConfigs,
            List<VectorResolverConfig> vectorConfigs,
            List<InternalSingletonResolverConfig> internalSingletonConfigs,
            List<InternalVectorResolverConfig> internalVectorConfigs,
            Stash graphlettes,
            boolean dataLoaderEnabled,
            int connectTimeoutMs,
            int requestTimeoutMs
    ) {
        this.graphlettes = graphlettes != null ? graphlettes : stash();
        this.dataLoaderEnabled = dataLoaderEnabled;
        this.connectTimeoutMs = connectTimeoutMs;
        this.requestTimeoutMs = requestTimeoutMs;

        if (!dataLoaderEnabled) {
            logger.info("DataLoader is disabled - resolvers will use direct calls instead of batching");
        }

        // Process external resolvers
        if (singletonConfigs != null) {
            for (SingletonResolverConfig c : singletonConfigs) {
                singletonResolvers.put(c.name(), assignExternalSingletonResolver(c.id(), c.queryName(), c.url()));
            }
        }
        if (vectorConfigs != null) {
            for (VectorResolverConfig c : vectorConfigs) {
                vectorResolvers.put(c.name(), assignExternalVectorResolver(c.id(), c.queryName(), c.url()));
            }
        }

        // Process internal resolvers
        if (internalSingletonConfigs != null) {
            for (InternalSingletonResolverConfig c : internalSingletonConfigs) {
                singletonResolvers.put(c.name(), assignInternalSingletonResolver(c.id(), c.queryName(), c.graphletteName()));
            }
        }
        if (internalVectorConfigs != null) {
            for (InternalVectorResolverConfig c : internalVectorConfigs) {
                vectorResolvers.put(c.name(), assignInternalVectorResolver(c.id(), c.queryName(), c.graphletteName()));
            }
        }
    }

    @Override
    public Stash fillOne(Map<String, Object> data, long timestamp) {
        Stash copy = stash("_timestamp", timestamp);

        // Add singleton resolvers
        singletonResolvers.forEach((key, resolver) -> {
            if (resolver != null) {
                copy.put(key, resolver);
            }
        });

        // Add vector resolvers
        vectorResolvers.forEach((key, resolver) -> {
            if (resolver != null) {
                copy.put(key, resolver);
            }
        });

        // Add data properties
        copy.putAll(data);
        return copy;
    }

    @Override
    public List<Stash> fillMany(List<Stash> data, long timestamp) {
        return data.stream()
                .map(d -> fillOne(d, timestamp))
                .collect(Collectors.toList());
    }

    private SingletonResolver assignExternalSingletonResolver(String id, String queryName, URI url) {
        // Default to "id" if not specified (for relationships based on parent's id field)
        final String foreignKeyField = (id != null) ? id : "id";

        logger.debug("Assigning external singleton resolver for: foreignKeyField={}, queryName={}, url={}", foreignKeyField, queryName, url);

        return (parent, env) -> {
            Object foreignKey = parent.get(foreignKeyField);
            if (foreignKey == null) {
                return CompletableFuture.completedFuture(stash());
            }

            // Get DataLoaderRegistry from context (handle null env for backward compatibility)
            DataLoaderRegistry registry = null;
            if (dataLoaderEnabled && env != null && env.getGraphQlContext() != null) {
                registry = env.getGraphQlContext().get("dataLoaderRegistry");
            }

            if (registry == null) {
                // Fallback to direct call if DataLoader not available or disabled
                if (dataLoaderEnabled) {
                    logger.debug("DataLoader registry not available, falling back to direct call for {}", queryName);
                }
                SubgraphClient client = new SubgraphClient(connectTimeoutMs, requestTimeoutMs);
                String query = SubgraphClient.processContext(
                    foreignKey.toString(),
                    createContext(env),
                    queryName,
                    (Long) parent.get("_timestamp")
                );
                return client.resolveSingleton(url, query, queryName, extractAuthHeader(env));
            }

            // Create unique key for this (url, queryName) combination
            String loaderKey = url.toString() + ":" + queryName;
            long timestamp = (Long) parent.get("_timestamp");
            String authHeader = extractAuthHeader(env);

            // Get or create DataLoader
            DataLoader<String, Stash> loader = registry.computeIfAbsent(loaderKey, k -> {
                String selectionSet = extractSelectionSet(env);
                logger.debug("Creating DataLoader for {}", loaderKey);
                return DataLoaderFactory.createExternalLoader(url, queryName, selectionSet, timestamp, authHeader, connectTimeoutMs, requestTimeoutMs);
            });

            return loader.load(foreignKey.toString());
        };
    }

    private VectorResolver assignExternalVectorResolver(String id, String queryName, URI url) {
        // Default to "id" if not specified (for relationships based on parent's id field)
        final String foreignKeyField = (id != null) ? id : "id";

        logger.debug("Assigning external vector resolver for: foreignKeyField={}, queryName={}, url={}", foreignKeyField, queryName, url);

        SubgraphClient client = new SubgraphClient(connectTimeoutMs, requestTimeoutMs);

        return (parent, env) -> {
            Object foreignKey = parent.get(foreignKeyField);
            if (foreignKey == null) {
                return List.of();
            }

            String query = SubgraphClient.processContext(
                foreignKey.toString(),
                createContext(env),
                queryName,
                (Long) parent.get("_timestamp")
            );

            String authHeader = extractAuthHeader(env);
            return client.resolveVector(url, query, queryName, authHeader);
        };
    }

    private SingletonResolver assignInternalSingletonResolver(String id, String queryName, String graphletteName) {
        // Default to "id" if not specified (for relationships based on parent's id field)
        final String foreignKeyField = (id != null) ? id : "id";

        logger.debug("Assigning internal singleton resolver for: foreignKeyField={}, queryName={}, graphletteName={}", foreignKeyField, queryName, graphletteName);

        return (parent, env) -> {
            Object foreignKey = parent.get(foreignKeyField);
            if (foreignKey == null) {
                return CompletableFuture.completedFuture(stash());
            }

            Graphlette graphlette = (Graphlette) graphlettes.get(graphletteName);
            if (graphlette == null) {
                logger.error("Graphlette not found: {}", graphletteName);
                return CompletableFuture.completedFuture(stash());
            }

            // Get DataLoaderRegistry from context (handle null env for backward compatibility)
            DataLoaderRegistry registry = null;
            if (dataLoaderEnabled && env != null && env.getGraphQlContext() != null) {
                registry = env.getGraphQlContext().get("dataLoaderRegistry");
            }

            if (registry == null) {
                // Fallback to direct call if DataLoader not available or disabled
                if (dataLoaderEnabled) {
                    logger.debug("DataLoader registry not available, falling back to direct call for internal {}", queryName);
                }
                String query = SubgraphClient.processContext(
                    foreignKey.toString(),
                    createContext(env),
                    queryName,
                    (Long) parent.get("_timestamp")
                );

                String jsonResponse = graphlette.executeInternal(query, extractHeaders(env));
                Stash response = Stash.parseJSON(jsonResponse);

                if (response.containsKey("errors")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
                    logger.error("GraphQL error from internal resolver: {}", errors.get(0).get("message"));
                    return stash();
                }

                Stash data = response.asStash("data");
                if (data.containsKey(queryName) && data.get(queryName) != null) {
                    return data.asStash(queryName);
                } else {
                    return stash();
                }
            }

            // Create unique key for this (graphletteName, queryName) combination
            String loaderKey = "internal:" + graphletteName + ":" + queryName;
            long timestamp = (Long) parent.get("_timestamp");
            Map<String, Object> headers = extractHeaders(env);

            // Get or create DataLoader
            DataLoader<String, Stash> loader = registry.computeIfAbsent(loaderKey, k -> {
                String selectionSet = extractSelectionSet(env);
                logger.debug("Creating internal DataLoader for {}", loaderKey);
                return DataLoaderFactory.createInternalLoader(graphlette, queryName, selectionSet, timestamp, headers);
            });

            return loader.load(foreignKey.toString());
        };
    }

    private VectorResolver assignInternalVectorResolver(String id, String queryName, String graphletteName) {
        // Default to "id" if not specified (for relationships based on parent's id field)
        final String foreignKeyField = (id != null) ? id : "id";

        logger.debug("Assigning internal vector resolver for: foreignKeyField={}, queryName={}, graphletteName={}", foreignKeyField, queryName, graphletteName);

        return (parent, env) -> {
            Object foreignKey = parent.get(foreignKeyField);
            if (foreignKey == null) {
                return CompletableFuture.completedFuture(List.of());
            }

            Graphlette graphlette = (Graphlette) graphlettes.get(graphletteName);
            if (graphlette == null) {
                logger.error("Graphlette not found: {}", graphletteName);
                return CompletableFuture.completedFuture(List.of());
            }

            // Get DataLoaderRegistry from context (handle null env for backward compatibility)
            DataLoaderRegistry registry = null;
            if (dataLoaderEnabled && env != null && env.getGraphQlContext() != null) {
                registry = env.getGraphQlContext().get("dataLoaderRegistry");
            }

            if (registry == null) {
                // Fallback to direct call if DataLoader not available or disabled
                if (dataLoaderEnabled) {
                    logger.debug("DataLoader registry not available, falling back to direct call for internal vector {}", queryName);
                }
                String query = SubgraphClient.processContext(
                    foreignKey.toString(),
                    createContext(env),
                    queryName,
                    (Long) parent.get("_timestamp")
                );

                String jsonResponse = graphlette.executeInternal(query, extractHeaders(env));
                Stash response = Stash.parseJSON(jsonResponse);

                // Extract vector from response (same logic as SubgraphClient)
                if (response.containsKey("errors")) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> errors = (List<Map<String, Object>>) response.get("errors");
                    logger.error("GraphQL error from internal resolver: {}", errors.get(0).get("message"));
                    return List.of();
                }

                Stash data = response.asStash("data");
                if (data.containsKey(queryName) && data.get(queryName) != null) {
                    return data.asStashes(queryName);
                } else {
                    return List.of();
                }
            }

            // Create unique key for this (graphletteName, queryName) combination
            String loaderKey = "internal-vector:" + graphletteName + ":" + queryName;
            long timestamp = (Long) parent.get("_timestamp");
            Map<String, Object> headers = extractHeaders(env);

            // Get or create DataLoader for vectors
            DataLoader<String, List<Stash>> loader = registry.computeIfAbsent(loaderKey, k -> {
                String selectionSet = extractSelectionSet(env);
                logger.debug("Creating internal vector DataLoader for {}", loaderKey);
                return DataLoaderFactory.createInternalVectorLoader(graphlette, queryName, selectionSet, timestamp, headers);
            });

            return loader.load(foreignKey.toString());
        };
    }

    private Map<String, Object> createContext(DataFetchingEnvironment env) {
        Map<String, Object> context = new HashMap<>();
        context.put("fieldNodes", env.getFields());
        context.put("schema", env.getGraphQLSchema());
        return context;
    }

    /**
     * Pull the originating request's headers out of the GraphQL context.
     *
     * <p>Returns an empty map rather than null when there is no request context,
     * which is the normal case for a genuinely internal execution.
     */
    private Map<String, Object> extractHeaders(DataFetchingEnvironment env) {
        if (env == null || env.getGraphQlContext() == null) {
            return Map.of();
        }
        Map<String, Object> headers = env.getGraphQlContext().get("headers");
        return headers != null ? headers : Map.of();
    }

    private String extractAuthHeader(DataFetchingEnvironment env) {
        Map<String, Object> headers = extractHeaders(env);
        if (headers.containsKey("authorization")) {
            return headers.get("authorization").toString();
        }
        return null;
    }

    /**
     * Extract the selection set from the DataFetchingEnvironment.
     * This is used to know which fields to request when batching.
     */
    private String extractSelectionSet(DataFetchingEnvironment env) {
        Field field = env.getField();
        if (field.getSelectionSet() != null) {
            return SubgraphClient.processSelectionSet(field.getSelectionSet());
        }
        return "";
    }
}