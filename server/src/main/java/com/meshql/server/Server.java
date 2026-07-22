package com.meshql.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.api.graphql.DTOFactory;
import com.meshql.api.graphql.Graphlette;
import com.meshql.api.graphql.Root;
import com.meshql.api.restlette.JSONSchemaValidator;
import com.meshql.api.restlette.Restlette;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.*;
import com.meshql.core.config.GraphletteConfig;
import com.meshql.core.config.RestletteConfig;
import com.tailoredshapes.stash.Stash;
import graphql.schema.DataFetcher;

import static com.tailoredshapes.stash.Stash.stash;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.FilterHolder;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class Server {
    private static final Logger logger = LoggerFactory.getLogger(Server.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Plugin> plugins;
    private org.eclipse.jetty.server.Server jettyServer;

    public Server(Map<String, Plugin> plugins) {
        this.plugins = plugins;
    }

    public void init(Config config) throws Exception {
        init(config, Map.of());
    }

    public void init(Config config, Map<String, HttpServlet> extraServlets) throws Exception {
        // Create thread pool with virtual thread support (Java 21+)
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.setVirtualThreadsExecutor(Executors.newVirtualThreadPerTaskExecutor());

        // Create Jetty server with virtual threads
        jettyServer = new org.eclipse.jetty.server.Server(threadPool);

        // Configure HTTP connector on specified port
        org.eclipse.jetty.server.ServerConnector connector =
                new org.eclipse.jetty.server.ServerConnector(jettyServer);
        connector.setPort(config.port());
        jettyServer.addConnector(connector);

        // Create servlet context
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
        context.setContextPath("/");
        jettyServer.setHandler(context);

        // Add CORS filter with configurable origin
        FilterHolder corsFilter = new FilterHolder(new CORSFilter());
        corsFilter.setInitParameter("allowedOrigin", config.corsOrigin());
        context.addFilter(corsFilter, "/*", null);

        // Process authentication
        Auth auth = processAuth(config);

        // Add health check endpoint (liveness)
        context.addServlet(new ServletHolder(new HealthCheckServlet()), "/health");

        // Add readiness endpoint (checks database health)
        context.addServlet(new ServletHolder(new ReadinessServlet(plugins)), "/ready");

        // Create shared graphlette registry for internal resolvers
        Stash graphletteRegistry = stash();

        // Process graphlettes
        if (config.graphlettes() != null) {
            for (GraphletteConfig graphletteConfig : config.graphlettes()) {
                try {
                    Graphlette graphlette = processGraphlette(context, graphletteConfig, auth, graphletteRegistry, config);
                    graphletteRegistry.put(graphletteConfig.path(), graphlette);
                    logger.info("Initialized Graphlette at path: {}", graphletteConfig.path());
                } catch (Exception e) {
                    logger.error("Failed to initialize Graphlette at path: {}", graphletteConfig.path(), e);
                }
            }
        }

        // Process restlettes
        if (config.restlettes() != null) {
            for (RestletteConfig restletteConfig : config.restlettes()) {
                try {
                    processRestlette(context, restletteConfig, auth);
                    logger.info("Initialized Restlette at path: {}", restletteConfig.path());
                } catch (Exception e) {
                    logger.error("Failed to initialize Restlette at path: {}", restletteConfig.path(), e);
                }
            }
        }

        // Extra caller-supplied servlets (e.g. a static manifest route),
        // mounted before start so they're live from the first request.
        for (Map.Entry<String, HttpServlet> entry : extraServlets.entrySet()) {
            context.addServlet(new ServletHolder(entry.getValue()), entry.getKey());
        }

        // Start the server
        jettyServer.start();
        logger.info("Server initialized on port {}", config.port());
    }

    private Auth processAuth(Config config) {
        // For now, use NoAuth. In the future, implement Casbin auth if casbinParams is set
        if (config.casbinParams() != null && !config.casbinParams().isEmpty()) {
            logger.warn("Casbin auth parameters found but not yet implemented, using NoAuth");
        }
        return new NoAuth();
    }

    private Graphlette processGraphlette(
            ServletContextHandler context,
            GraphletteConfig config,
            Auth auth,
            Stash graphletteRegistry,
            Config serverConfig
    ) throws IOException {
        // Read schema file
        String schemaContent = new String(Files.readAllBytes(Paths.get(config.schema())));

        // Create searcher from plugin
        Plugin plugin = plugins.get(config.storage().type);
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin for storage type " + config.storage().type + " not found");
        }

        Searcher searcher = plugin.createSearcher(config.storage());

        // Create DTO factory with both external and internal resolver support
        DTOFactory dtoFactory = new DTOFactory(
            config.rootConfig().singletonResolvers(),
            config.rootConfig().vectorResolvers(),
            config.rootConfig().internalSingletonResolvers(),
            config.rootConfig().internalVectorResolvers(),
            graphletteRegistry,
            config.rootConfig().dataLoaderEnabled(),
            serverConfig.connectTimeoutMs(),
            serverConfig.requestTimeoutMs()
        );

        // Create data fetchers
        Map<String, DataFetcher> fetchers = Root.create(searcher, dtoFactory, auth, config.rootConfig());

        // Create and register Graphlette servlet
        Graphlette graphlette = new Graphlette(fetchers, schemaContent);
        context.addServlet(new ServletHolder(graphlette), config.path());

        return graphlette;
    }

    private void processRestlette(ServletContextHandler context, RestletteConfig config, Auth auth) {
        // Create validator - convert JsonSchema to Map
        Map<String, Object> schemaMap = objectMapper.convertValue(
            config.schema().getSchemaNode(),
            Map.class
        );
        Validator validator = new JSONSchemaValidator(schemaMap);

        // Create and register Restlette
        Restlette restlette = new Restlette(config, plugins, auth, validator);

        // Mount the servlet at the configured path with wildcard
        context.addServlet(new ServletHolder(restlette), config.path() + "/*");
    }

    public void stop() throws Exception {
        logger.info("Stopping server");

        if (jettyServer != null) {
            jettyServer.stop();
            jettyServer.join();
        }

        // Cleanup plugins
        for (Plugin plugin : plugins.values()) {
            try {
                plugin.cleanUp();
            } catch (Exception e) {
                logger.error("Error cleaning up plugin", e);
            }
        }
    }

    /**
     * CORS filter to allow cross-origin requests
     */
    public static class CORSFilter implements Filter {
        private String allowedOrigin = "*";

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
            String origin = filterConfig.getInitParameter("allowedOrigin");
            if (origin != null) {
                this.allowedOrigin = origin;
            }
        }

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                throws IOException, ServletException {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setHeader("Access-Control-Allow-Origin", allowedOrigin);
            httpResponse.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
            httpResponse.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");

            HttpServletRequest httpRequest = (HttpServletRequest) request;
            if ("OPTIONS".equalsIgnoreCase(httpRequest.getMethod())) {
                httpResponse.setStatus(HttpServletResponse.SC_OK);
                return;
            }

            chain.doFilter(request, response);
        }

        @Override
        public void destroy() {
        }
    }

    /**
     * Simple health check servlet (liveness probe)
     */
    public static class HealthCheckServlet extends jakarta.servlet.http.HttpServlet {
        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("{\"status\":\"ok\"}");
        }
    }

    /**
     * Readiness check servlet that verifies database connectivity
     */
    public static class ReadinessServlet extends jakarta.servlet.http.HttpServlet {
        private final Map<String, Plugin> plugins;

        public ReadinessServlet(Map<String, Plugin> plugins) {
            this.plugins = plugins;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            response.setContentType("application/json");
            boolean healthy = plugins.values().stream().allMatch(Plugin::isHealthy);
            response.setStatus(healthy
                ? HttpServletResponse.SC_OK
                : HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.getWriter().write("{\"status\":\"" + (healthy ? "ok" : "unavailable") + "\"}");
        }
    }
}
