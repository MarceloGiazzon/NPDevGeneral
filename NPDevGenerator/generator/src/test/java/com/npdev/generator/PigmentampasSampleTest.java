package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6 (NPDEV_MEGA_ROADMAP.md): pins the Pigmentampas sample -- the greenfield end-to-end proof app.
 * The model must parse, pass the semantic validator, compile with all seven concepts, and generate
 * without error through the real GeneratorFacade pipeline.
 */
class PigmentampasSampleTest {

    private static final Path SAMPLE_MODEL = Path.of(
            "..", "..", "NPDevSamples", "pigmentampas", "Input", "model.json").normalize();

    @Test
    void pigmentampasModelParsesValidatesAndGenerates() throws Exception {
        assertTrue(Files.exists(SAMPLE_MODEL),
                "Expected Pigmentampas sample model at: " + SAMPLE_MODEL.toAbsolutePath());

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(SAMPLE_MODEL);

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);

        CompiledConcept pigment = compiled.findConcept("Pigment").orElse(null);
        assertNotNull(pigment, "Pigment concept must compile");
        assertEquals(8, compiled.getConcepts().size(),
                "Pigmentampas must compile exactly eight concepts (pigment store domain + Credential for jwt-mode auth)");

        Path out = Files.createTempDirectory("npdev-pigmentampas-");
        Path migrations = Files.createTempDirectory("npdev-pigmentampas-migrations-");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, SAMPLE_MODEL);

        for (String entity : List.of("Pigment", "PigmentCategory", "Supplier", "PigmentSupplier",
                "StockEntry", "Order", "OrderItem")) {
            Path java = out.resolve("src/main/java/com/npdev/generated/entities/" + entity + ".java");
            assertTrue(Files.exists(java), "Expected generated entity: " + java);
        }
        Path uiManifest = out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json");
        assertTrue(Files.exists(uiManifest),
                "Expected generated business UI manifest: " + uiManifest);
        String manifest = Files.readString(uiManifest);
        for (String route : List.of("/pigments", "/categories", "/suppliers", "/pigment-suppliers",
                "/stock", "/orders", "/order-items")) {
            assertTrue(manifest.contains(route), "UI manifest must contain route " + route);
        }
    }
}