package com.meshql.api.graphql;

import com.tailoredshapes.stash.Stash;
import org.dataloader.BatchLoader;
import org.dataloader.DataLoader;
import org.dataloader.DataLoaderOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static com.tailoredshapes.stash.Stash.stash;

/**
 * Factory for creating DataLoaders that batch subgraph calls.
 * Follows the same pattern as meshobj's dataLoaderFactory.ts.
 */
public class DataLoaderFactory {
    private static final Logger logger = LoggerFactory.getLogger(DataLoaderFactory.class);
    private static final int MAX_BATCH_SIZE = 100;

    /**
     * Creates a DataLoader for batching external HTTP subgraph calls.
     *
     * @param url The GraphQL endpoint URI
     * @param queryName The query name (e.g., "getById")
     * @param selectionSet The GraphQL selection set (fields to fetch)
     * @param timestamp Timestamp for temporal queries
     * @param authHeader Authorization header to forward
     * @return DataLoader instance for batching requests
     */
    public static DataLoader<String, Stash> createExternalLoader(
            URI url,
            String queryName,
            String selectionSet,
            long timestamp,
            String authHeader
    ) {
        return createExternalLoader(url, queryName, selectionSet, timestamp, authHeader, 10_000, 30_000);
    }

    public static DataLoader<String, Stash> createExternalLoader(
            URI url,
            String queryName,
            String selectionSet,
            long timestamp,
            String authHeader,
            int connectTimeoutMs,
            int requestTimeoutMs
    ) {
        BatchLoader<String, Stash> batchLoader = ids -> {
            logger.debug("External batch loader invoked with {} ids for {}", ids.size(), queryName);

            SubgraphClient client = new SubgraphClient(connectTimeoutMs, requestTimeoutMs);
            Stash results = client.batchResolve(
                url,
                new ArrayList<>(ids),
                selectionSet,
                queryName,
                timestamp,
                authHeader
            );

            // Return results in order matching input IDs
            List<Stash> orderedResults = IntStream.range(0, ids.size())
                .mapToObj(i -> {
                    String key = "item_" + i;
                    if (results.containsKey(key) && results.get(key) != null) {
                        return results.asStash(key);
                    }
                    return stash();
                })
                .toList();

            return CompletableFuture.completedFuture(orderedResults);
        };

        return DataLoader.newDataLoader(batchLoader,
            DataLoaderOptions.newOptions().setMaxBatchSize(MAX_BATCH_SIZE));
    }

    /**
     * Creates a DataLoader for batching internal graphlette-to-graphlette calls.
     *
     * @param graphlette The target Graphlette to call
     * @param queryName The query name (e.g., "getById")
     * @param selectionSet The GraphQL selection set (fields to fetch)
     * @param timestamp Timestamp for temporal queries
     * @return DataLoader instance for batching requests
     */
    public static DataLoader<String, Stash> createInternalLoader(
            Graphlette graphlette,
            String queryName,
            String selectionSet,
            long timestamp
    ) {
        return createInternalLoader(graphlette, queryName, selectionSet, timestamp, java.util.Map.of());
    }

    /**
     * As above, but carries the originating request's headers so the batched
     * subgraph read is performed as the original caller rather than anonymously.
     */
    public static DataLoader<String, Stash> createInternalLoader(
            Graphlette graphlette,
            String queryName,
            String selectionSet,
            long timestamp,
            java.util.Map<String, Object> headers
    ) {
        BatchLoader<String, Stash> batchLoader = ids -> {
            logger.debug("Internal batch loader invoked with {} ids for {}", ids.size(), queryName);

            // Build aliased query same as external
            StringBuilder aliasedQueries = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) {
                aliasedQueries.append(String.format(
                    "item_%d: %s(id: \"%s\" at: %d) {\n%s}\n",
                    i, queryName, ids.get(i), timestamp, selectionSet
                ));
            }
            String query = "{ " + aliasedQueries + " }";

            // Execute single internal query
            String jsonResponse = graphlette.executeInternal(query, headers);
            Stash response = Stash.parseJSON(jsonResponse);

            // Check for errors
            if (response.containsKey("errors")) {
                @SuppressWarnings("unchecked")
                var errors = (java.util.List<java.util.Map<String, Object>>) response.get("errors");
                String errorMsg = errors.get(0).get("message").toString();
                logger.error("GraphQL error from internal batch resolver: {}", errorMsg);
                // Return empty stashes for all IDs on error
                List<Stash> emptyResults = IntStream.range(0, ids.size())
                    .mapToObj(i -> stash())
                    .toList();
                return CompletableFuture.completedFuture(emptyResults);
            }

            Stash data = response.asStash("data");

            // Extract results in order
            List<Stash> orderedResults = IntStream.range(0, ids.size())
                .mapToObj(i -> {
                    String key = "item_" + i;
                    if (data.containsKey(key) && data.get(key) != null) {
                        return data.asStash(key);
                    }
                    return stash();
                })
                .toList();

            return CompletableFuture.completedFuture(orderedResults);
        };

