package com.meshql.api.restlette;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meshql.auth.noop.NoAuth;
import com.meshql.core.Auth;
import com.meshql.core.Envelope;
import com.meshql.core.Repository;
import com.meshql.core.Validator;
import com.meshql.repositories.memory.InMemoryRepository;
import com.tailoredshapes.stash.Stash;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.*;

import static com.tailoredshapes.stash.Stash.stash;
import static com.tailoredshapes.underbar.ocho.UnderBar.list;
import static com.tailoredshapes.underbar.ocho.UnderBar.map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CrudHandlerTest {
    private static final String API_PATH = "/hens";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private Repository repository;
    private Auth auth;
    private Validator validator;
    private CrudHandler crudHandler;
    private HttpServletRequest mockRequest;
    private HttpServletResponse mockResponse;
    private StringWriter responseWriter;

    private static final Stash henSchema = stash(
            "type", "object",
            "properties", stash(
                    "name", stash("type", "string", "minLength", 1),
                    "eggs", stash("type", "integer", "minimum", 0)),
            "required", list("name", "eggs"),
            "additionalProperties", false);

    @BeforeEach
    void setUp() throws Exception {
        repository = new InMemoryRepository();
        auth = new NoAuth();
        validator = new JSONSchemaValidator(henSchema);
        crudHandler = new CrudHandler(auth, repository, validator, List.of());

        mockRequest = mock(HttpServletRequest.class);
        mockResponse = mock(HttpServletResponse.class);

        // Setup response writer
        responseWriter = new StringWriter();
        when(mockResponse.getWriter()).thenReturn(new PrintWriter(responseWriter));
    }

    @Test
    void testBasicCRUDOperations() throws Exception {
        // Test Create
        var payload = stash("name","Henny","eggs", 5);
        String henData = payload.toJSONString();
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(henData)));

        crudHandler.create(mockRequest, mockResponse);

        verify(mockResponse).setStatus(HttpServletResponse.SC_CREATED);
        String createResponseJson = responseWriter.toString();
        Stash createResult = Stash.parseJSON(createResponseJson);
        assertEquals("Henny", createResult.get("name"));
        assertEquals(5.0, createResult.get("eggs"));


        // Get the ID from the repository
        Envelope envelope = repository.list(list()).get(0);
        String henId = envelope.id();

        // Test Read
        responseWriter.getBuffer().setLength(0); // Clear the response

        crudHandler.read(mockRequest, mockResponse, henId);

        String readResponseJson = responseWriter.toString();
        Stash readResult = Stash.parseJSON(readResponseJson);
        assertEquals("Henny", readResult.get("name"));
        assertEquals(5.0, readResult.get("eggs"));

        // Test Update
        responseWriter.getBuffer().setLength(0); // Clear the response
        String updatedHenData = "{\"name\":\"Henny\",\"eggs\":10}";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(updatedHenData)));

        crudHandler.update(mockRequest, mockResponse, henId);

        String updateResponseJson = responseWriter.toString();
        Stash updateResult = Stash.parseJSON(updateResponseJson);
        assertEquals("Henny", updateResult.get("name"));
        assertEquals(10.0, updateResult.get("eggs"));

        // Test Delete
        responseWriter.getBuffer().setLength(0); // Clear the response

        crudHandler.remove(mockRequest, mockResponse, henId);

        String deleteResponseJson = responseWriter.toString();
        Stash deleteResult = Stash.parseJSON(deleteResponseJson);
        assertEquals(henId, deleteResult.get("id"));
        assertEquals("deleted", deleteResult.get("status"));

        // Verify delete by trying to read again
        responseWriter.getBuffer().setLength(0); // Clear the response

        crudHandler.read(mockRequest, mockResponse, henId);

        verify(mockResponse, times(1)).setStatus(HttpServletResponse.SC_NOT_FOUND);
        String readAfterDeleteJson = responseWriter.toString();
        Stash readAfterDeleteResult = Stash.parseJSON(readAfterDeleteJson);
        assertTrue(readAfterDeleteResult.containsKey("error"));
    }

    @Test
    void testNegativeCases() throws Exception {
        // Test invalid ID
        crudHandler.read(mockRequest, mockResponse, "999");

        verify(mockResponse).setStatus(HttpServletResponse.SC_NOT_FOUND);
        String invalidReadJson = responseWriter.toString();
        Stash invalidReadResult = Stash.parseJSON(invalidReadJson);
        assertTrue(invalidReadResult.containsKey("error"));

        // Test invalid payload for creation
        responseWriter.getBuffer().setLength(0); // Clear the response
        String invalidHenData = "{\"eggs\":\"not a number\"}";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(invalidHenData)));

        crudHandler.create(mockRequest, mockResponse);

        verify(mockResponse).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        String invalidCreateJson = responseWriter.toString();
        Stash invalidCreateResult = Stash.parseJSON(invalidCreateJson);
        assertTrue(invalidCreateResult.containsKey("error"));
    }

    @Test
    void testAuthorizationScenarios() throws Exception {
        // Pre-populate a test hen
        String henId = "666";
        Envelope hen = new Envelope(
                henId,
                stash("name", "chuck", "eggs", 6),
                Instant.now(),
                false,
                List.of("token"));
        repository.create(hen, list());

        // Test with valid token
        when(mockRequest.getHeader("Authorization")).thenReturn("token");

        crudHandler.read(mockRequest, mockResponse, henId);

        String authorizedJson = responseWriter.toString();
        Stash authorizedResult = Stash.parseJSON(authorizedJson);
        assertEquals("chuck", authorizedResult.get("name"));
        assertEquals(6.0, authorizedResult.get("eggs"));

        // Test with invalid token (NoAuth implementation doesn't actually check tokens)
        responseWriter.getBuffer().setLength(0); // Clear the response
        when(mockRequest.getHeader("Authorization")).thenReturn("invalidToken");

        crudHandler.read(mockRequest, mockResponse, henId);

        String unauthorizedJson = responseWriter.toString();
        Stash unauthorizedResult = Stash.parseJSON(unauthorizedJson);
        // With NoAuth, this should still work
        assertEquals("chuck", unauthorizedResult.get("name"));
    }

    @Test
    void testBulkOperations() throws Exception {
        // Test bulk create
        String bulkHenData = "[{\"name\":\"Hen1\",\"eggs\":1},{\"name\":\"Hen2\",\"eggs\":2}]";
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(bulkHenData)));

        crudHandler.bulkCreate(mockRequest, mockResponse);

        verify(mockResponse).setStatus(HttpServletResponse.SC_CREATED);

        // Verify repository has 2 items
        List<Envelope> envelopes = repository.list(list());
        assertEquals(2, envelopes.size());

        // Test bulk read
        responseWriter.getBuffer().setLength(0); // Clear the response
        List<String> henIds = map(envelopes, (e) -> e.id());

        when(mockRequest.getParameter("ids")).thenReturn(String.join(",", henIds));

        crudHandler.bulkRead(mockRequest, mockResponse);

        // Convert the result to a String
        String resultJson = responseWriter.toString();
        assertTrue(resultJson.contains("\"name\":\"Hen1\""));
        assertTrue(resultJson.contains("\"name\":\"Hen2\""));
    }

    /**
     * "Honesty" headers: create/read/update responses carry the Envelope's
     * as-of timestamp and tombstone state as headers, without widening the
     * JSON body (which stays payload-only, per the REST identity model).
     */
    @Test
    void testHonestyHeadersOnCreateReadUpdate() throws Exception {
        var payload = stash("name", "Henny", "eggs", 5);
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader(payload.toJSONString())));

        crudHandler.create(mockRequest, mockResponse);

        verify(mockResponse).setHeader(eq("X-Meshql-Created-At"), matches("^\\d{4}-\\d{2}-\\d{2}T.*Z$"));
        verify(mockResponse).setHeader("X-Meshql-Deleted", "false");

        String henId = repository.list(list()).get(0).id();

        reset(mockResponse);
        when(mockResponse.getWriter()).thenReturn(new PrintWriter(responseWriter));
        responseWriter.getBuffer().setLength(0);

        crudHandler.read(mockRequest, mockResponse, henId);

        verify(mockResponse).setHeader(eq("X-Meshql-Created-At"), matches("^\\d{4}-\\d{2}-\\d{2}T.*Z$"));
        verify(mockResponse).setHeader("X-Meshql-Deleted", "false");

        reset(mockResponse);
        when(mockResponse.getWriter()).thenReturn(new PrintWriter(responseWriter));
        responseWriter.getBuffer().setLength(0);
        when(mockRequest.getReader()).thenReturn(new BufferedReader(new StringReader("{\"name\":\"Henny\",\"eggs\":10}")));

        crudHandler.update(mockRequest, mockResponse, henId);

        verify(mockResponse).setHeader(eq("X-Meshql-Created-At"), matches("^\\d{4}-\\d{2}-\\d{2}T.*Z$"));
        verify(mockResponse).setHeader("X-Meshql-Deleted", "false");
    }
}
