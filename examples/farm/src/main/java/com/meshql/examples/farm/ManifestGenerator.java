package com.meshql.examples.farm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds the deployment manifest (schemas/manifest.schema.json) from
 * config/graph/*.graphql and config/json/*.schema.json. Mirrors
 * meshql-rs's examples/egg-economy/src/manifest.rs: reads the config
 * directory directly (not the runtime Config object), deterministic
 * (sorted by filename), path convention by construction
 * (/{entity}/graph, /{entity}/api).
 */
public class ManifestGenerator {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static JsonNode generate(Path configDir) throws IOException {
        ObjectNode entities = MAPPER.createObjectNode();
        Path graphDir = configDir.resolve("graph");
        Path jsonDir = configDir.resolve("json");

        List<Path> graphqlFiles;
        try (Stream<Path> paths = Files.list(graphDir)) {
            graphqlFiles = paths
                    .filter(p -> p.toString().endsWith(".graphql"))
                    .sorted()
                    .collect(Collectors.toList());
        }

        for (Path path : graphqlFiles) {
            String filename = path.getFileName().toString();
            String entity = filename.substring(0, filename.length() - ".graphql".length());
            String graphqlText = Files.readString(path);

            ObjectNode surfaces = MAPPER.createObjectNode();
            ObjectNode graphSurface = MAPPER.createObjectNode();
            graphSurface.put("kind", "graphql");
            graphSurface.put("path", "/" + entity + "/graph");
            graphSurface.put("schema", graphqlText);
            surfaces.set("graph", graphSurface);

            Path jsonSchemaPath = jsonDir.resolve(entity + ".schema.json");
            if (Files.exists(jsonSchemaPath)) {
                JsonNode jsonSchema = MAPPER.readTree(jsonSchemaPath.toFile());
                ObjectNode apiSurface = MAPPER.createObjectNode();
                apiSurface.put("kind", "rest");
                apiSurface.put("path", "/" + entity + "/api");
                apiSurface.set("schema", jsonSchema);
                surfaces.set("api", apiSurface);
            }

            ObjectNode entityNode = MAPPER.createObjectNode();
            entityNode.set("surfaces", surfaces);
            entities.set(entity, entityNode);
        }

        ObjectNode manifest = MAPPER.createObjectNode();
        manifest.put("meshql", 1);
        manifest.set("entities", entities);
        manifest.set("surfaces", MAPPER.createObjectNode());
        return manifest;
    }
}
