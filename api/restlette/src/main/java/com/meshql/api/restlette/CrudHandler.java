package com.meshql.api.restlette;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meshql.core.Auth;
import com.meshql.core.AuthSession;
import com.meshql.core.Envelope;
import com.meshql.core.NotAuthorizedException;
import com.meshql.core.Repository;
import com.meshql.core.Validator;
import com.meshql.core.VersionRef;
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
    private final Validator validator;

    public CrudHandler(Auth authorizer, Repository repository, Validator validator) {
        this.authorizer = authorizer;
        this.repository = repository;
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
            AuthSession session = sessionFor(request);

            boolean isValid = validator.validate(payload).get();
            if (!isValid) {
                sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid payload");
                return;
            }

            String id = UUID.randomUUID().toString();
            Envelope envelope = new Envelope(id, payload, Instant.now(), false, null);

            Envelope result = repository.create(envelope, session);
            setHonestyHeaders(response, result);
            sendJsonResponse(response, HttpServletResponse.SC_CREATED, result.payload());
        } catch (NotAuthorizedException e) {
            sendJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Not authorized");
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
            Optional<Envelope> envelope = repository.read(id, sessionFor(request), Instant.now());

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
            AuthSession session = sessionFor(request);

            boolean isValid = validator.validate(payload).get();
            if (!isValid) {
                sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid payload");
                return;
            }

            Optional<Envelope> existing = repository.read(id, session, Instant.now());

            if (existing.isEmpty()) {
                sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "Resource not found");
                return;
            }

            Envelope envelope = new Envelope(id, payload, Instant.now(), false, null);

            Envelope result = repository.create(envelope, session);
            setHonestyHeaders(response, result);
            sendJsonResponse(response, HttpServletResponse.SC_OK, result.payload());
        } catch (NotAuthorizedException e) {
            sendJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Not authorized");
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
            Boolean result = repository.remove(id, sessionFor(request));

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
            List<Envelope> results = repository.list(sessionFor(request));

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

            AuthSession session = sessionFor(request);
            List<Envelope> envelopes = new ArrayList<>();

            for (Map<String, Object> item : items) {
                Stash payload = new Stash(item);

                boolean isValid = validator.validate(payload).get();
                if (!isValid) {
                    sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid payload in batch");
                    return;
                }

                String id = UUID.randomUUID().toString();
                envelopes.add(new Envelope(id, payload, Instant.now(), false, null));
            }

            List<Envelope> results = repository.createMany(envelopes, session);
            sendJsonResponse(response, HttpServletResponse.SC_CREATED, results);
        } catch (NotAuthorizedException e) {
            sendJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Not authorized");
        } catch (Exception e) {
            logger.error("Failed to bulk create resources", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to bulk create resources");
        }
    }

    /**
     * Every version of one document, oldest first.
     *
     * <p>Each entry carries the URL that addresses that version, so a caller
     * follows a link rather than assembling one. A version the caller may not read
     * still appears, carrying when it happened and whether it was a deletion and
     * saying it is unauthorized - no URL at all, rather than a null one. Omitting
     * it would make the history look continuous when it is not.
     *
     * @param basePath the path this restlette is mounted at, which is what makes
     *                 the URLs in the response followable
     */
    public void listVersions(HttpServletRequest request, HttpServletResponse response,
                             String basePath, String id) throws IOException {
        try {
            List<VersionRef> versions = repository.listVersions(id, sessionFor(request));

            List<Map<String, Object>> entries = new ArrayList<>();
            for (VersionRef version : versions) {
                Map<String, Object> entry = new LinkedHashMap<>();
                if (version.authorized()) {
                    entry.put("url", basePath + "/" + id + "/versions/" + version.token());
                }
                entry.put("created_at", DateTimeFormatter.ISO_INSTANT.format(version.createdAt()));
                entry.put("deleted", version.deleted());
                if (!version.authorized()) {
                    entry.put("unauthorized", true);
                }
                entries.add(entry);
            }

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id", id);
            body.put("versions", entries);
            sendJsonResponse(response, HttpServletResponse.SC_OK, body);
        } catch (Exception e) {
            logger.error("Failed to list versions", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to list versions");
        }
    }

    /**
     * One version of a document, in the same shape as reading the document itself.
     *
     * <p>An unknown token is a 404. A version the caller cannot read is a 403, not
     * a 404 - the listing already told them it exists, and pretending otherwise
     * would contradict the answer they were just given.
     */
    public void readVersion(HttpServletRequest request, HttpServletResponse response,
                            String id, String token) throws IOException {
        try {
            Optional<Envelope> envelope = repository.readVersion(id, token, sessionFor(request));

            if (envelope.isEmpty()) {
                sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "No such version");
                return;
            }

            setHonestyHeaders(response, envelope.get());
            sendJsonResponse(response, HttpServletResponse.SC_OK, envelope.get().payload());
        } catch (NotAuthorizedException e) {
            sendJsonError(response, HttpServletResponse.SC_FORBIDDEN,
                "Not authorized to read this version");
        } catch (Exception e) {
            logger.error("Failed to read version", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to read version");
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

            List<Envelope> results = repository.readMany(ids, sessionFor(request));

            sendJsonResponse(response, HttpServletResponse.SC_OK, results);
        } catch (Exception e) {
            logger.error("Failed to bulk read resources", e);
            sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "Failed to bulk read resources");
        }
    }

    /**
     * Ask the auth plugin who this request is, and hand the answer to storage.
     *
     * <p>The handler no longer decides what a record is stamped with, nor which
     * records a caller may see. Both used to happen here - the writer's tokens
     * were merged with a configured list on write, and the read path knew nothing
     * about that list, so the two disagreed about who could see what. What goes on
     * a record is now {@code AuthSession.stamp}, and who may see it is
     * {@code AuthSession.isAuthorized}.
     *
     * <p>A request with no Authorization header still gets a session, resolved
     * from an empty context. There is no unset session: what an unidentified
     * caller may do is the plugin's answer, not the absence of one.
     */
    private AuthSession sessionFor(HttpServletRequest request) {
        Stash context = new Stash();
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.isEmpty()) {
            context.put("headers", Map.of("authorization", authHeader));
        }
        return authorizer.authenticate(context);
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