        return DataLoader.newDataLoader(batchLoader,
            DataLoaderOptions.newOptions().setMaxBatchSize(MAX_BATCH_SIZE));
    }

    /**
     * Creates a DataLoader for batching internal graphlette-to-graphlette vector calls.
     * Vector calls return List<Stash> instead of Stash.
     *
     * @param graphlette The target Graphlette to call
     * @param queryName The query name (e.g., "getByHen")
     * @param selectionSet The GraphQL selection set (fields to fetch)
     * @param timestamp Timestamp for temporal queries
     * @return DataLoader instance for batching vector requests
     */
    public static DataLoader<String, List<Stash>> createInternalVectorLoader(
            Graphlette graphlette,
            String queryName,
            String selectionSet,
            long timestamp
    ) {
        return createInternalVectorLoader(graphlette, queryName, selectionSet, timestamp, java.util.Map.of());
    }

    /**
     * As above, but carries the originating request's headers so the batched
     * subgraph read is performed as the original caller rather than anonymously.
     */
    public static DataLoader<String, List<Stash>> createInternalVectorLoader(
            Graphlette graphlette,
            String queryName,
            String selectionSet,
            long timestamp,
            java.util.Map<String, Object> headers
    ) {
        BatchLoader<String, List<Stash>> batchLoader = ids -> {
            logger.debug("Internal vector batch loader invoked with {} ids for {}", ids.size(), queryName);

            // Build aliased query same as external
            StringBuilder aliasedQueries = new StringBuilder();
            for (int i = 0; i < ids.size(); i++) {
                aliasedQueries.append(String.format(
                    "item_%d: %s(id: \"%s\" at: %d) {\n%s}\n",
                    i, queryName, ids.get(i), timestamp, selectionSet
                ));
            }
            String query = "{ " + aliasedQueries + " }";

            // Execute single internal query
            String jsonResponse = graphlette.executeInternal(query, headers);
            Stash response = Stash.parseJSON(jsonResponse);

            // Check for errors
            if (response.containsKey("errors")) {
                @SuppressWarnings("unchecked")
                var errors = (java.util.List<java.util.Map<String, Object>>) response.get("errors");
                String errorMsg = errors.get(0).get("message").toString();
                logger.error("GraphQL error from internal vector batch resolver: {}", errorMsg);
                // Return empty lists for all IDs on error
                List<List<Stash>> emptyResults = IntStream.range(0, ids.size())
                    .mapToObj(i -> List.<Stash>of())
                    .toList();
                return CompletableFuture.completedFuture(emptyResults);
            }

            Stash data = response.asStash("data");

            // Extract results in order - each result is a List<Stash> for vector queries
            List<List<Stash>> orderedResults = IntStream.range(0, ids.size())
                .mapToObj(i -> {
                    String key = "item_" + i;
                    if (data.containsKey(key) && data.get(key) != null) {
                        return data.asStashes(key);
                    }
                    return List.<Stash>of();
                })
                .toList();

            return CompletableFuture.completedFuture(orderedResults);
        };

        return DataLoader.newDataLoader(batchLoader,
            DataLoaderOptions.newOptions().setMaxBatchSize(MAX_BATCH_SIZE));
    }
}
