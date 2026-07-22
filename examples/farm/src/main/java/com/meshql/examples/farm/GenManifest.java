package com.meshql.examples.farm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Regenerates examples/farm/config/manifest.json. Run from the module
 * directory (examples/farm) so the default "config" path resolves.
 */
public class GenManifest {
    public static void main(String[] args) throws IOException {
        Path configDir = Paths.get(args.length > 0 ? args[0] : "config");
        JsonNode manifest = ManifestGenerator.generate(configDir);

        ObjectMapper mapper = new ObjectMapper();
        String output = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest);

        Path outputPath = configDir.resolve("manifest.json");
        Files.writeString(outputPath, output + "\n");
        System.out.println("Wrote " + outputPath.toAbsolutePath());
    }
}
