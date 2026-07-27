package com.meshql.cert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.classic.methods.*;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.IOException;
import java.util.Map;

/**
 * Simple HTTP client for REST and GraphQL operations.
 */
public class HttpClient {
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Status code of the most recent response, for tests that assert on it. */
    private int lastStatus;

    public HttpClient() {
        this.httpClient = HttpClients.createDefault();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Send a POST request with JSON body.
     */
    public JsonNode post(String url, String jsonBody) throws IOException {
        return post(url, jsonBody, null);
    }

    /**
     * Send a POST request with JSON body and an optional Authorization header.
     */
    public JsonNode post(String url, String jsonBody, String authorization) throws IOException {
        HttpPost request = new HttpPost(url);
        request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        return executeRequest(request, authorization);
    }

    /**
     * Send a PUT request with JSON body.
     */
    public JsonNode put(String url, String jsonBody) throws IOException {
        return put(url, jsonBody, null);
    }

    public JsonNode put(String url, String jsonBody, String authorization) throws IOException {
        HttpPut request = new HttpPut(url);
        request.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));
        return executeRequest(request, authorization);
    }

    /**
     * Send a GET request.
     */
    public JsonNode get(String url) throws IOException {
        return get(url, null);
    }

    public JsonNode get(String url, String authorization) throws IOException {
        HttpGet request = new HttpGet(url);
        return executeRequest(request, authorization);
    }

    /**
     * Send a DELETE request.
     */
    public void delete(String url) throws IOException {
        delete(url, null);
    }

    public JsonNode delete(String url, String authorization) throws IOException {
        HttpDelete request = new HttpDelete(url);
        return executeRequest(request, authorization);
    }

    /**
     * Execute a GraphQL query.
     */
    public JsonNode graphql(String url, String query) throws IOException {
        return graphql(url, query, null);
    }

    /**
     * Execute a GraphQL query on behalf of a credentialed caller.
     *
     * <p>Credentials travel as an {@code Authorization} header — the way a real
     * deployment supplies them — not as a GraphQL argument.
     */
    public JsonNode graphql(String url, String query, String authorization) throws IOException {
        String requestBody = objectMapper.writeValueAsString(Map.of("query", query));
        JsonNode response = post(url, requestBody, authorization);
        return response.get("data");
    }

    public int getLastStatus() {
        return lastStatus;
    }

    private JsonNode executeRequest(HttpUriRequestBase request, String authorization) throws IOException {
        if (authorization != null) {
            request.setHeader("Authorization", authorization);
        }
        try (CloseableHttpResponse response = httpClient.execute(request)) {
            lastStatus = response.getCode();
            if (response.getEntity() == null) {
                return objectMapper.nullNode();
            }
            String responseBody = EntityUtils.toString(response.getEntity());
            if (responseBody == null || responseBody.isBlank()) {
                return objectMapper.nullNode();
            }
            return objectMapper.readTree(responseBody);
        } catch (org.apache.hc.core5.http.ParseException e) {
            throw new IOException("Failed to parse response", e);
        }
    }

    public void close() throws IOException {
        httpClient.close();
    }
}
