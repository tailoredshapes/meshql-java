package com.meshql.api.graphql;

import com.google.gson.Gson;
import graphql.ExecutionInput;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.language.ObjectTypeDefinition;
import graphql.scalars.ExtendedScalars;
import graphql.schema.DataFetcher;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.dataloader.DataLoaderRegistry;

import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.tailoredshapes.underbar.ocho.UnderBar.each;

public class Graphlette extends HttpServlet {
    private static final Gson gson = new Gson();
    private GraphQL graphQL;

    public Graphlette(Map<String, DataFetcher> fetchers, String schema) {
        SchemaParser schemaParser = new SchemaParser();
        TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(schema);

        RuntimeWiring.Builder builder = RuntimeWiring.newRuntimeWiring();

        // Register custom scalars
        builder.scalar(ExtendedScalars.Date);

        // Register Query data fetchers
        each(fetchers, (t, f) -> {
            builder.type("Query", b -> b.dataFetcher(t, f));
        });

        // Create a custom data fetcher that handles ResolverFunction objects
        DataFetcher<?> resolverDataFetcher = env -> {
            Object source = env.getSource();
            String fieldName = env.getField().getName();

            if (source instanceof Map) {
                Map<String, Object> sourceMap = (Map<String, Object>) source;
                Object value = sourceMap.get(fieldName);

                // If the value is a ResolverFunction, call it
                if (value instanceof ResolverFunction) {
                    ResolverFunction resolver = (ResolverFunction) value;
                    return resolver.resolve((com.tailoredshapes.stash.Stash) source, env);
                }

                return value;
            }

            return null;
        };

        // Register the resolver data fetcher for all object types in the schema
        typeDefinitionRegistry.getTypes(graphql.language.ObjectTypeDefinition.class).forEach(type -> {
            String typeName = type.getName();
            if (!typeName.equals("Query") && !typeName.equals("Mutation") && !typeName.equals("Subscription")) {
                builder.type(typeName, typeBuilder -> {
                    type.getFieldDefinitions().forEach(field -> {
                        typeBuilder.dataFetcher(field.getName(), resolverDataFetcher);
                    });
                    return typeBuilder;
                });
            }
        });

        RuntimeWiring runtimeWiring = builder.build();

        SchemaGenerator schemaGenerator = new SchemaGenerator();
        GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);

        this.graphQL = GraphQL.newGraphQL(graphQLSchema).build();
    }

    /**
     * Execute a GraphQL query internally without HTTP overhead, with no request
     * context. Callers that have a request context should use
     * {@link #executeInternal(String, Map)} so the caller's identity survives the hop.
     *
     * @param query The GraphQL query string
     * @return The JSON response as a string
     */
    public String executeInternal(String query) {
        return executeInternal(query, Map.of());
    }

    /**
     * Execute a GraphQL query internally, carrying the originating request's headers.
     *
     * <p>The headers are what let an {@link com.meshql.core.Auth} implementation see
     * who is asking. Without them an Auth that reads headers is handed GraphQL
     * <em>arguments</em> instead of the request, so an authenticated identity can
     * never reach the Searcher and per-record read filtering cannot be switched on
     * at all. They are also what {@code DTOFactory} forwards to subgraphs, so a
     * federated read is performed as the original caller rather than anonymously.
     *
     * @param query   The GraphQL query string
     * @param headers Lower-cased request headers; empty for genuinely internal calls
     * @return The JSON response as a string
     */
    public String executeInternal(String query, Map<String, Object> headers) {
        // Create fresh DataLoaderRegistry per request to prevent cache leaks
        DataLoaderRegistry registry = new DataLoaderRegistry();

        ExecutionInput input = ExecutionInput.newExecutionInput()
            .query(query)
            .dataLoaderRegistry(registry)
            .graphQLContext(Map.of(
                "dataLoaderRegistry", registry,
                "headers", headers != null ? headers : Map.of()))
            .build();

        ExecutionResult result = graphQL.execute(input);
        return gson.toJson(result.toSpecification());
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json");

        try {
            String body = request.getReader().lines().collect(Collectors.joining());
            GraphQLRequest graphQLRequest = gson.fromJson(body, GraphQLRequest.class);

            String jsonResponse = executeInternal(graphQLRequest.getQuery(), extractHeaders(request));

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(jsonResponse);
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write(gson.toJson(new ErrorResponse("Error processing GraphQL request")));
        }
    }

    /**
     * Copy the request headers into a plain map, lower-casing the names.
     *
     * <p>HTTP header names are case-insensitive, but the maps they end up in are not,
     * and every Auth implementation looks for {@code "authorization"}.
     */
    private static Map<String, Object> extractHeaders(HttpServletRequest request) {
        Map<String, Object> headers = new HashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        if (names == null) {
            return headers;
        }
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            headers.put(name.toLowerCase(Locale.ROOT), request.getHeader(name));
        }
        return headers;
    }

    private static class GraphQLRequest {
        private String query;

        public String getQuery() {
            return query;
        }
    }

    private record ErrorResponse(String error) {
    }
}
