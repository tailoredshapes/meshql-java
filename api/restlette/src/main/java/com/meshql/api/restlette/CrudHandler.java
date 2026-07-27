package com.meshql.api.restlette;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import com.meshql.core.Validator;
import com.tailoredshapes.stash.Stash;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static com.tailoredshapes.stash.Stash.stash;
import static com.tailoredshapes.underbar.ocho.UnderBar.map;

public class CrudHandler {
    private static final Logger logger = LoggerFactory.getLogger(CrudHandler.class);
    private final ObjectMapper objectMapper;

    private final Auth authorizer;
    private final Repository repository;
    private final List<String> tokens;
    private final Validator validator;

    public CrudHandler(Auth authorizer, Repository repository, Validator validator,
                       List<String> tokens) {
        this.authorizer = authorizer;
        this.repository = repository;
        this.tokens = tokens != null ? tokens : List.of();
        this.validator = validator;

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Handle create request
     */
    public void create(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String body = request.getReader().lines().collect(Collectors.joining());
            Stash payload = Stash.parseJSON(body);
            // Resolve the writer's identity from the request, not from the body they sent.
            List<String> authTokens = getAuthTokens(request);

            boolean isValid = validator.validate(payload).get();
            if (!isValid) {
                sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid payload");
                return;
            }

            String id = UUID.randomUUID().toString();
            List<String> storedTokens = tokensToPersist(authTokens);
            Envelope envelope = new Envelope(
                    id,
                    payload,
                    Instant.now(),
                    false,
                    storedTokens);

            Envelope result = repository.create(envelope, storedTokens);
            setHonestyHeaders(response, result);
            sendJsonResponse(response, HttpServletResponse.SC_CREATED, result.payload());
        } catch (Exception e) {
            logger.error("Failed to create resource", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to create resource");
        }
    }

    /**
     * Handle read request
     */
    public void read(HttpServletRequest request, HttpServletResponse response, String id) throws IOException {
        try {
            List<String> authTokens = getAuthTokens(request);

            Optional<Envelope> envelope = repository.read(id, authTokens, Instant.now());

            if (envelope.isEmpty()) {
                sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Resource not found");
                return;
            }

            setHonestyHeaders(response, envelope.get());
            sendJsonResponse(response, HttpServletResponse.SC_OK, envelope.get().payload());
        } catch (Exception e) {
            logger.error("Failed to read resource", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to read resource");
        }
    }

    /**
     * Handle update request
     */
    public void update(HttpServletRequest request, HttpServletResponse response, String id) throws IOException {
        try {
            String body = request.getReader().lines().collect(Collectors.joining());
            Stash payload = Stash.parseJSON(body);
            List<String> authTokens = getAuthTokens(request);

            boolean isValid = validator.validate(payload).get();
            if (!isValid) {
                sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid payload");
                return;
            }

            Optional<Envelope> existing = repository.read(id, authTokens, Instant.now());

            if (existing.isEmpty()) {
                sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Resource not found");
                return;
            }

            List<String> storedTokens = tokensToPersist(authTokens);
            Envelope envelope = new Envelope(
                    id,
                    payload,
                    Instant.now(),
                    false,
                    storedTokens);

            Envelope result = repository.create(envelope, storedTokens);
            setHonestyHeaders(response, result);
            sendJsonResponse(response, HttpServletResponse.SC_OK, result.payload());
        } catch (Exception e) {
            logger.error("Failed to update resource", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to update resource");
        }
    }

    /**
     * Handle delete request
     */
    public void remove(HttpServletRequest request, HttpServletResponse response, String id) throws IOException {
        try {
            List<String> authTokens = getAuthTokens(request);

            Boolean result = repository.remove(id, authTokens);

            if (!result) {
                sendJsonError(response, HttpServletResponse.SC_NOT_FOUND,
                    "Resource not found or could not be deleted");
                return;
            }

            Stash responseData = stash("id", id, "status", "deleted");
            sendJsonResponse(response, HttpServletResponse.SC_OK, responseData);
        } catch (Exception e) {
            logger.error("Failed to delete resource", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to delete resource");
        }
    }

    /**
     * Handle list request
     */
    public void list(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            List<String> authTokens = getAuthTokens(request);
            List<Envelope> results = repository.list(authTokens);

            List<Stash> payloads = map(results, Envelope::payload);
            sendJsonResponse(response, HttpServletResponse.SC_OK, payloads);
        } catch (Exception e) {
            logger.error("Failed to list resources", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to list resources");
        }
    }

    /**
     * Handle bulk create request
     */
    public void bulkCreate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String body = request.getReader().lines().collect(Collectors.joining());
            List<Map<String, Object>> items = objectMapper.readValue(
                    body,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));

            List<String> authTokens = getAuthTokens(request);
            List<String> storedTokens = tokensToPersist(authTokens);
            List<Envelope> envelopes = new ArrayList<>();

            for (Map<String, Object> item : items) {
                Stash payload = new Stash(item);

                boolean isValid = validator.validate(payload).get();
                if (!isValid) {
                    sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid payload in batch");
                    return;
                }

                String id = UUID.randomUUID().toString();
                Envelope envelope = new Envelope(
                        id,
                        payload,
                        Instant.now(),
                        false,
                        storedTokens);

                envelopes.add(envelope);
            }

            List<Envelope> results = repository.createMany(envelopes, storedTokens);
            sendJsonResponse(response, HttpServletResponse.SC_CREATED, results);
        } catch (Exception e) {
            logger.error("Failed to bulk create resources", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to bulk create resources");
        }
    }

    /**
     * Handle bulk read request
     */
    public void bulkRead(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String idsParam = request.getParameter("ids");
            if (idsParam == null || idsParam.isEmpty()) {
                sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing ids parameter");
                return;
            }

            List<String> ids = Arrays.asList(idsParam.split(","));
            List<String> authTokens = getAuthTokens(request);

            List<Envelope> results = repository.readMany(ids, authTokens);

            sendJsonResponse(response, HttpServletResponse.SC_OK, results);
        } catch (Exception e) {
            logger.error("Failed to bulk read resources", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to bulk read resources");
        }
    }

    /**
     * Decide which authorized tokens a new record is stamped with.
     *
     * <p>The union of the restlette's configured tokens and the tokens Auth resolved
     * for the writer. Previously the writer's tokens were computed and then thrown
     * away in favour of the configured list alone, which is almost always empty -
     * so everything written through the restlette was stored world-readable and no
     * amount of correct filtering downstream could restrict it.
     *
     * <p>Both empty stays both empty: a caller presenting no credentials writes a
     * public record. That is the documented contract - the edge authenticates, the
     * framework authorizes - and it is deliberately preserved.
     */
    private List<String> tokensToPersist(List<String> callerTokens) {
        if (callerTokens == null || callerTokens.isEmpty()) {
            return tokens;
        }
        if (tokens.isEmpty()) {
            return List.copyOf(callerTokens);
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(tokens);
        merged.addAll(callerTokens);
        return List.copyOf(merged);
    }

    /**
     * Get authentication tokens from request
     */
    private List<String> getAuthTokens(HttpServletRequest request) {
        try {
            String authHeader = request.getHeader("Authorization");
            if (authHeader != null && !authHeader.isEmpty()) {
                Stash context = new Stash();
                context.put("headers", Map.of("authorization", authHeader));
                return authorizer.getAuthToken(context);
            }
            return List.of();
        } catch (Exception e) {
            logger.error("Failed to get auth tokens", e);
            return List.of();
        }
    }

    /**
     * "Honesty" headers: the as-of timestamp and tombstone state, leaked
     * from the otherwise-internal Envelope so callers can tell how fresh
     * a payload is.
     */
    private void setHonestyHeaders(HttpServletResponse response, Envelope envelope) {
        response.setHeader("X-Meshql-Created-At", DateTimeFormatter.ISO_INSTANT.format(envelope.createdAt()));
        response.setHeader("X-Meshql-Deleted", String.valueOf(envelope.deleted()));
    }

    /**
     * Send JSON response
     */
    private void sendJsonResponse(HttpServletResponse response, int status, Object data) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(data));
    }

    /**
     * Send JSON error response
     */
    private void sendJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(stash("error", message)));
    }
}
