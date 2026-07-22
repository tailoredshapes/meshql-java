package com.meshql.examples.farm;

import com.meshql.core.Config;
import com.meshql.server.Server;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManifestServingIntegrationTest {
    // Fixed port: Server.java doesn't currently expose the bound port
    // when started on an ephemeral one (port 0), so this test uses a
    // high, unlikely-to-collide port rather than adding that capability
    // (out of scope for this change).
    private static final int TEST_PORT = 34567;

    @Test
    void manifestServletServesTheGivenContentAt200() throws Exception {
        String manifestJson = "{\"meshql\":1,\"entities\":{},\"surfaces\":{}}";
        Config config = new Config(List.of(), List.of(), TEST_PORT, List.of(), "*", 5000, 5000);
        Server server = new Server(new HashMap<>());

        try {
            server.init(config, Map.of("/manifest", new ManifestServlet(manifestJson)));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + TEST_PORT + "/manifest"))
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(manifestJson, response.body());
        } finally {
            server.stop();
        }
    }
}
