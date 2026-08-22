package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledCapabilityCall;
import com.npdev.dsl.v1.compiled.CompiledCapabilityExecutionPolicy;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledGeneratedActionDescriptorSpec;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TrustedSourceEmitterGeneratedActionTest {
    @TempDir
    Path tempDir;

    @Test
    void trustedProcedureGenerationIncludesKernelBackedActionSlice() throws Exception {
        Path outputRoot = emitTrustedActionApp();
        Path trustedRoot = outputRoot.resolve("src/main/java/com/npdev/generated/trusted");

        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedActionDescriptor.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedActionRegistry.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedActionExecutionRequest.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedActionExecutionResponse.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedActionCapabilityRequest.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedActionCapabilityResult.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedActionCapabilityAdapter.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedActionCapabilityDispatcherFactory.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedActionKernelRunner.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedActionCapabilityRegistryContributor.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedFlowDescriptor.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedFlowRegistry.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedFlowExecutionRequest.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedFlowExecutionResponse.java")));
        assertTrue(Files.isRegularFile(trustedRoot.resolve("GeneratedFlowCodaRunner.java")));

        String registry = Files.readString(trustedRoot.resolve("GeneratedActionRegistry.java"));
        assertTrue(registry.contains("new GeneratedActionDescriptor(\"CreateUser\", \"CreateUser\", \"ADMIN\", true"));
        assertTrue(registry.contains("List.of(\"User\")"));
        assertTrue(registry.contains("\"generated.action.create-user.completed\""));
        assertTrue(registry.contains("\"GENERATED_ACTION\""));
        assertTrue(registry.contains("\"record\""));
        assertTrue(registry.contains("\"claim\""));
        assertTrue(registry.contains("\"generated.action.CreateUser\""));
        assertTrue(registry.contains("context -> new CreateUserProcedure().execute(context)"),
                "Trusted procedure handler must be owned by the generated action registry");

        String adapter = Files.readString(trustedRoot.resolve("GeneratedActionCapabilityAdapter.java"));
        assertTrue(adapter.contains("implements CapabilityAdapter"));
        assertTrue(adapter.contains("request.descriptor().handler().invoke(request.procedureContext())"),
                "Generated action provider must be the bridge that invokes the registry-owned handler");
        assertTrue(adapter.contains("requestFromKernelFlow("),
                "Generated action adapter must bridge KernelRunner capability calls into registry handlers");

        String contributor = Files.readString(trustedRoot.resolve("GeneratedActionCapabilityRegistryContributor.java"));
        assertTrue(contributor.contains("implements SmartInitializingSingleton"));
        assertTrue(contributor.contains("capabilityRegistry.register("));
        assertTrue(contributor.contains("GeneratedActionRegistry.all()"));

        String factory = Files.readString(trustedRoot.resolve("GeneratedActionCapabilityDispatcherFactory.java"));
        assertTrue(factory.contains("new RegistryCapabilityDispatcher(registry)"));
        assertTrue(factory.contains("descriptor.capabilityId()"));
        assertTrue(factory.contains("DISPATCHER_INVOCATIONS"));

        String controller = Files.readString(trustedRoot.resolve("GeneratedTrustedSourceRuntimeController.java"));
        assertTrue(controller.contains("/generated/actions/{actionName}/run"));
        assertTrue(controller.contains("/generated/flows/{flowName}/start"));
        assertTrue(controller.contains("/generated/flows/{flowName}/events/{eventName}"),
                "Generated flow event endpoint must be exposed for waiting/resume slice");
        assertTrue(controller.contains("/generated/flows/{flowName}/resume"),
                "Generated flow resume endpoint must be exposed for waiting/resume slice");
        assertTrue(controller.contains("actionKernelRunner.run("),
                "Controller endpoints must enter GeneratedActionKernelRunner");
        assertTrue(controller.contains("flowCodaRunner.start("),
                "Generated flow endpoint must enter GeneratedFlowCodaRunner");
        assertTrue(controller.contains("flowCodaRunner.publishEventAndResume("),
                "Generated flow resume endpoints must enter GeneratedFlowCodaRunner");
        assertFalse(controller.contains("new CreateUserProcedure().execute"),
                "Controller must not directly invoke trusted procedures");
        assertTrue(controller.contains("/generated/procedures/{procedureName}"),
                "Compatibility procedure endpoint must remain");
        assertTrue(controller.contains("GeneratedActionExecutionRequest.from(body)"));
        assertTrue(controller.contains("/generated/actions/executions/{executionId}"),
                "Generated evidence viewer must expose execution lookup");
        assertTrue(controller.contains("/generated/actions/correlations/{correlationId}"),
                "Generated evidence viewer must expose correlation lookup");
        assertTrue(controller.contains("kernelFacade.generatedActionEvidenceByExecution(executionId, context)"));
        assertTrue(controller.contains("kernelFacade.generatedActionEvidenceByCorrelation(correlationId, context)"));

        String response = Files.readString(trustedRoot.resolve("GeneratedActionExecutionResponse.java"));
        assertTrue(response.contains("String executionId"));
        assertTrue(response.contains("String correlationId"));
        assertTrue(response.contains("String capabilityId"));
        assertTrue(response.contains("String capabilityDispatchStatus"));
        assertTrue(response.contains("out.put(\"capabilityId\", capabilityId)"));
        assertTrue(response.contains("out.put(\"capabilityDispatchStatus\", capabilityDispatchStatus)"));
        assertTrue(response.contains("String eventStatus"));
        assertTrue(response.contains("String auditStatus"));
        assertTrue(response.contains("String traceStatus"));
        assertTrue(response.contains("String idempotencyStatus"));
        assertTrue(response.contains("String correlationStatus"));

        String runner = Files.readString(trustedRoot.resolve("GeneratedActionKernelRunner.java"));
        assertTrue(runner.contains("KernelFacade"));
        assertTrue(runner.contains("CapabilityDispatcher"));
        assertTrue(runner.contains("CapabilityCall"));
        assertTrue(runner.contains("capabilityDispatcher.invoke("));
        assertTrue(runner.contains("dispatched: capabilityId="));
        assertTrue(runner.contains("prevented: idempotency reused before capability dispatch"));
        assertFalse(runner.contains("descriptor.handler().invoke"),
                "GeneratedActionKernelRunner must dispatch instead of invoking the registry handler directly");
        assertTrue(runner.contains("kernelFacade.publishExternalEvent"));
        assertTrue(runner.contains("descriptor.sideEffectConcept()"),
                "Side-effect counting must be descriptor-driven");
        assertFalse(runner.contains("DEFAULT_SIDE_EFFECT_CONCEPT"),
                "Runner must not use a hardcoded side-effect concept constant");
        assertTrue(runner.contains("conceptGateway.save("));
        assertTrue(runner.contains("claimGeneratedActionCorrelation"));
        assertTrue(runner.contains("findGeneratedActionIdempotency"));
        assertTrue(runner.contains("recordGeneratedActionAudit"));
        assertTrue(runner.contains("writeGeneratedActionTrace"));
        assertTrue(runner.contains("recordGeneratedActionIdempotencySuccess"));

        String flowRegistry = Files.readString(trustedRoot.resolve("GeneratedFlowRegistry.java"));
        assertTrue(flowRegistry.contains("new GeneratedFlowDescriptor(\"CreateUserFlow\", \"CreateUser\")"));

        String flowRunner = Files.readString(trustedRoot.resolve("GeneratedFlowCodaRunner.java"));
        assertTrue(flowRunner.contains("kernelFacade.executeFlow("),
                "Generated flow runner must delegate to KernelFacade.executeFlow");
        assertTrue(flowRunner.contains("kernelFacade.findExecution("),
                "Generated flow runner must read persisted FlowInstance evidence through KernelFacade");
        assertTrue(flowRunner.contains("kernelFacade.publishExternalEvent("),
                "Generated flow runner must publish resume events through KernelFacade for waiting/resume");
        assertTrue(flowRunner.contains("WAITING_EVENT"),
                "Generated flow runner must expose truthful waiting status for await-event flows");
        assertTrue(flowRunner.contains("waiting: KernelRunner persisted WAITING_EVENT before generated action dispatch"),
                "Generated flow runner must not overclaim action dispatch while waiting");
        assertTrue(flowRunner.contains("resumed: external event -> KernelRunner.resumeExecution -> CapabilityDispatcher"),
                "Generated flow resume path must prove event-driven resume through KernelRunner and CapabilityDispatcher");
        assertTrue(flowRunner.contains("countSideEffects("),
                "Generated flow runner must measure business side effects around kernel execution");
        assertFalse(flowRunner.contains("FlowInstance.start("),
                "Generated flow runner must not create controller-local/generated-only flow status");
    }

    @Test
    void explicitDescriptorWithoutSideEffectConceptDoesNotGenerateInferredConceptCounting() throws Exception {
        Path outputRoot = emitTrustedActionApp(false);
        Path trustedRoot = outputRoot.resolve("src/main/java/com/npdev/generated/trusted");

        String registry = Files.readString(trustedRoot.resolve("GeneratedActionRegistry.java"));
        assertTrue(registry.contains("List.of(),"));
        assertTrue(registry.contains("\"\","));
        assertFalse(registry.contains("\"User\","),
                "Explicit descriptors without sideEffectConcept must not infer User from CreateUser");

        String runner = Files.readString(trustedRoot.resolve("GeneratedActionKernelRunner.java"));
        assertTrue(runner.contains("disabled: descriptor sideEffectConcept is not declared"));
    }

    @Test
    void generatedTrustedActionJavaCompilesAndProcedureEndpointDelegatesThroughRunner() throws Exception {
        Path outputRoot = emitTrustedActionApp();
        writeGeneratedAppValidationStubs(outputRoot);

        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(outputRoot.resolve("src/main/java"))) {
            sources = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "Generated app validation requires a JDK compiler");
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            List<String> options = List.of("-d", classesDir.toString(), "-classpath", System.getProperty("java.class.path"));
            Boolean ok = compiler.getTask(
                    null,
                    fileManager,
                    null,
                    options,
                    null,
                    fileManager.getJavaFileObjectsFromPaths(sources)
            ).call();
            assertTrue(Boolean.TRUE.equals(ok), "Generated trusted-source Java must compile in the temp app harness");
        }

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()},
                TrustedSourceEmitterGeneratedActionTest.class.getClassLoader()
        )) {
            Class<?> harness = Class.forName("validation.GeneratedActionEndpointHarness", true, loader);
            harness.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        }
    }

    private Path emitTrustedActionApp() throws Exception {
        return emitTrustedActionApp(true);
    }

    private Path emitTrustedActionApp(boolean includeSideEffectConcept) throws Exception {
        Path modelRoot = tempDir.resolve("model");
        Path outputRoot = tempDir.resolve("out");
        Files.createDirectories(modelRoot.resolve("trusted"));

        Path trustedProcedure = modelRoot.resolve("trusted/CreateUserProcedure.java");
        Files.writeString(trustedProcedure, """
                import java.util.List;
                import java.util.Map;

                public class CreateUserProcedure {
                    public static int invocationCount = 0;

                    public Map<String, Object> execute(NPDevProcedureContext context) {
                        invocationCount++;
                        List<Map<String, Object>> saved = context.saveMany(
                                "User",
                                List.of(Map.of("id", "user-1", "name", "Ada"))
                        );
                        return Map.of("handlerInvoked", true, "savedCount", saved.size());
                    }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(modelRoot.resolve("trusted-source-manifest.json"), """
                {
                  "schemaVersion": "npdev-trusted-source-manifest.v1",
                  "entries": [
                    {
                      "entryId": "create-user",
                      "kind": "procedure",
                      "relativePath": "trusted/CreateUserProcedure.java",
                      "language": "java",
                      "sha256": "%s",
                      "runtimeBinding": "procedure:CreateUser",
                      "className": "CreateUserProcedure",
                      "method": "execute",
                      "requiredRole": "ADMIN",
                      "tenantScoped": true
                    }
                  ]
                }
                """.formatted(sha256(trustedProcedure)), StandardCharsets.UTF_8);
        Path modelSource = modelRoot.resolve("model.json");
        Files.writeString(modelSource, "{}", StandardCharsets.UTF_8);

        CompiledProcedure procedure = new CompiledProcedure(
                "CreateUser",
                "Creates a user through trusted source",
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("ADMIN"),
                "detailed",
                new CompiledGeneratedActionDescriptorSpec(
                        "CreateUser",
                        includeSideEffectConcept ? List.of("User") : List.of(),
                        includeSideEffectConcept ? "User" : null,
                        "generated.action.create-user.completed",
                        "GENERATED_ACTION",
                        "record",
                        "record",
                        "claim",
                        true
                ),
                Map.of(
                        "trustedSourceEntrypoint", "trusted/CreateUserProcedure.java",
                        "sideEffectConcept", "LegacyShouldNotWin",
                        "affectedConcepts", "LegacyShouldNotWin",
                        "eventNameOnSuccess", "generated.action.legacy.completed"
                )
        );
        CompiledFlow flow = new CompiledFlow(
                "CreateUserFlow",
                "User",
                "sync",
                List.of(new CompiledFlowStep(
                        "runGeneratedAction",
                        "generatedAction",
                        "",
                        "",
                        List.of(),
                        null,
                        null,
                        Map.of(),
                        null,
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null,
                        new CompiledCapabilityCall(
                                "generated.action.CreateUser",
                                "GeneratedActionCapability",
                                "generated-action",
                                "run",
                                List.of("input"),
                                "input",
                                "actionResult",
                                null,
                                null,
                                new CompiledCapabilityExecutionPolicy(1, 0L, 0L, 0, 0L, 0,
                                        "input.idempotencyKey", null)
                        ),
                        null,
                        "CreateUser"
                )),
                null,
                null,
                null,
                true
        );
        CompiledModel model = new CompiledModel(
                "trusted-test",
                "1.0.0",
                "1.0.0",
                Map.<String, CompiledConcept>of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(flow),
                List.of(),
                List.of(),
                List.of(),
                List.of(procedure),
                List.of()
        );

        new TrustedSourceEmitter(new GeneratedSourceWriter(outputRoot, new RegenerationPolicy())).emit(model, modelSource);
        return outputRoot;
    }

    private static void writeGeneratedAppValidationStubs(Path outputRoot) throws Exception {
        write(outputRoot, "src/main/java/jakarta/servlet/http/HttpServletRequest.java", """
                package jakarta.servlet.http;
                public interface HttpServletRequest {}
                """);
        write(outputRoot, "src/main/java/org/springframework/stereotype/Service.java", """
                package org.springframework.stereotype;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Service {}
                """);
        write(outputRoot, "src/main/java/org/springframework/stereotype/Component.java", """
                package org.springframework.stereotype;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface Component {}
                """);
        write(outputRoot, "src/main/java/org/springframework/beans/factory/SmartInitializingSingleton.java", """
                package org.springframework.beans.factory;
                public interface SmartInitializingSingleton {
                    void afterSingletonsInstantiated();
                }
                """);
        write(outputRoot, "src/main/java/org/springframework/web/bind/annotation/RestController.java", annotation("org.springframework.web.bind.annotation", "RestController"));
        write(outputRoot, "src/main/java/org/springframework/web/bind/annotation/PathVariable.java", annotation("org.springframework.web.bind.annotation", "PathVariable"));
        write(outputRoot, "src/main/java/org/springframework/web/bind/annotation/RequestBody.java", """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface RequestBody {
                    boolean required() default true;
                }
                """);
        write(outputRoot, "src/main/java/org/springframework/web/bind/annotation/GetMapping.java", mappingAnnotation("GetMapping"));
        write(outputRoot, "src/main/java/org/springframework/web/bind/annotation/PostMapping.java", mappingAnnotation("PostMapping"));
        write(outputRoot, "src/main/java/org/springframework/http/HttpStatus.java", """
                package org.springframework.http;
                public enum HttpStatus { OK, FORBIDDEN, NOT_FOUND, INTERNAL_SERVER_ERROR }
                """);
        write(outputRoot, "src/main/java/org/springframework/http/MediaType.java", """
                package org.springframework.http;
                public final class MediaType {
                    public static final String APPLICATION_JSON_VALUE = "application/json";
                    public static final String TEXT_HTML_VALUE = "text/html";
                    public static final MediaType APPLICATION_JSON = new MediaType("application/json");
                    public static final MediaType TEXT_HTML = new MediaType("text/html");
                    private final String value;
                    private MediaType(String value) { this.value = value; }
                    public static MediaType valueOf(String value) { return new MediaType(value); }
                    public String toString() { return value; }
                }
                """);
        write(outputRoot, "src/main/java/org/springframework/http/ResponseEntity.java", """
                package org.springframework.http;
                public final class ResponseEntity<T> {
                    private final HttpStatus statusCode;
                    private final T body;
                    public ResponseEntity(HttpStatus statusCode, T body) {
                        this.statusCode = statusCode;
                        this.body = body;
                    }
                    public static <T> ResponseEntity<T> ok(T body) { return new ResponseEntity<>(HttpStatus.OK, body); }
                    public static BodyBuilder ok() { return new BodyBuilder(HttpStatus.OK); }
                    public static BodyBuilder status(HttpStatus status) { return new BodyBuilder(status); }
                    public HttpStatus getStatusCode() { return statusCode; }
                    public T getBody() { return body; }
                    public static final class BodyBuilder {
                        private final HttpStatus status;
                        private BodyBuilder(HttpStatus status) { this.status = status; }
                        public BodyBuilder header(String name, String value) { return this; }
                        public BodyBuilder contentType(MediaType mediaType) { return this; }
                        public <T> ResponseEntity<T> body(T body) { return new ResponseEntity<>(status, body); }
                    }
                }
                """);
        write(outputRoot, "src/main/java/org/springframework/core/io/ClassPathResource.java", """
                package org.springframework.core.io;
                import java.io.ByteArrayInputStream;
                import java.io.InputStream;
                import java.nio.charset.StandardCharsets;
                public final class ClassPathResource {
                    public ClassPathResource(String path) {}
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream("".getBytes(StandardCharsets.UTF_8));
                    }
                }
                """);
        write(outputRoot, "src/main/java/org/springframework/util/StreamUtils.java", """
                package org.springframework.util;
                import java.io.InputStream;
                import java.nio.charset.Charset;
                public final class StreamUtils {
                    private StreamUtils() {}
                    public static String copyToString(InputStream input, Charset charset) { return ""; }
                }
                """);
        write(outputRoot, "src/main/java/com/npdev/generated/runtime/service/RuntimeContextService.java", """
                package com.npdev.generated.runtime.service;
                import com.npdev.kernel.ExecutionContext;
                import jakarta.servlet.http.HttpServletRequest;
                import java.util.Map;
                import java.util.Set;
                public class RuntimeContextService {
                    public ExecutionContext currentContext(HttpServletRequest request) {
                        return new ExecutionContext("dev", "developer", Map.of(), Set.of("ADMIN", "USER"));
                    }
                }
                """);
        write(outputRoot, "src/main/java/com/npdev/generated/runtime/service/KernelFacade.java", """
                package com.npdev.generated.runtime.service;
                import com.npdev.adapters.audit.inproc.InProcAuditLogStore;
                import com.npdev.adapters.events.inproc.InProcEventStore;
                import com.npdev.adapters.flowinstance.inproc.InProcCorrelationOwnershipStore;
                import com.npdev.adapters.idempotency.inproc.InProcIdempotencyStore;
                import com.npdev.adapters.tracing.inproc.InProcExecutionTracer;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.ExecutionResult;
                import com.npdev.kernel.audit.AuditRecord;
                import com.npdev.kernel.capability.IdempotencyRecord;
                import com.npdev.kernel.events.EventEnvelope;
                import com.npdev.kernel.execution.FlowInstance;
                import com.npdev.kernel.ports.AuditQuery;
                import com.npdev.kernel.trace.FlowTrace;
                import com.npdev.kernel.trace.FlowTraceMeta;
                import com.npdev.kernel.trace.StepOutcome;
                import com.npdev.kernel.trace.StepTrace;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                public class KernelFacade {
                    public final InProcEventStore eventStore = new InProcEventStore();
                    public final InProcAuditLogStore auditStore = new InProcAuditLogStore();
                    public final InProcExecutionTracer traceStore = new InProcExecutionTracer();
                    public final InProcIdempotencyStore idempotencyStore = new InProcIdempotencyStore();
                    public final InProcCorrelationOwnershipStore correlationStore = new InProcCorrelationOwnershipStore();
                    public int publishCount = 0;
                    public EventEnvelope publishExternalEvent(String eventName, String correlationId, String causationId, Map<String, Object> payload, ExecutionContext executionContext) {
                        publishCount++;
                        EventEnvelope envelope = EventEnvelope.create(eventName, payload, correlationId, causationId, "generated.action", 0, executionContext.tenantId(), executionContext.actorId());
                        eventStore.append(envelope);
                        return envelope;
                    }
                    public String verifyGeneratedActionEvent(String eventName, String correlationId, String eventId, ExecutionContext executionContext) {
                        Optional<EventEnvelope> row = eventStore.findFirst(eventName, correlationId, executionContext.tenantId());
                        return row.isPresent() && row.get().eventId().equals(eventId) ? "written: eventId=" + eventId : "failed: event readback missing";
                    }
                    public String claimGeneratedActionCorrelation(String correlationId, ExecutionContext executionContext) {
                        correlationStore.claimCorrelation(correlationId, executionContext.tenantId());
                        return correlationStore.findTenantByCorrelationId(correlationId).filter(executionContext.tenantId()::equals).isPresent()
                                ? "owned: correlationId=" + correlationId
                                : "failed: correlation owner readback missing";
                    }
                    public Optional<IdempotencyRecord> findGeneratedActionIdempotency(String actionName, String idempotencyKey, ExecutionContext executionContext) {
                        return idempotencyStore.find(executionContext.tenantId(), "generated.action." + actionName, actionName + ":" + executionContext.actorId(), idempotencyKey);
                    }
                    public String recordGeneratedActionIdempotencySuccess(String actionName, String idempotencyKey, String resultJsonRedacted, ExecutionContext executionContext) {
                        idempotencyStore.saveSuccess(executionContext.tenantId(), "generated.action." + actionName, actionName + ":" + executionContext.actorId(), idempotencyKey, resultJsonRedacted, System.currentTimeMillis());
                        return findGeneratedActionIdempotency(actionName, idempotencyKey, executionContext).filter(IdempotencyRecord::success).isPresent()
                                ? "recorded: idempotencyKey=" + idempotencyKey
                                : "failed: idempotency readback missing";
                    }
                    public String recordGeneratedActionIdempotencyFailure(String actionName, String idempotencyKey, String errorCode, ExecutionContext executionContext) {
                        idempotencyStore.saveFailure(executionContext.tenantId(), "generated.action." + actionName, actionName + ":" + executionContext.actorId(), idempotencyKey, errorCode, System.currentTimeMillis());
                        return "recorded: idempotencyKey=" + idempotencyKey;
                    }
                    public String recordGeneratedActionAudit(String actionName, String auditResourceType, String executionId, String correlationId, String outcome, String reasonCode, ExecutionContext executionContext) {
                        String action = "GENERATED_ACTION_" + outcome.toUpperCase();
                        AuditRecord record = AuditRecord.create(executionContext.tenantId(), executionContext.actorId(), executionContext.roles(), action, auditResourceType, executionId, outcome, reasonCode, executionContext.tags(), Map.of("actionName", actionName, "correlationId", correlationId));
                        auditStore.append(record);
                        boolean found = auditStore.search(new AuditQuery(executionContext.tenantId(), executionContext.actorId(), action, auditResourceType, executionId, null, null, 10, 0)).stream().anyMatch(row -> row.auditId().equals(record.auditId()));
                        return found ? "written: auditId=" + record.auditId() : "failed: audit readback missing";
                    }
                    public String writeGeneratedActionTrace(String actionName, String executionId, String correlationId, String outcome, long startedAtMs, long endedAtMs, int before, int after, ExecutionContext executionContext) {
                        long started = Math.max(1L, startedAtMs);
                        long ended = Math.max(started, endedAtMs);
                        StepOutcome stepOutcome = "ok".equalsIgnoreCase(outcome) ? StepOutcome.OK : StepOutcome.FAILED;
                        FlowTrace trace = new FlowTrace(
                                new FlowTraceMeta(executionId, correlationId, "generated.action." + actionName, executionContext.tenantId(), executionContext.actorId(), Map.of("actionName", actionName)),
                                started,
                                ended,
                                stepOutcome,
                                List.of(new StepTrace(0, actionName, "trusted-procedure", started, ended, stepOutcome, Map.of("sideEffectCountBefore", before, "sideEffectCountAfter", after), List.of(), null))
                        );
                        traceStore.save(trace);
                        return traceStore.findByExecutionId(executionId).isPresent() ? "written: executionId=" + executionId : "failed: trace readback missing";
                    }
                    public ExecutionResult resumeExecution(String executionId, ExecutionContext executionContext) {
                        String safeExecutionId = executionId == null || executionId.isBlank() ? "exec-flow" : executionId;
                        String safeCorrelationId = executionContext == null ? "corr-flow" : executionContext.correlationId();
                        return ExecutionResult.ok("resume", Map.of(), List.of(), safeExecutionId, safeCorrelationId, safeExecutionId);
                    }
                    public Map<String, Object> evidenceSnapshot(String correlationId, String executionId, String actionName, String idempotencyKey, ExecutionContext executionContext) {
                        return Map.of(
                                "events", eventStore.readByCorrelation(correlationId, executionContext.tenantId()).size(),
                                "audits", auditStore.search(new AuditQuery(executionContext.tenantId(), null, null, null, executionId, null, null, 100, 0)).size(),
                                "traces", traceStore.findByExecutionId(executionId).isPresent() ? 1 : 0,
                                "idempotency", findGeneratedActionIdempotency(actionName, idempotencyKey, executionContext).isPresent() ? 1 : 0,
                                "correlationOwners", correlationStore.findTenantByCorrelationId(correlationId).isPresent() ? 1 : 0
                        );
                    }
                    public ExecutionResult executeFlow(String flowName, Map<String, Object> input, ExecutionContext executionContext) {
                        String correlationId = input == null ? "" : String.valueOf(input.get("correlationId"));
                        return ExecutionResult.ok(flowName, Map.of(), List.of(), "exec-flow", correlationId, "exec-flow");
                    }
                    public Optional<FlowInstance> findExecution(String executionId, ExecutionContext executionContext) {
                        return Optional.empty();
                    }
                    public Map<String, Object> generatedActionEvidenceByExecution(String executionId, ExecutionContext executionContext) {
                        Optional<FlowTrace> trace = traceStore.findByExecutionId(executionId);
                        String correlationId = trace.map(row -> row.meta().correlationId()).orElse("");
                        if (correlationId.isBlank()) {
                            Map<String, Object> missing = new LinkedHashMap<>();
                            missing.put("executionId", executionId);
                            missing.put("status", "not_found");
                            missing.put("evidenceStatus", "correlation_unresolved");
                            missing.put("eventCount", 0);
                            missing.put("traceCount", 0);
                            missing.put("auditCount", 0);
                            missing.put("idempotencyCount", 0);
                            missing.put("correlationOwnerCount", 0);
                            missing.put("events", List.of());
                            missing.put("traces", List.of());
                            missing.put("audits", List.of());
                            missing.put("idempotencyRecords", List.of());
                            missing.put("correlationOwners", List.of());
                            missing.put("warnings", List.of("executionId lookup could not resolve correlationId"));
                            return missing;
                        }
                        return generatedActionEvidenceByCorrelation(correlationId, executionContext);
                    }
                    public Map<String, Object> generatedActionEvidenceByCorrelation(String correlationId, ExecutionContext executionContext) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("executionId", "exec");
                        out.put("correlationId", correlationId);
                        out.put("actionName", "CreateUser");
                        out.put("procedureName", "CreateUser");
                        out.put("capabilityId", "generated.action.CreateUser");
                        out.put("status", "complete");
                        out.put("evidenceStatus", "complete");
                        out.put("eventCount", eventStore.readByCorrelation(correlationId, executionContext.tenantId()).size());
                        out.put("traceCount", traceStore.search(new com.npdev.kernel.ports.TraceQuery(correlationId, null, null, null, null, 100, 0)).size());
                        out.put("auditCount", auditStore.search(AuditQuery.emptyForTenant(executionContext.tenantId())).size());
                        out.put("idempotencyCount", 1);
                        out.put("correlationOwnerCount", correlationStore.findTenantByCorrelationId(correlationId).isPresent() ? 1 : 0);
                        out.put("events", List.of());
                        out.put("traces", List.of());
                        out.put("audits", List.of());
                        out.put("idempotencyRecords", List.of());
                        out.put("correlationOwners", List.of());
                        out.put("warnings", List.of());
                        return out;
                    }
                }
                """);
        write(outputRoot, "src/main/java/validation/GeneratedActionEndpointHarness.java", """
                package validation;

                import com.npdev.generated.runtime.service.KernelFacade;
                import com.npdev.generated.runtime.service.RuntimeContextService;
                import com.npdev.generated.trusted.CreateUserProcedure;
                import com.npdev.generated.trusted.GeneratedActionCapabilityDispatcherFactory;
                import com.npdev.generated.trusted.GeneratedFlowCodaRunner;
                import com.npdev.generated.trusted.GeneratedActionKernelRunner;
                import com.npdev.generated.trusted.GeneratedTrustedSourceRuntimeController;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptListRequest;
                import com.npdev.kernel.concepts.ConceptReadRequest;
                import com.npdev.kernel.concepts.ConceptRecord;
                import com.npdev.kernel.concepts.ConceptWriteRequest;
                import org.springframework.http.ResponseEntity;

                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;

                public final class GeneratedActionEndpointHarness {
                    public static void main(String[] args) {
                        GeneratedActionCapabilityDispatcherFactory.resetCounters();
                        InMemoryConceptGateway gateway = new InMemoryConceptGateway();
                        KernelFacade facade = new KernelFacade();
                        GeneratedActionKernelRunner runner = new GeneratedActionKernelRunner(gateway, facade);
                        GeneratedFlowCodaRunner flowRunner = new GeneratedFlowCodaRunner(facade, gateway);
                        GeneratedTrustedSourceRuntimeController controller = new GeneratedTrustedSourceRuntimeController(
                                new RuntimeContextService(),
                                gateway,
                                runner,
                                flowRunner,
                                facade
                        );

                        ResponseEntity<Map<String, Object>> actionResponse = controller.runAction("CreateUser", Map.of("idempotencyKey", "idem-1"), null);
                        Map<String, Object> body = actionResponse.getBody();
                        require("ok".equals(body.get("status")), "status must be ok");
                        require("CreateUser".equals(body.get("actionName")), "actionName must be CreateUser");
                        require("CreateUser".equals(body.get("procedureName")), "procedureName must be CreateUser");
                        require(!String.valueOf(body.get("executionId")).isBlank(), "executionId must be present");
                        require(!String.valueOf(body.get("correlationId")).isBlank(), "correlationId must be present");
                        require(Integer.valueOf(1).equals(body.get("createdCount")), "createdCount must reflect business side-effect delta");
                        require(Integer.valueOf(0).equals(body.get("sideEffectCountBefore")), "sideEffectCountBefore must be 0");
                        require(Integer.valueOf(1).equals(body.get("sideEffectCountAfter")), "sideEffectCountAfter must be 1");
                        require(String.valueOf(body.get("eventStatus")).startsWith("written:"), "eventStatus must prove publish path");
                        require(String.valueOf(body.get("auditStatus")).startsWith("written:"), "auditStatus must prove audit store write");
                        require(String.valueOf(body.get("traceStatus")).startsWith("written:"), "traceStatus must prove trace store write");
                        require(String.valueOf(body.get("idempotencyStatus")).startsWith("recorded:"), "idempotencyStatus must prove idempotency store write");
                        require(String.valueOf(body.get("correlationStatus")).startsWith("owned:"), "correlationStatus must prove ownership store write");
                        require(facade.publishCount == 1, "procedure endpoint must enter GeneratedActionKernelRunner event path");
                        require(CreateUserProcedure.invocationCount == 1, "handler must run on first call");
                        require(GeneratedActionCapabilityDispatcherFactory.dispatcherInvocations() == 1, "dispatcher must be entered on first call");
                        require(GeneratedActionCapabilityDispatcherFactory.providerInvocations() == 1, "provider must be entered on first call");
                        require(GeneratedActionCapabilityDispatcherFactory.handlerInvocations() == 1, "handler must be invoked through provider on first call");

                        Map<String, Object> firstEvidence = facade.evidenceSnapshot(
                                String.valueOf(body.get("correlationId")),
                                String.valueOf(body.get("executionId")),
                                "CreateUser",
                                "idem-1",
                                ExecutionContext.of("dev", "developer")
                        );
                        require(Integer.valueOf(1).equals(firstEvidence.get("events")), "event adapter/store evidence must exist");
                        require(Integer.valueOf(1).equals(firstEvidence.get("traces")), "trace adapter/store evidence must exist");
                        require(((Integer) firstEvidence.get("audits")) >= 1, "audit adapter/store evidence must exist");
                        require(Integer.valueOf(1).equals(firstEvidence.get("idempotency")), "idempotency adapter/store evidence must exist");
                        require(Integer.valueOf(1).equals(firstEvidence.get("correlationOwners")), "correlation adapter/store evidence must exist");

                        ResponseEntity<Map<String, Object>> repeatResponse = controller.invokeProcedure("CreateUser", Map.of("idempotencyKey", "idem-1"), null);
                        Map<String, Object> repeatBody = repeatResponse.getBody();
                        require("ok".equals(repeatBody.get("status")), "repeat status must be ok");
                        require(Integer.valueOf(0).equals(repeatBody.get("createdCount")), "repeat call must not create side effects");
                        require(Integer.valueOf(1).equals(repeatBody.get("sideEffectCountBefore")), "repeat before count must see first row");
                        require(Integer.valueOf(1).equals(repeatBody.get("sideEffectCountAfter")), "repeat after count must remain unchanged");
                        require(String.valueOf(repeatBody.get("idempotencyStatus")).startsWith("reused:"), "repeat idempotencyStatus must prove reuse");
                        require(CreateUserProcedure.invocationCount == 1, "side-effecting handler must not be invoked again");
                        require(GeneratedActionCapabilityDispatcherFactory.dispatcherInvocations() == 1, "idempotency reuse must not redispatch side-effecting execution");
                        require(GeneratedActionCapabilityDispatcherFactory.providerInvocations() == 1, "idempotency reuse must not reenter provider");
                        require(GeneratedActionCapabilityDispatcherFactory.handlerInvocations() == 1, "idempotency reuse must not reenter handler through provider");
                        require(gateway.users.size() == 1, "business row count must not increase on idempotency repeat");
                    }

                    private static void require(boolean condition, String message) {
                        if (!condition) {
                            throw new IllegalStateException(message);
                        }
                    }

                    private static final class InMemoryConceptGateway implements ConceptGateway {
                        private final List<ConceptRecord> users = new ArrayList<>();

                        @Override
                        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
                            return users.stream()
                                    .filter(record -> record.conceptName().equals(request.conceptName()))
                                    .filter(record -> record.id().equals(request.id()))
                                    .findFirst();
                        }

                        @Override
                        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
                            if ("User".equals(request.conceptName())) {
                                return List.copyOf(users);
                            }
                            return List.of();
                        }

                        @Override
                        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
                            Map<String, Object> data = new LinkedHashMap<>(request.data());
                            ConceptRecord record = new ConceptRecord(request.conceptName(), request.id(), request.tenantId(), Map.copyOf(data));
                            if ("User".equals(request.conceptName())) {
                                users.add(record);
                            }
                            return record;
                        }

                        @Override
                        public void delete(ConceptReadRequest request, ExecutionContext context) {
                            users.removeIf(record -> record.conceptName().equals(request.conceptName()) && record.id().equals(request.id()));
                        }
                    }
                }
                """);
    }

    private static String annotation(String packageName, String name) {
        return """
                package %s;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface %s {}
                """.formatted(packageName, name);
    }

    private static String mappingAnnotation(String name) {
        return """
                package org.springframework.web.bind.annotation;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                @Retention(RetentionPolicy.RUNTIME)
                public @interface %s {
                    String value() default "";
                    String produces() default "";
                }
                """.formatted(name);
    }

    private static void write(Path outputRoot, String relativePath, String source) throws Exception {
        Path target = outputRoot.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, source, StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }
}

