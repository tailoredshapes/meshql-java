package com.meshql.cert.egg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/**
 * A reference hen worker: whenever a lay report happens, the hen's productivity is
 * recomputed. It is the harness's, not the library's and not under test; it exists so
 * the surfaces have a projection to serve.
 *
 * <p>It does what the spec says a good worker does and nothing more. It reads through
 * the restlettes and the graph, as a worklette would, and writes through the
 * restlette with the worker's credential. Its fold is a function of the hen's lay
 * reports, not of the projection (so a redelivered event changes nothing), and it
 * writes only when the projection differs.
 */
public class HenWorker {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EggEconomyEnv env;
    private final HttpClient http = HttpClient.newHttpClient();

    public HenWorker(EggEconomyEnv env) {
        this.env = env;
    }

    /** Fold the lay report {@code eventId}'s hen. Throws when it cannot, so the bridge delivers again. */
    public void onLayReport(String eventId) throws Exception {
        JsonNode event = send("GET", env.apiUrl("lay_report") + "/" + eventId, null);
        String hen = event.get("hen_id").asText();
        String farm = event.get("farm_id").asText();

        long total = 0;
        long good = 0;
        for (JsonNode report : send("GET", env.apiUrl("lay_report"), null)) {
            if (!hen.equals(report.path("hen_id").asText())) {
                continue;
            }
            long eggs = report.path("eggs").asLong();
            String quality = report.path("quality").asText("");
            total += eggs;
            if (quality.isEmpty() || quality.equals("grade_a") || quality.equals("double_yolk")) {
                good += eggs;
            }
        }
        double rate = total == 0 ? 0.0 : (double) good / total;
        Map<String, Object> doc = Map.of("hen_id", hen, "farm_id", farm, "total_eggs", total, "quality_rate", rate);

        JsonNode found = send("POST", env.graphUrl("hen_productivity"),
                Map.of("query", "{ getByHen(id: \"" + hen + "\") { id total_eggs quality_rate } }"))
                .path("data").path("getByHen");
        if (found.isArray() && found.size() > 0) {
            JsonNode current = found.get(0);
            if (current.path("total_eggs").asLong() == total && Math.abs(current.path("quality_rate").asDouble() - rate) < 1e-9) {
                return;
            }
            send("PUT", env.apiUrl("hen_productivity") + "/" + current.get("id").asText(), doc);
        } else {
            send("POST", env.apiUrl("hen_productivity"), doc);
        }
    }

    private JsonNode send(String method, String url, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", EggEconomyEnv.bearer(EggEconomyEnv.WORKER))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)));
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IllegalStateException(method + " " + url + " answered " + response.statusCode() + ": " + response.body());
        }
        return MAPPER.readTree(response.body());
    }
}
