package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginRequirementAssetEmitterTest {

    @Test
    void shouldEmitDeterministicPluginRequirementAssetFromModelSource() throws Exception {
        Path model = writeModel("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "TriggerEntity",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ],
                  "capabilities": [
                    {
                      "name": "persistence",
                      "type": "PersistenceCapability",
                      "operations": ["save"]
                    }
                  ],
                  "customCapabilities": [
                    {
                      "name": "customExtension",
                      "type": "CustomProcedureCapability",
                      "operations": [
                        {
                          "name": "run",
                          "input": { "conceptRef": "TriggerEntity" }
                        }
                      ]
                    }
                  ],
                  "bindings": [
                    { "capability": "customExtension", "adapter": "plugin:custom-procedure" }
                  ],
                  "flows": [
                    {
                      "name": "SubmitTrigger",
                      "concept": "TriggerEntity",
                      "steps": [
                        {
                          "name": "persist",
                          "type": "capability",
                          "capability": "persistence",
                          "operation": "save",
                          "input": "payload",
                          "output": "saved"
                        },
                        {
                          "name": "invoke-custom",
                          "type": "capability",
                          "capability": "customExtension",
                          "operation": "run",
                          "input": "saved",
                          "output": "result"
                        }
                      ]
                    }
                  ]
                }
                """);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path outOne = Files.createTempDirectory("npdev-plugin-req-1-");
        Path outTwo = Files.createTempDirectory("npdev-plugin-req-2-");
        Path migrationsOne = Files.createTempDirectory("npdev-plugin-req-migrations-1-");
        Path migrationsTwo = Files.createTempDirectory("npdev-plugin-req-migrations-2-");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");

        new GeneratorFacade(templates, new GeneratedSourceWriter(outOne, new RegenerationPolicy()))
                .generate(compiled, outOne, migrationsOne, model);
        new GeneratorFacade(templates, new GeneratedSourceWriter(outTwo, new RegenerationPolicy()))
                .generate(compiled, outTwo, migrationsTwo, model);

        Path emittedOne = outOne.resolve("src/main/resources/npdev/plugins/generated.plugin-requirements.json");
        Path emittedTwo = outTwo.resolve("src/main/resources/npdev/plugins/generated.plugin-requirements.json");

        assertTrue(Files.exists(emittedOne), "Expected generated plugin requirement asset");
        assertTrue(Files.readString(emittedOne).contains("\"customExtension\""),
                "Expected custom capability requirement in emitted asset");
        assertTrue(Files.readString(emittedOne).contains("\"externalCandidate\" : true")
                        || Files.readString(emittedOne).contains("\"externalCandidate\": true"),
                "Expected external candidate marker in emitted asset");
        assertArrayEquals(Files.readAllBytes(emittedOne), Files.readAllBytes(emittedTwo),
                "Expected deterministic plugin requirement asset emission");
    }

    private static Path writeModel(String json) throws Exception {
        Path modelFile = Files.createTempFile("npdev-plugin-asset-", ".json");
        Files.writeString(modelFile, json, StandardCharsets.UTF_8);
        return modelFile;
    }
}
