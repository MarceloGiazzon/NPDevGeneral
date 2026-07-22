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

class GeneratorApiVersioningContractTest {

    @Test
    void generatedRuntimeShouldUseV1CanonicalMappingsAndOpenApiEndpoints() throws Exception {
        Path model = Path.of("..", "test-models", "user-minimal", "model.json").normalize();
        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected semantic validation success: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-gen-v1-contract-");
        Path migrations = Files.createTempDirectory("npdev-gen-v1-migrations-");

        new GeneratorFacade(
                new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())
        ).generate(compiled, out, migrations, model);

        String flowController = Files.readString(out.resolve("src/main/java/com/npdev/generated/runtime/api/FlowExecutionController.java"));
        String executionController = Files.readString(out.resolve("src/main/java/com/npdev/generated/runtime/api/ExecutionQueryController.java"));
        String traceController = Files.readString(out.resolve("src/main/java/com/npdev/generated/runtime/api/TraceController.java"));
        String eventController = Files.readString(out.resolve("src/main/java/com/npdev/generated/runtime/api/EventIngestionController.java"));
        String correlationController = Files.readString(out.resolve("src/main/java/com/npdev/generated/runtime/api/CorrelationController.java"));
        String auditController = Files.readString(out.resolve("src/main/java/com/npdev/generated/runtime/api/AuditController.java"));
        String openApiController = Files.readString(out.resolve("src/main/java/com/npdev/generated/runtime/api/OpenApiController.java"));
        String runtimeConfig = Files.readString(out.resolve("src/main/java/com/npdev/generated/runtime/config/NPDevRuntimeConfig.java"));
        String authFilter = Files.readString(out.resolve("src/main/java/com/npdev/generated/runtime/config/RuntimeApiKeyAuthFilter.java"));
        String uiApp = Files.readString(out.resolve("src/main/resources/static/npdev-ui/app.js"));

        assertTrue(flowController.contains("@GetMapping({\"/v1/flows\", \"/flows/definitions\"})"));
        assertTrue(flowController.contains("@PostMapping({\"/v1/flows/{flowName}/execute\", \"/flows/{flowName}/execute\"})"));
        assertTrue(flowController.contains("@PostMapping({\"/v1/executions/{executionId}/resume\", \"/executions/{executionId}/resume\"})"));

        assertTrue(executionController.contains("@RequestMapping({\"/api/v1/executions\", \"/api/executions\"})"));
        assertTrue(traceController.contains("@RequestMapping({\"/api/v1/traces\", \"/api/traces\"})"));
        assertTrue(eventController.contains("@RequestMapping({\"/api/v1/events\", \"/api/events\"})"));
        assertTrue(correlationController.contains("@RequestMapping({\"/api/v1/correlations\", \"/api/correlations\"})"));
        assertTrue(auditController.contains("@RequestMapping({\"/api/v1/audit\", \"/api/audit\"})"));

        assertTrue(openApiController.contains("@GetMapping(value = \"/v3/api-docs\""));
        assertTrue(openApiController.contains("@GetMapping(value = \"/swagger-ui/index.html\""));

        assertTrue(runtimeConfig.contains("@Configuration"));
        assertTrue(runtimeConfig.contains("class NPDevRuntimeConfig"));
        assertTrue(authFilter.contains("uri.startsWith(\"/api/v1/\")"));
        assertTrue(authFilter.contains("api-dev=dev:developer:ADMIN;dev-key=dev:developer:ADMIN"));
        assertTrue(authFilter.contains("parsed.put(\"api-dev\""));

        // LNCH-3: the generated api-key filter must never clobber a request that an earlier filter in
        // the chain (e.g. the ControlPanel super-user filter) already authenticated -- neither by
        // overwriting its claims nor by rejecting it over a stray/invalid X-Api-Key. The guard mirrors
        // JwtBearerAuthFilter and must be emitted into every app, so pin it here in the generator gate.
        assertTrue(authFilter.contains("if (request.getAttribute(CLAIMS_ATTRIBUTE) != null)"),
                "RuntimeApiKeyAuthFilter must skip requests already authenticated by an earlier filter (LNCH-3 clobber guard)");
        int clobberGuardIdx = authFilter.indexOf("getAttribute(CLAIMS_ATTRIBUTE) != null");
        int apiKeyPresenceIdx = authFilter.indexOf("normalize(request.getHeader(\"X-Api-Key\"))");
        assertTrue(clobberGuardIdx > 0 && clobberGuardIdx < apiKeyPresenceIdx,
                "the clobber guard must precede the X-Api-Key presence check in shouldNotFilter");

        assertTrue(uiApp.contains("/api/v1/flows/"));
        assertTrue(uiApp.contains("/api/v1/executions/"));
        assertTrue(uiApp.contains("/api/v1/traces/"));
        assertTrue(uiApp.contains("/api/v1/events/publish"));
        assertTrue(uiApp.contains("/api/v1/correlations/"));
        assertTrue(uiApp.contains("DEFAULT_DEV_API_KEY = \"api-dev\""));

        assertFalse(uiApp.contains("/api/executions/summaries"));
        assertFalse(uiApp.contains("/api/events/publish\","));
    }
}
