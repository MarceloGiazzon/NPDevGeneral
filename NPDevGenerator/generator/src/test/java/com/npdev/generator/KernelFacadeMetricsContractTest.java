package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KernelFacadeMetricsContractTest {

    @Test
    void generatedKernelFacadeShouldEmitRequiredApiBoundaryMetricsWithoutHighCardinalityTags() throws Exception {
        Path model = Path.of("..", "test-models", "user-minimal", "model.json").normalize();
        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected semantic validation success: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-gen-facade-metrics-");
        Path migrations = Files.createTempDirectory("npdev-gen-facade-metrics-migrations-");

        new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())
        ).generate(compiled, out, migrations, model);

        String facade = Files.readString(out.resolve("src/main/java/com/npdev/generated/runtime/service/KernelFacade.java"));

        assertTrue(facade.contains("private void metricInc("));
        assertTrue(facade.contains("private <T> T metricTimed("));
        assertTrue(facade.contains("private static String metricErrorCode("));
        assertTrue(facade.contains("npdev.api.execute.started"));
        assertTrue(facade.contains("npdev.api.execute.completed"));
        assertTrue(facade.contains("npdev.api.execute.failed"));
        assertTrue(facade.contains("npdev.api.resume.attempted"));
        assertTrue(facade.contains("npdev.api.resume.failed"));
        assertTrue(facade.contains("npdev.api.event.publish"));
        assertTrue(facade.contains("npdev.api.trace.read"));
        assertTrue(facade.contains("npdev.api.trace.search"));
        assertTrue(facade.contains("npdev.api.execution.list"));
        assertTrue(facade.contains("npdev.api.correlation.timeline"));
        assertTrue(facade.contains("npdev.api.audit.search"));

        // Prevent accidental high-cardinality tags in metric calls.
        assertFalse(facade.contains("metricInc(\"npdev.api.trace.read\", requester, Map.of(\"executionId\""));
        assertFalse(facade.contains("metricInc(\"npdev.api.execution.read\", requester, Map.of(\"executionId\""));
        assertFalse(facade.contains("metricInc(\"npdev.api.event.read\", requester, Map.of(\"correlationId\""));
        assertFalse(facade.contains("metricInc(\"npdev.api.event.read\", requester, Map.of(\"eventId\""));
        assertFalse(facade.contains("metricInc(\"npdev.api.event.publish\", ctx, Map.of(\"eventName\""));
        assertFalse(facade.contains("metricInc(\"npdev.api.correlation.timeline\", requester, Map.of(\"correlationId\""));
    }
}
