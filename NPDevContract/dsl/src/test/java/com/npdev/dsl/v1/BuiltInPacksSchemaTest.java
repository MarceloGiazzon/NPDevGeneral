package com.npdev.dsl.v1;

import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every built-in pack ({@code NPDevContract/packs/*}) is composed the way an app author consumes it, and none of them
 * may fail the model/pack SCHEMA. A pack only meets the schema when it is composed, so a schema change that one pack
 * quietly violates (D-1: the tracing pack's screen named "Trace Viewer", with a space) passed every other test.
 * A pack that needs role bindings or other packs may still be refused by the composer for THAT reason; only a schema
 * refusal fails this test.
 */
class BuiltInPacksSchemaTest {

    @TempDir
    Path temp;

    @Test
    void noBuiltInPackFailsTheSchemaWhenComposed() throws Exception {
        Path packsRoot = Path.of("..", "packs").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(packsRoot), "built-in packs folder must exist at " + packsRoot);
        List<String> schemaFailures = new ArrayList<>();
        int composed = 0;
        try (Stream<Path> dirs = Files.list(packsRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path pack = dir.resolve("pack.json");
                if (!Files.isRegularFile(pack)) {
                    continue;
                }
                String id = dir.getFileName().toString();
                Path app = Files.createDirectories(temp.resolve(id));
                Files.writeString(Files.createDirectories(app.resolve("packs").resolve(id)).resolve("pack.json"),
                        Files.readString(pack));
                Path model = app.resolve("model.json");
                Files.writeString(model, """
                        { "namespace": "builtin.%s", "dslVersion": "3.0.0", "version": "1.0",
                          "packs": [ { "$ref": "packs/%s/pack.json", "as": "%s" } ] }
                        """.formatted(id.replace('-', '_'), id, id));
                composed++;
                try {
                    new JsonModelParser().parse(new ModelSourceResolver().resolve(model));
                } catch (Exception e) {
                    String message = String.valueOf(e.getMessage());
                    if (message.contains("schema validation failed")) {
                        schemaFailures.add(id + ": " + message);
                    }
                }
            }
        }
        assertTrue(composed >= 10, "expected to compose the built-in packs, composed " + composed);
        assertTrue(schemaFailures.isEmpty(), "built-in packs that fail the schema:\n" + String.join("\n", schemaFailures));
        assertFalse(schemaFailures.stream().anyMatch(s -> s.startsWith("tracing")), "tracing pack must pass the schema");
    }
}
