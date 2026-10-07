package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionPagesMenuEmitterTest {

    @Test
    void flattensMenuTreeThenAppendsPagesNotAlreadyPlaced(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("model.json");
        Files.writeString(model, "{}");
        Files.writeString(dir.resolve("menu.json"), """
                [ { "label": "Studio", "ordinal": 5, "children": [
                    { "label": "Paint", "kind": "PAGE", "target": "studio.html" },
                    { "label": "Mosaics", "target": "Mosaic" } ] } ]""");
        Files.writeString(dir.resolve("pages.json"), """
                [ { "path": "studio.html", "label": "Studio (dup)" },
                  { "path": "gallery.html", "label": "Gallery", "requiredRole": "ADMIN" },
                  { "path": "story.html", "label": "Story", "ordinal": 7 },
                  { "path": "about.html", "label": "About" } ]""");
        Path out = dir.resolve("out");

        new CompanionPagesMenuEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model);

        JsonNode rows = new ObjectMapper().readTree(out.resolve(CompanionPagesMenuEmitter.SEED_PATH).toFile());
        assertEquals(6, rows.size(), rows.toString());
        assertEquals("GROUP:Studio", rows.get(0).get("key").asText());
        assertEquals("GROUP", rows.get(0).get("kind").asText());
        assertEquals("GROUP:Studio/PAGE:studio.html", rows.get(1).get("key").asText());
        assertEquals("GROUP:Studio", rows.get(1).get("parentKey").asText());
        assertEquals("BUSINESS", rows.get(2).get("kind").asText());
        assertEquals("PAGE:gallery.html", rows.get(3).get("key").asText());
        assertEquals(1000, rows.get(3).get("ordinal").asInt());
        assertEquals("ADMIN", rows.get(3).get("requiredRole").asText());
        assertEquals(7, rows.get(4).get("ordinal").asInt());
        assertEquals(1020, rows.get(5).get("ordinal").asInt());
        assertTrue(rows.get(5).get("visible").asBoolean());
    }

    @Test
    void writesNothingWithoutPagesOrMenu(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("model.json");
        Files.writeString(model, "{}");
        Path out = dir.resolve("out");
        new CompanionPagesMenuEmitter(new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(model);
        assertFalse(Files.exists(out.resolve(CompanionPagesMenuEmitter.SEED_PATH)));
    }
}
