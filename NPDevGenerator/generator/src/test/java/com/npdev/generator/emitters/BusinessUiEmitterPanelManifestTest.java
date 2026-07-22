package com.npdev.generator.emitters;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Declared Panel Objects were previously validated/compiled but never surfaced anywhere in the
 * generated business UI's manifest (the actual documented gap: "the actual rendered UI is a
 * single generic renderer... not by declared Panel objects at all"). Proves the manifest now
 * carries enough for the renderer to build a dedicated nav entry + section per declared panel,
 * and that an app with no declared panels emits an empty (not absent/erroring) panels array.
 */
public class BusinessUiEmitterPanelManifestTest {

    private static Path writeModel(String json) throws IOException {
        Path modelPath = Files.createTempFile("npdev-panel-manifest-", ".json");
        Files.writeString(modelPath, json);
        return modelPath;
    }

    private static CompiledModel compile(String json) throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(writeModel(json)));
    }

    private static String emitAndReadManifest(CompiledModel model) throws Exception {
        Path out = Files.createTempDirectory("npdev-panel-manifest-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(model, "ADMIN", new SettingResolver(SettingStore.empty()));
        return Files.readString(out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json"));
    }

    @Test
    void declaredPanelAndItsActionAppearInTheManifest() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "panel.manifest.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Project",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true }
                      ]
                    }
                  ],
                  "procedures": [
                    {
                      "name": "CountProjects",
                      "steps": [
                        { "type": "listConcepts", "concept": "Project", "target": "projects" },
                        { "type": "return", "value": "$projects" }
                      ]
                    }
                  ],
                  "panels": [
                    {
                      "name": "ProjectsOverviewPanel",
                      "route": "projects-overview",
                      "title": "Projects Overview",
                      "dataSources": [ { "name": "projects", "concept": "Project" } ],
                      "layout": { "type": "table", "fields": ["projects"] },
                      "actions": [
                        { "name": "recount", "label": "Recount Projects", "binding": "procedure", "procedure": "CountProjects" }
                      ]
                    }
                  ]
                }
                """);

        String manifest = emitAndReadManifest(model);
        assertTrue(manifest.contains("\"panels\""), manifest);
        assertTrue(manifest.contains("\"name\" : \"ProjectsOverviewPanel\""), manifest);
        assertTrue(manifest.contains("\"route\" : \"projects-overview\""), manifest);
        assertTrue(manifest.contains("\"title\" : \"Projects Overview\""), manifest);
        assertTrue(manifest.contains("\"name\" : \"recount\""), manifest);
        assertTrue(manifest.contains("\"label\" : \"Recount Projects\""), manifest);
        assertTrue(manifest.contains("\"binding\" : \"procedure\""), manifest);
    }

    @Test
    void panelAndActionVisibilityEnabledWhenAppearInTheManifest() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "panel.manifest.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Project",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true }
                      ]
                    }
                  ],
                  "procedures": [
                    {
                      "name": "CountProjects",
                      "steps": [
                        { "type": "listConcepts", "concept": "Project", "target": "projects" },
                        { "type": "return", "value": "$projects" }
                      ]
                    }
                  ],
                  "panels": [
                    {
                      "name": "AdminOnlyPanel",
                      "route": "admin-only",
                      "title": "Admin Only",
                      "visibility": "isSuperUser",
                      "enabledWhen": "role == 'ADMIN'",
                      "dataSources": [ { "name": "projects", "concept": "Project" } ],
                      "actions": [
                        {
                          "name": "recount", "label": "Recount", "binding": "procedure", "procedure": "CountProjects",
                          "visibleWhen": "isSuperUser", "enabledWhen": "role == 'ADMIN'"
                        }
                      ]
                    }
                  ]
                }
                """);

        String manifest = emitAndReadManifest(model);
        assertTrue(manifest.contains("\"visibility\" : \"isSuperUser\""), manifest);
        assertTrue(manifest.contains("\"enabledWhen\" : \"role == 'ADMIN'\""), manifest);
        assertTrue(manifest.contains("\"visibleWhen\" : \"isSuperUser\""), manifest);
    }

    @Test
    void emptyPanelsArrayWhenModelDeclaresNone() throws Exception {
        CompiledModel model = compile("""
                {
                  "namespace": "panel.manifest.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Order",
                      "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ]
                    }
                  ]
                }
                """);

        String manifest = emitAndReadManifest(model);
        assertTrue(manifest.contains("\"panels\" : [ ]") || manifest.contains("\"panels\" : []"), manifest);
        assertFalse(manifest.contains("ProjectsOverviewPanel"), manifest);
    }
}
