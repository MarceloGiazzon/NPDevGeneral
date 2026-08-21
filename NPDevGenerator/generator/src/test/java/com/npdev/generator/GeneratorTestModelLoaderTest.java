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
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GeneratorTestModelLoaderTest {

    @Test
    void loadsTestModelAndGeneratesFiles() throws Exception {
        Path model = Path.of("..", "test-models", "user-minimal", "model.json").normalize();
        assertTrue(Files.exists(model), "Expected test model at: " + model.toAbsolutePath());

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-gen-");
        Path migrations = Files.createTempDirectory("npdev-migrations-");

        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, model);

        Path entity = out.resolve("src/main/java/com/npdev/generated/entities/User.java");
        Path repo = out.resolve("src/main/java/com/npdev/generated/repositories/UserRepository.java");
        Path service = out.resolve("src/main/java/com/npdev/generated/services/UserServiceBase.java");
        Path flowController = out.resolve("src/main/java/com/npdev/generated/runtime/api/FlowExecutionController.java");
        Path eventController = out.resolve("src/main/java/com/npdev/generated/runtime/api/EventIngestionController.java");
        Path traceController = out.resolve("src/main/java/com/npdev/generated/runtime/api/TraceController.java");
        Path executionQueryController = out.resolve("src/main/java/com/npdev/generated/runtime/api/ExecutionQueryController.java");
        Path eventQueryController = out.resolve("src/main/java/com/npdev/generated/runtime/api/EventQueryController.java");
        Path correlationController = out.resolve("src/main/java/com/npdev/generated/runtime/api/CorrelationController.java");
        Path auditController = out.resolve("src/main/java/com/npdev/generated/runtime/api/AuditController.java");
        Path adminController = out.resolve("src/main/java/com/npdev/generated/runtime/api/AdminController.java");
        Path uiRedirectController = out.resolve("src/main/java/com/npdev/generated/runtime/api/UiRedirectController.java");
        Path eventPublishRequest = out.resolve("src/main/java/com/npdev/generated/runtime/dto/EventPublishRequest.java");
        Path eventPublishResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/EventPublishResponse.java");
        Path executionQueryResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/ExecutionQueryResponse.java");
        Path executionSummaryResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/ExecutionSummaryResponse.java");
        Path eventQueryResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/EventQueryResponse.java");
        Path eventMetaSummaryResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/EventMetaSummaryResponse.java");
        Path traceSummaryResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/TraceSummaryResponse.java");
        Path correlationTimelineResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/CorrelationTimelineResponse.java");
        Path auditRecordResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/AuditRecordResponse.java");
        Path adminCircuitResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/AdminCircuitResponse.java");
        Path adminIdempotencyResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/AdminIdempotencyResponse.java");
        Path runtimeConfig = out.resolve("src/main/java/com/npdev/generated/runtime/config/NPDevRuntimeConfig.java");
        Path runtimeApiKeyAuthFilter = out.resolve("src/main/java/com/npdev/generated/runtime/config/RuntimeApiKeyAuthFilter.java");
        Path kernelFacade = out.resolve("src/main/java/com/npdev/generated/runtime/service/KernelFacade.java");
        Path runtimeContextService = out.resolve("src/main/java/com/npdev/generated/runtime/service/RuntimeContextService.java");
        Path flowDefinitionResponse = out.resolve("src/main/java/com/npdev/generated/runtime/dto/FlowDefinitionResponse.java");
        Path runtimeEventStoreAdapter = out.resolve("src/main/java/com/npdev/generated/runtime/adapters/InProcEventStoreAdapter.java");
        Path runtimeModel = out.resolve("src/main/resources/npdev/model.json");
        Path canonicalUiSelection = out.resolve("src/main/resources/npdev/ui-boundary/canonical-ui-selection.json");
        Path uiIndex = out.resolve("src/main/resources/static/npdev-ui/index.html");
        Path uiAppJs = out.resolve("src/main/resources/static/npdev-ui/app.js");
        Path uiStyle = out.resolve("src/main/resources/static/npdev-ui/style.css");
        Path runtimeActuatorProps = out.resolve("src/main/resources/npdev-runtime-actuator.properties");
        Path generatedSignature = out.resolve("src/main/resources/npdev/support/generated-folder.signature.properties");
        Path generatedBuildInfo = out.resolve("src/main/resources/npdev-build-info.properties");
        Path generatedStamp = out.resolve("src/main/resources/generated-stamp.properties");
        Path generatedBuildInfoJava = out.resolve("src/main/java/com/npdev/generated/meta/GeneratedBuildInfo.java");
        Path legacyRepeatableSchemaSql = migrations.resolve("R__npdev_schema.sql");
        Path generatedSchemaRealizationSql = migrations.resolve("V1__npdev_schema_realization.sql");
        Path packagedSchemaRealizationSql = out.resolve("src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql");

        assertTrue(Files.exists(entity), "Expected generated entity: " + entity);
        assertTrue(Files.notExists(repo),
                "Generated runtime should not emit direct Spring repositories when persistence is routed through ConceptStore/PersistenceCapability: " + repo);
        assertTrue(Files.exists(service), "Expected generated service: " + service);
        assertTrue(Files.exists(flowController), "Expected generated flow controller: " + flowController);
        assertTrue(Files.exists(eventController), "Expected generated event ingestion controller: " + eventController);
        assertTrue(Files.exists(traceController), "Expected generated trace controller: " + traceController);
        assertTrue(Files.exists(executionQueryController), "Expected generated execution query controller: " + executionQueryController);
        assertTrue(Files.exists(eventQueryController), "Expected generated event query controller: " + eventQueryController);
        assertTrue(Files.exists(correlationController), "Expected generated correlation controller: " + correlationController);
        assertTrue(Files.exists(auditController), "Expected generated audit controller: " + auditController);
        assertTrue(Files.exists(adminController), "Expected generated admin controller: " + adminController);
        assertTrue(Files.exists(uiRedirectController), "Expected generated UI redirect controller: " + uiRedirectController);
        assertTrue(Files.exists(eventPublishRequest), "Expected generated event publish request DTO: " + eventPublishRequest);
        assertTrue(Files.exists(eventPublishResponse), "Expected generated event publish response DTO: " + eventPublishResponse);
        assertTrue(Files.exists(executionQueryResponse), "Expected generated execution query response DTO: " + executionQueryResponse);
        assertTrue(Files.exists(executionSummaryResponse), "Expected generated execution summary response DTO: " + executionSummaryResponse);
        assertTrue(Files.exists(eventQueryResponse), "Expected generated event query response DTO: " + eventQueryResponse);
        assertTrue(Files.exists(eventMetaSummaryResponse), "Expected generated event meta summary response DTO: " + eventMetaSummaryResponse);
        assertTrue(Files.exists(traceSummaryResponse), "Expected generated trace summary response DTO: " + traceSummaryResponse);
        assertTrue(Files.exists(correlationTimelineResponse), "Expected generated correlation timeline response DTO: " + correlationTimelineResponse);
        assertTrue(Files.exists(auditRecordResponse), "Expected generated audit record response DTO: " + auditRecordResponse);
        assertTrue(Files.exists(adminCircuitResponse), "Expected generated admin circuit response DTO: " + adminCircuitResponse);
        assertTrue(Files.exists(adminIdempotencyResponse), "Expected generated admin idempotency response DTO: " + adminIdempotencyResponse);
        assertTrue(Files.exists(runtimeConfig), "Expected generated runtime config: " + runtimeConfig);
        assertTrue(Files.exists(runtimeApiKeyAuthFilter), "Expected generated API key auth filter: " + runtimeApiKeyAuthFilter);
        assertTrue(Files.exists(kernelFacade), "Expected generated kernel facade: " + kernelFacade);
        assertTrue(Files.exists(runtimeContextService), "Expected generated runtime context service: " + runtimeContextService);
        assertTrue(Files.exists(flowDefinitionResponse),
                "Expected generated flow definition response DTO: " + flowDefinitionResponse);
        assertTrue(!Files.exists(runtimeEventStoreAdapter),
                "Generated runtime must not create local InProcEventStore adapter: " + runtimeEventStoreAdapter);
        assertTrue(Files.exists(runtimeModel), "Expected projected model resource: " + runtimeModel);
        assertTrue(Files.exists(uiIndex), "Expected generated operator UI index: " + uiIndex);
        assertTrue(Files.exists(uiAppJs), "Expected generated operator UI script: " + uiAppJs);
        assertTrue(Files.exists(uiStyle), "Expected generated operator UI style: " + uiStyle);
        assertTrue(Files.exists(runtimeActuatorProps), "Expected generated runtime actuator properties: " + runtimeActuatorProps);
        assertTrue(Files.exists(generatedSignature), "Expected generated strict-execution signature manifest: " + generatedSignature);
        assertFalse(Files.exists(generatedBuildInfo),
                "Generated runtime must not leak build-info properties into the deterministic artifact root: "
                        + generatedBuildInfo);
        assertFalse(Files.exists(generatedStamp),
                "Generated runtime must not leak timestamp stamp properties into the deterministic artifact root: "
                        + generatedStamp);
        assertFalse(Files.exists(generatedBuildInfoJava),
                "Generated runtime must not leak timestamped metadata source into the deterministic artifact root: "
                        + generatedBuildInfoJava);
        assertFalse(Files.exists(legacyRepeatableSchemaSql),
                "Generator test-model output must not recreate legacy repeatable schema SQL authority: " + legacyRepeatableSchemaSql);
        assertFalse(Files.exists(generatedSchemaRealizationSql),
                "Generator test-model output must not create standalone schema SQL outside the source-of-truth pipeline: " + generatedSchemaRealizationSql);
        assertFalse(Files.exists(packagedSchemaRealizationSql),
                "Generator test-model output must not create packaged schema SQL outside the source-of-truth pipeline: " + packagedSchemaRealizationSql);

        String serviceContent = Files.readString(service);
        assertTrue(serviceContent.contains("runtimeSupport"),
                "Expected generated service to use the shared runtime support entrypoint for generated CRUD behavior");
        assertTrue(serviceContent.contains("GeneratedCrudRuntimeSupport"),
                "Expected generated service to keep CRUD behavior delegated through reusable runtime support");
        assertTrue(serviceContent.contains("GeneratedCrudRuntimeSupport"),
                "Expected generated service to delegate runtime concerns");
        assertTrue(serviceContent.contains("publishMutationEvent(\"created\""),
                "Expected generated service to publish created event");
        assertTrue(serviceContent.contains("ALLOWED_MUTATION_TOPICS"),
                "Expected generated service to keep an explicit whitelist for mutation topics");
        assertTrue(serviceContent.contains("Undeclared mutation event topic"),
                "Expected generated service to fail fast on undeclared mutation topics");
        assertTrue(serviceContent.contains("runtimeSupport.validateEntityDetailed("),
                "Expected generated service to delegate invariant checks");
        assertTrue(serviceContent.contains("PersistenceCapability<User, UUID>"),
                "Expected generated service to depend on persistence capability contract");
        assertTrue(serviceContent.contains("GeneratedCrudRuntimeSupport.persistenceCapability("),
                "Expected generated service to isolate persistence behind reusable capability factory");
        String runtimeConfigContent = Files.readString(runtimeConfig);
        String generatedSignatureContent = Files.readString(generatedSignature);
        assertTrue(runtimeConfigContent.contains("@Configuration"),
                "Expected generated runtime config marker");
        assertTrue(runtimeConfigContent.contains("class NPDevRuntimeConfig"),
                "Expected generated runtime config class");
        assertTrue(!runtimeConfigContent.contains("new KernelRunner("),
                "Generated runtime config must not construct kernel runner directly");
        assertTrue(!runtimeConfigContent.contains("com.npdev.adapters.events.inproc"),
                "Generated runtime config must not reference in-proc event adapter classes directly");
        assertTrue(!runtimeConfigContent.contains("com.npdev.adapters.expression.cel"),
                "Generated runtime config must not reference CEL adapter classes directly");
        assertTrue(generatedSignatureContent.contains("contract=npdev-generated-folder-signature-v1"),
                "Expected generated strict-execution signature contract marker");
        assertTrue(generatedSignatureContent.contains("treeSha256="),
                "Expected generated strict-execution signature tree hash");

        String kernelFacadeContent = Files.readString(kernelFacade);
        String flowDefinitionResponseContent = Files.readString(flowDefinitionResponse);
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ExecutionContext;"),
                "Expected kernel facade to use execution context");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.TraceRedactionPolicy;"),
                "Expected kernel facade to depend on redaction policy");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.TraceSummaryStore;"),
                "Expected kernel facade to depend on trace summary store");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.ExecutionAuthorizationPolicy;"),
                "Expected kernel facade to depend on execution authorization policy");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.ExecutionSummaryStore;"),
                "Expected kernel facade to depend on execution summary store");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.EventMetaStore;"),
                "Expected kernel facade to depend on event meta store");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.FlowInstanceStore;"),
                "Expected kernel facade to load flow instances for resume authorization");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.EventStore;"),
                "Expected kernel facade to load events via tenant-scoped read methods");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.audit.AuditRecord;"),
                "Expected kernel facade to use audit records");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.AuditLogStore;"),
                "Expected kernel facade to depend on audit log store");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.MetricsSink;"),
                "Expected kernel facade to depend on metrics sink");
        assertTrue(flowDefinitionResponseContent.contains("String mode"),
                "Expected flow definition response DTO to expose flow mode");
        assertTrue(flowDefinitionResponseContent.contains("List<FlowStepView> steps"),
                "Expected flow definition response DTO to expose typed flow step previews");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.CircuitBreakerStateStore;"),
                "Expected kernel facade to depend on circuit breaker state store");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.IdempotencyStore;"),
                "Expected kernel facade to depend on idempotency store");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.AuditQuery;"),
                "Expected kernel facade to use audit query contract");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.CorrelationOwnershipViolationException;"),
                "Expected kernel facade to map correlation ownership violations to forbidden");
        assertTrue(kernelFacadeContent.contains("catch (CorrelationOwnershipViolationException ignored)"),
                "Expected kernel facade to translate ownership violation to forbidden");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.EventRedactionPolicy;"),
                "Expected kernel facade to apply event redaction on read APIs");
        assertTrue(kernelFacadeContent.contains("import com.npdev.kernel.ports.ExecutionRedactionPolicy;"),
                "Expected kernel facade to apply execution redaction on read APIs");
        assertTrue(kernelFacadeContent.contains("traceRedactionPolicy.redact"),
                "Expected kernel facade to apply redaction before returning traces");
        assertTrue(kernelFacadeContent.contains("tenantScopedTraceQuery("),
                "Expected kernel facade to enforce tenant-scoped trace searches");
        assertTrue(kernelFacadeContent.contains("canResumeExecution"),
                "Expected kernel facade to enforce resume authorization checks");
        assertTrue(kernelFacadeContent.contains("listExecutions("),
                "Expected kernel facade to expose execution query APIs");
        assertTrue(kernelFacadeContent.contains("listExecutionSummaries("),
                "Expected kernel facade to expose execution summary query API");
        assertTrue(kernelFacadeContent.contains("listStaleWaitingExecutions("),
                "Expected kernel facade to expose stale waiting execution query API");
        assertTrue(kernelFacadeContent.contains("listEventsByCorrelation("),
                "Expected kernel facade to expose event query APIs");
        assertTrue(kernelFacadeContent.contains("searchTraceSummaries("),
                "Expected kernel facade to expose trace summary query API");
        assertTrue(kernelFacadeContent.contains("correlationTimeline("),
                "Expected kernel facade to expose correlation timeline API");
        assertTrue(kernelFacadeContent.contains("listFlowDefinitions()"),
                "Expected kernel facade to expose typed flow definition list API");
        assertTrue(kernelFacadeContent.contains("CorrelationTimelineResponse correlationTimelineResponse("),
                "Expected kernel facade to expose correlation timeline response API");
        assertTrue(kernelFacadeContent.contains("auditLogStore.append"),
                "Expected kernel facade to append audit records");
        assertTrue(kernelFacadeContent.contains("searchAudit(AuditQuery query, ExecutionContext requesterContext)"),
                "Expected kernel facade to expose audit query API");
        assertTrue(kernelFacadeContent.contains("listRecentFailureExecutions("),
                "Expected kernel facade to expose recent failures admin API");
        assertTrue(kernelFacadeContent.contains("listRecentStuckExecutions("),
                "Expected kernel facade to expose recent stuck admin API");
        assertTrue(kernelFacadeContent.contains("listCircuitStates("),
                "Expected kernel facade to expose circuit states admin API");
        assertTrue(kernelFacadeContent.contains("findIdempotencyRecord("),
                "Expected kernel facade to expose idempotency lookup admin API");
        assertTrue(kernelFacadeContent.contains("canReadAudit"),
                "Expected kernel facade to enforce audit read authorization");
        assertTrue(kernelFacadeContent.contains("canReadFailures"),
                "Expected kernel facade to enforce failure read authorization");
        assertTrue(kernelFacadeContent.contains("canReadAdminOps"),
                "Expected kernel facade to enforce admin operations authorization");
        assertTrue(kernelFacadeContent.contains("OUTCOME_ALLOW"),
                "Expected kernel facade to emit allow audit outcomes");
        assertTrue(kernelFacadeContent.contains("OUTCOME_DENY"),
                "Expected kernel facade to emit deny audit outcomes");
        assertTrue(kernelFacadeContent.contains("audit(ctx, \"EXECUTE_FLOW\""),
                "Expected kernel facade to audit execute flow decisions");
        assertTrue(kernelFacadeContent.contains("npdev.api.execute.started"),
                "Expected kernel facade to emit execute API metrics");
        assertTrue(kernelFacadeContent.contains("npdev.api.trace.search"),
                "Expected kernel facade to emit trace search API metrics");
        assertTrue(kernelFacadeContent.contains("npdev.api.audit.search"),
                "Expected kernel facade to emit audit search API metrics");
        assertTrue(kernelFacadeContent.contains("npdev.api.event.read"),
                "Expected kernel facade to emit event read/list API metrics");
        assertTrue(kernelFacadeContent.contains("npdev.api.correlation.timeline"),
                "Expected kernel facade to emit correlation timeline API metrics");

        String flowControllerContent = Files.readString(flowController);
        assertTrue(flowControllerContent.contains("RuntimeContextService"),
                "Expected generated flow controller to depend on runtime context service");
        assertTrue(flowControllerContent.contains("runtimeContextService.currentContext(request)"),
                "Expected generated flow controller to resolve context from authenticated request");
        assertTrue(flowControllerContent.contains("resumeExecution("),
                "Expected generated flow controller to expose resume endpoint");
        assertTrue(flowControllerContent.contains("@GetMapping({\"/v1/flows\", \"/flows/definitions\"})"),
                "Expected generated flow controller to expose typed flow definitions endpoint");
        assertTrue(flowControllerContent.contains("HttpServletRequest request"),
                "Expected generated flow controller endpoints to accept servlet request");

        String eventControllerContent = Files.readString(eventController);
        assertTrue(eventControllerContent.contains("RuntimeContextService"),
                "Expected generated event controller to depend on runtime context service");
        assertTrue(eventControllerContent.contains("runtimeContextService.currentContext(httpRequest)"),
                "Expected generated event controller to resolve context from authenticated request");

        String traceControllerContent = Files.readString(traceController);
        assertTrue(traceControllerContent.contains("@GetMapping"),
                "Expected generated trace controller to expose query endpoint");
        assertTrue(traceControllerContent.contains("public List<FlowTrace> searchTraces("),
                "Expected generated trace controller to support trace search API");
        assertTrue(traceControllerContent.contains("public List<TraceSummaryResponse> searchTraceSummaries("),
                "Expected generated trace controller to expose trace summaries endpoint");
        assertTrue(traceControllerContent.contains("runtimeContextService.currentContext(request)"),
                "Expected generated trace controller to resolve context from authenticated request");
        assertTrue(traceControllerContent.contains("kernelFacade.searchTraceSummaries("),
                "Expected generated trace controller summaries endpoint to use summary store path");

        String executionQueryControllerContent = Files.readString(executionQueryController);
        assertTrue(executionQueryControllerContent.contains("@RequestMapping({\"/api/v1/executions\", \"/api/executions\"})"),
                "Expected generated execution query controller base path");
        assertTrue(executionQueryControllerContent.contains("listWaitingExecutions"),
                "Expected generated execution query controller waiting endpoint");
        assertTrue(executionQueryControllerContent.contains("listExecutionSummaries"),
                "Expected generated execution query controller summaries endpoint");
        assertTrue(executionQueryControllerContent.contains("kernelFacade.listExecutionSummaries("),
                "Expected generated execution query summaries endpoint to use summary store path");
        assertTrue(executionQueryControllerContent.contains("@GetMapping(\"/stale\")"),
                "Expected generated execution query controller stale endpoint");
        assertTrue(executionQueryControllerContent.contains("listStaleWaitingExecutions"),
                "Expected generated execution query controller stale endpoint method");

        String eventQueryControllerContent = Files.readString(eventQueryController);
        assertTrue(eventQueryControllerContent.contains("/events/by-correlation/{correlationId}"),
                "Expected generated event query controller correlation endpoint");
        assertTrue(eventQueryControllerContent.contains("/correlations/{correlationId}/timeline"),
                "Expected generated event query controller timeline endpoint");

        String correlationControllerContent = Files.readString(correlationController);
        assertTrue(correlationControllerContent.contains("@RequestMapping({\"/api/v1/correlations\", \"/api/correlations\"})"),
                "Expected generated correlation controller base path");
        assertTrue(correlationControllerContent.contains("@GetMapping(\"/{correlationId}\")"),
                "Expected generated correlation controller GET mapping");
        assertTrue(correlationControllerContent.contains("ResponseEntity<CorrelationTimelineResponse>"),
                "Expected generated correlation controller to return ResponseEntity");
        assertTrue(correlationControllerContent.contains("RuntimeContextService"),
                "Expected generated correlation controller to depend on runtime context service");
        assertTrue(correlationControllerContent.contains("runtimeContextService.currentContext(request)"),
                "Expected generated correlation controller to resolve context from authenticated request");
        assertTrue(correlationControllerContent.contains("kernelFacade.correlationTimeline("),
                "Expected generated correlation controller to delegate to kernel facade");

        String auditControllerContent = Files.readString(auditController);
        assertTrue(auditControllerContent.contains("@RequestMapping({\"/api/v1/audit\", \"/api/audit\"})"),
                "Expected generated audit controller base path");
        assertTrue(auditControllerContent.contains("kernelFacade.searchAudit"),
                "Expected generated audit controller to delegate to kernel facade audit API");
        assertTrue(auditControllerContent.contains("RuntimeContextService"),
                "Expected generated audit controller to resolve authenticated execution context");

        String adminControllerContent = Files.readString(adminController);
        assertTrue(adminControllerContent.contains("@RequestMapping({\"/api/v1/admin\", \"/api/admin\"})"),
                "Expected generated admin controller base path");
        assertTrue(adminControllerContent.contains("/failures/recent"),
                "Expected generated admin controller failures endpoint");
        assertTrue(adminControllerContent.contains("/stuck/recent"),
                "Expected generated admin controller stuck endpoint");
        assertTrue(adminControllerContent.contains("/circuits"),
                "Expected generated admin controller circuits endpoint");
        assertTrue(adminControllerContent.contains("/idempotency"),
                "Expected generated admin controller idempotency endpoint");
        assertTrue(adminControllerContent.contains("/executions/{executionId}/resume"),
                "Expected generated admin controller resume shortcut endpoint");
        assertTrue(adminControllerContent.contains("/model/export"),
                "Expected generated admin controller model export endpoint");
        assertTrue(adminControllerContent.contains("RuntimeContextService"),
                "Expected generated admin controller to resolve authenticated execution context");

        String uiRedirectControllerContent = Files.readString(uiRedirectController);
        assertTrue(uiRedirectControllerContent.contains("@GetMapping({\"/npdev-ui\", \"/npdev-ui/\"})"),
                "Expected generated UI redirect controller to map /npdev-ui and /npdev-ui/");
        assertTrue(uiRedirectControllerContent.contains("redirect:/npdev-ui/index.html"),
                "Expected generated UI redirect controller root redirect to follow canonical UI selection");
        String uiIndexContent = Files.readString(uiIndex);
        assertTrue(uiIndexContent.contains("/npdev-ui/app.js"),
                "Expected generated UI index to reference generated script");
        assertTrue(uiIndexContent.contains("Current canonical operator UI: /npdev-ui/"),
                "Expected generated UI index to include canonical route from UI selection");
        assertTrue(uiIndexContent.contains("canonical-ui-selection.json"),
                "Expected generated UI index to mention canonical selection control");
        assertTrue(uiIndexContent.contains("Execute Flow"),
                "Expected generated UI index to include execute flow view");
        assertTrue(uiIndexContent.contains("data-view=\"admin\""),
                "Expected generated UI index to include admin tab view");
        assertTrue(uiIndexContent.contains("Copy Trace Summary"),
                "Expected generated UI index to include trace summary copy action");
        assertTrue(uiIndexContent.contains("adminFailuresTable"),
                "Expected generated UI index to include admin failures operator table");

        String uiAppContent = Files.readString(uiAppJs);
        assertTrue(uiAppContent.contains("/api/v1/flows"),
                "Expected generated UI script to call typed flow list API");
        assertTrue(uiAppContent.contains("/api/v1/correlations/"),
                "Expected generated UI script to call correlation timeline API");
        assertTrue(uiAppContent.contains("X-Api-Key"),
                "Expected generated UI script to forward API key header");
        assertTrue(uiAppContent.contains("buildInfoFooter"),
                "Expected generated UI script to wire the build info footer");
        assertTrue(uiAppContent.contains("Unauthorized (check API key)."),
                "Expected generated UI script to surface API key auth failures");
        assertTrue(uiAppContent.contains("state.executionsMode === \"waiting\" ? \"waiting\" : \"recent\""),
                "Expected generated UI script to include waiting/recent mode switch for summaries");
        assertTrue(uiAppContent.contains("/api/v1/executions/stale?olderThanMinutes="),
                "Expected generated UI script to support stale waiting endpoint");
        assertTrue(uiAppContent.contains("/api/v1/executions/summaries"),
                "Expected generated UI script to use execution summaries endpoint");

        assertTrue(uiAppContent.contains("/api/v1/admin/failures/recent"),
                "Expected generated UI script to probe admin failures endpoint");
        assertTrue(uiAppContent.contains("/api/v1/admin/circuits"),
                "Expected generated UI script to support admin circuits endpoint");
        assertTrue(uiAppContent.contains("/api/v1/admin/executions/"),
                "Expected generated UI script to support admin execution resume shortcut endpoint");
        assertTrue(uiAppContent.contains("/api/v1/events/publish"),
                "Expected generated UI script to support external event publish");
        assertTrue(uiAppContent.contains("/api/v1/traces/summaries?"),
                "Expected generated UI script to use trace summaries endpoint");
        assertTrue(uiAppContent.contains("/api/v1/executions/summaries"),
                "Expected generated UI script to use execution summaries endpoint");

        String actuatorPropsContent = Files.readString(runtimeActuatorProps);
        assertTrue(actuatorPropsContent.contains("management.endpoints.web.exposure.include=health,info,metrics"),
                "Expected generated actuator defaults to expose metrics");
        assertTrue(actuatorPropsContent.contains("management.endpoint.metrics.enabled=true"),
                "Expected generated actuator defaults to enable metrics endpoint");
        assertTrue(actuatorPropsContent.contains("management.info.env.enabled=true"),
                "Expected generated actuator defaults to enable env-backed info details");

    }

    @Test
    void removesObsoleteRuntimeEventStoreAdapterOnNonCleanGeneration() throws Exception {
        Path model = Path.of("..", "test-models", "user-minimal", "model.json").normalize();
        assertTrue(Files.exists(model), "Expected test model at: " + model.toAbsolutePath());

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-gen-stale-");
        Path stale = out.resolve("src/main/java/com/npdev/generated/runtime/adapters/InProcEventStoreAdapter.java");
        Files.createDirectories(stale.getParent());
        Files.writeString(stale, "// stale", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        assertTrue(Files.exists(stale), "Expected stale file to exist before generation");

        Path migrations = Files.createTempDirectory("npdev-migrations-stale-");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, model);

        assertTrue(!Files.exists(stale),
                "Expected generation to remove obsolete runtime adapter even without full clean");
    }
}
