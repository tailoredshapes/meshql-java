package com.meshql.examples.farm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ManifestConformanceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path CONFIG_DIR = Paths.get("config");
    private static final Path MANIFEST_PATH = CONFIG_DIR.resolve("manifest.json");
    // Relative to examples/farm/ (the module dir, = surefire's CWD): up
    // to examples/, up to repo root, then into the vendored schema.
    private static final Path SCHEMA_PATH = Paths.get("../../schemas/manifest.schema.json");

    @Test
    void manifestValidatesAgainstPublishedSchema() throws Exception {
        JsonNode schemaNode = MAPPER.readTree(SCHEMA_PATH.toFile());
        JsonNode manifest = MAPPER.readTree(MANIFEST_PATH.toFile());

        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        JsonSchema schema = factory.getSchema(schemaNode);
        Set<ValidationMessage> errors = schema.validate(manifest);

        assertTrue(errors.isEmpty(), "manifest invalid: " + errors);
    }

    @Test
    void manifestMatchesRegeneration() throws Exception {
        JsonNode committed = MAPPER.readTree(MANIFEST_PATH.toFile());
        JsonNode generated = ManifestGenerator.generate(CONFIG_DIR);

        assertEquals(committed, generated,
            "config/manifest.json is stale — regenerate (see Task 3, Step 3 of the manifest-parity plan)");
    }

    @Test
    void everyGraphEntityAppearsInManifestWithCorrectSurfaces() throws Exception {
        JsonNode manifest = MAPPER.readTree(MANIFEST_PATH.toFile());
        JsonNode entities = manifest.get("entities");

        List<Path> graphFiles;
        try (Stream<Path> paths = Files.list(CONFIG_DIR.resolve("graph"))) {
            graphFiles = paths.filter(p -> p.toString().endsWith(".graphql")).toList();
        }

        int seen = 0;
        for (Path path : graphFiles) {
            String filename = path.getFileName().toString();
            String entity = filename.substring(0, filename.length() - ".graphql".length());

            JsonNode e = entities.get(entity);
            assertNotNull(e, "entity '" + entity + "' missing from manifest");
            assertEquals("graphql", e.at("/surfaces/graph/kind").asText(), entity + " graph surface");

            boolean hasJsonSchema = Files.exists(CONFIG_DIR.resolve("json").resolve(entity + ".schema.json"));
            if (hasJsonSchema) {
                assertEquals("rest", e.at("/surfaces/api/kind").asText(), entity + " api surface");
            } else {
                assertTrue(e.get("surfaces").path("api").isMissingNode(),
                    entity + " must not advertise an api surface it doesn't have");
            }
            seen++;
        }
        assertEquals(seen, entities.size(), "manifest entity count != graph file count");
    }
}
