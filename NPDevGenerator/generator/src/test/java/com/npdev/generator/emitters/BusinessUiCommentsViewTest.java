package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** PROC-5: the Comments detail section ships verbatim, loads before app.js, and the manifest says which concepts have it. */
public class BusinessUiCommentsViewTest {

    private static final String STATIC = "src/main/resources/static/npdev-business-ui/";

    private static Path emit() throws Exception {
        Path modelPath = Files.createTempFile("npdev-comments-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "comments.demo", "dslVersion": "1.0.0", "version": "1.0",
                  "concepts": [
                    { "name": "Mosaic", "collaboration": { "comments": true, "attachments": false },
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true },
                                  { "name": "title", "type": "string" } ] },
                    { "name": "Plain",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """);
        CompiledModel model = new ModelCompiler().compile(new JsonModelParser().parse(modelPath));
        Path out = Files.createTempDirectory("npdev-comments-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return out;
    }

    @Test
    void commentsSectionIsEmittedVerbatimAndLoadedBetweenRegistryAndAppJs() throws Exception {
        Path out = emit();
        Path comments = out.resolve(STATIC + "views/comments.js");
        assertTrue(Files.exists(comments));
        assertEquals(new TemplateEngine("npdev-templates/").raw("static-assets/business-ui/views/comments.js"),
                Files.readString(comments));
        String index = Files.readString(out.resolve(STATIC + "index.html"));
        assertTrue(index.indexOf("./views/registry.js") < index.indexOf("./views/comments.js"));
        assertTrue(index.indexOf("./views/comments.js") < index.indexOf("./app.js"));
        assertTrue(Files.readString(out.resolve(STATIC + "views/views.css")).contains(".detail-comments"));
        assertFalse(Files.readString(comments).contains("innerHTML"), "comment text must only be written with textContent");
    }

    @Test
    void manifestCarriesCollaborationOnlyForTheConceptThatDeclaresIt() throws Exception {
        JsonNode manifest = new ObjectMapper().readTree(Files.readString(emit().resolve(STATIC + "generated-ui-manifest.json")));
        JsonNode mosaic = null;
        JsonNode plain = null;
        for (JsonNode concept : manifest.get("concepts")) {
            if ("Mosaic".equals(concept.get("conceptName").asText())) {
                mosaic = concept;
            } else if ("Plain".equals(concept.get("conceptName").asText())) {
                plain = concept;
            }
        }
        assertNotNull(mosaic);
        assertNotNull(plain);
        assertTrue(mosaic.get("collaboration").get("comments").asBoolean());
        assertFalse(mosaic.get("collaboration").get("attachments").asBoolean());
        assertFalse(plain.has("collaboration"));
    }
}
