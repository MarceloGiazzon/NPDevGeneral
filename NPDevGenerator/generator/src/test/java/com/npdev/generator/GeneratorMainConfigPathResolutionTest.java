package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneratorMainConfigPathResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void resolvesRelativeConfiguredPathsFromConfigDirectory() throws Exception {
        Path workspace = Files.createTempDirectory("npdev-generator-config-paths-");
        Path inputRoot = Files.createDirectories(workspace.resolve("NPDevSamples").resolve("sample-a").resolve("Input"));
        Path configPath = inputRoot.resolve("config.json");
        Files.writeString(configPath, """
                {
                  "scenario": {
                    "outputRoot": "..\\\\Output"
                  },
                  "bootstrap": {
                    "root": "..\\\\..\\\\..\\\\NPDevRuntimeHost"
                  },
                  "artifact": {
                    "root": "..\\\\Output\\\\ArtifactNP"
                  },
                  "finalExec": {
                    "root": "..\\\\Output\\\\App"
                  }
                }
                """);

        JsonNode config = MAPPER.readTree(configPath.toFile());

        assertEquals(
                inputRoot.resolve("..").resolve("Output").normalize().toAbsolutePath(),
                GeneratorMain.resolveConfiguredPath(configPath.toString(), config, "scenario", "outputRoot")
        );
        assertEquals(
                inputRoot.resolve("..").resolve("Output").resolve("ArtifactNP").normalize().toAbsolutePath(),
                GeneratorMain.resolveConfiguredPath(configPath.toString(), config, "artifact", "root")
        );
        assertEquals(
                inputRoot.resolve("..").resolve("Output").resolve("App").normalize().toAbsolutePath(),
                GeneratorMain.resolveConfiguredPath(configPath.toString(), config, "finalExec", "root")
        );
        assertEquals(
                inputRoot.resolve("..").resolve("..").resolve("..").resolve("NPDevRuntimeHost").normalize().toAbsolutePath(),
                GeneratorMain.resolveConfiguredPath(configPath.toString(), config, "bootstrap", "root")
        );
    }
}
