package com.meshql.examples.farm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class ManifestGeneratorTest {
    private static final Path CONFIG_DIR = Paths.get("config");

    @Test
    void generatesAnEntryForEveryGraphqlFile() throws Exception {
        JsonNode manifest = ManifestGenerator.generate(CONFIG_DIR);

        assertEquals(1, manifest.get("meshql").asInt());
        JsonNode entities = manifest.get("entities");
        assertTrue(entities.has("farm"));
        assertTrue(entities.has("coop"));
        assertTrue(entities.has("hen"));
        assertTrue(entities.has("lay_report"));
    }

    @Test
    void graphSurfaceHasPathKindAndInlinedSchemaText() throws Exception {
        JsonNode manifest = ManifestGenerator.generate(CONFIG_DIR);
        JsonNode farmGraph = manifest.at("/entities/farm/surfaces/graph");

        assertEquals("graphql", farmGraph.get("kind").asText());
        assertEquals("/farm/graph", farmGraph.get("path").asText());
        assertTrue(farmGraph.get("schema").asText().contains("type"),
            "schema field should contain the raw GraphQL SDL text");
    }

    @Test
    void apiSurfaceHasPathKindAndParsedJsonSchema() throws Exception {
        JsonNode manifest = ManifestGenerator.generate(CONFIG_DIR);
        JsonNode farmApi = manifest.at("/entities/farm/surfaces/api");

        assertEquals("rest", farmApi.get("kind").asText());
        assertEquals("/farm/api", farmApi.get("path").asText());
        assertTrue(farmApi.get("schema").isObject(),
            "schema field should be a parsed JSON object, not a string");
    }

    @Test
    void topLevelSurfacesIsEmptyBecauseThereIsNoChangeFeed() throws Exception {
        JsonNode manifest = ManifestGenerator.generate(CONFIG_DIR);
        assertTrue(manifest.get("surfaces").isEmpty());
    }
}
