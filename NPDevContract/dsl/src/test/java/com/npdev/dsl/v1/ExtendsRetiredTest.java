package com.npdev.dsl.v1;

import com.npdev.dsl.v1.parser.DeprecationException;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-2 (owner, 2026-11-21): {@code extends} no longer exists in the DSL. On a concept it was an alias
 * of {@code specializes}; on a pack it declared "adds fields to another pack's concept", now
 * {@code augments}. One word, one meaning: a model still using the old word is refused with the
 * name of the replacement, never silently ignored.
 */
class ExtendsRetiredTest {

    @TempDir
    Path temp;

    @Test
    void conceptExtendsIsRefusedWithTheReplacementNamed() throws Exception {
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "3.0.0",
                  "version": "v1",
                  "concepts": [
                    { "name": "Base", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "Child", "extends": "Base",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """);

        DeprecationException refused = assertThrows(DeprecationException.class, () -> new JsonModelParser().parse(model));

        assertTrue(refused.getMessage().contains("'Child'"), refused.getMessage());
        assertTrue(refused.getMessage().contains("specializes"), refused.getMessage());
    }

    @Test
    void packExtendsIsRefusedWithTheReplacementNamed() throws Exception {
        write("packs/ext/pack.json", """
                {
                  "dslVersion": "3.0.0",
                  "pack": "ext",
                  "version": "1.0.0",
                  "extends": { "pack": "base", "concept": "Thing" },
                  "concepts": [
                    { "name": "Thing", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """);
        Path model = write("model.json", """
                {
                  "namespace": "demo",
                  "dslVersion": "3.0.0",
                  "version": "v1",
                  "packs": [ { "$ref": "packs/ext/pack.json" } ]
                }
                """);

        IOException refused = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));

        assertTrue(refused.getMessage().contains("augments"), refused.getMessage());
    }

    private Path write(String relative, String content) throws IOException {
        Path file = temp.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
