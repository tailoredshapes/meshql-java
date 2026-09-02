package com.meshql.api.restlette;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meshql.core.Auth;
import com.meshql.core.Plugin;
import com.meshql.core.Repository;
import com.meshql.core.Validator;
import com.meshql.core.config.RestletteConfig;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

public class Restlette extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(Restlette.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final CrudHandler crudHandler;
    private final SwaggerUIHandler swaggerUIHandler;
    private final String openAPIJson;
    private final String apiPath;

    static {
        objectMapper.registerModule(new JavaTimeModule());
    }

    public Restlette(RestletteConfig rc, Map<String, Plugin> storageFactory, Auth auth, Validator validator) {
        this.apiPath = rc.path();
        var port = rc.port();

        Plugin plugin = storageFactory.get(rc.storage().type);
        Repository repo = plugin.createRepository(rc.storage());

        this.crudHandler = new CrudHandler(auth, repo, validator);
        this.swaggerUIHandler = new SwaggerUIHandler(apiPath);

        // Create Swagger document
        OpenAPI openAPI = createSwaggerDocument(apiPath, port, rc.schema());
        this.openAPIJson = openAPI.getOpenapi();

        logger.info("API Docs are available at: http://localhost:{}{}api-docs", port, apiPath);
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String pathInfo = request.getPathInfo() != null ? request.getPathInfo() : "/";
        String method = request.getMethod();

        try {
            // Handle Swagger documentation routes
            if (pathInfo.equals("/api-docs/swagger.json")) {
                handleSwaggerJson(response);
                return;
            }

            if (pathInfo.equals("/api-docs") || pathInfo.equals("/api-docs/")) {
                swaggerUIHandler.handle(request, response);
                return;
            }

            // Handle CRUD routes
            if (pathInfo.equals("/bulk")) {
                if ("POST".equals(method)) {
                    crudHandler.bulkCreate(request, response);
                } else if ("GET".equals(method)) {
                    crudHandler.bulkRead(request, response);
                } else {
                    response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
                return;
            }

            if (pathInfo.equals("/") || pathInfo.equals("")) {
                if ("POST".equals(method)) {
                    crudHandler.create(request, response);
                } else if ("GET".equals(method)) {
                    crudHandler.list(request, response);
                } else {
                    response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
                return;
            }

            // Handle routes with ID parameter (e.g., /123)
            if (pathInfo.startsWith("/") && pathInfo.length() > 1) {
                String id = pathInfo.substring(1);

                switch (method) {
                    case "GET":
                        crudHandler.read(request, response, id);
                        break;
                    case "PUT":
                        crudHandler.update(request, response, id);
                        break;
                    case "DELETE":
                        crudHandler.remove(request, response, id);
                        break;
                    default:
                        response.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
                }
                return;
            }

            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        } catch (Exception e) {
            logger.error("Error handling request", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Internal server error\"}");
        }
    }

    private void handleSwaggerJson(HttpServletResponse response) throws IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(openAPIJson);
    }

    /**
     * Create the Swagger document
     */
    private static OpenAPI createSwaggerDocument(String apiPath, int port, com.networknt.schema.JsonSchema schema) {
        OpenAPI openAPI = new OpenAPI();

        Info info = new Info()
                .title(apiPath + " API")
                .version("0.1.0")
                .description("API for mutating " + apiPath);

        openAPI.setInfo(info);

        Server server = new Server();
        server.setUrl("http://localhost:" + port);
        openAPI.setServers(Collections.singletonList(server));

        // Add security schemes and schemas
        SwaggerConfig.configureSwagger(openAPI, schema);

        return openAPI;
    }
}
