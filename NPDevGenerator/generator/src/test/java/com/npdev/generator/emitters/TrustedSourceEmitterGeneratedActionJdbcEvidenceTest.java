package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledGeneratedActionDescriptorSpec;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.generator.dbconfig.DatabaseEngine;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import com.npdev.generator.dbconfig.SchemaLifecycleStrategy;
import com.npdev.generator.dbconfig.SchemaRealizationEmitter;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TrustedSourceEmitterGeneratedActionJdbcEvidenceTest {
    @TempDir
    Path tempDir;

    @Test
    void generatedActionRuntimeWritesJdbKernelEvidenceRowsAndPreventsIdempotentRepeat() throws Exception {
        Path outputRoot = emitTrustedActionApp();
        String internalSchemaSql = emitInternalSchemaSql();
        writeGeneratedAppValidationStubs(outputRoot, internalSchemaSql);

        Path classesDir = tempDir.resolve("jdbc-classes");
        Files.createDirectories(classesDir);
        List<Path> sources;
        try (Stream<Path> stream = Files.walk(outputRoot.resolve("src/main/java"))) {
            sources = stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertTrue(compiler != null, "Generated app JDBC validation requires a JDK compiler");
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
            assertTrue(Boolean.TRUE.equals(ok), "Generated trusted-source Java must compile in JDBC temp app harness");
        }

        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()},
                TrustedSourceEmitterGeneratedActionJdbcEvidenceTest.class.getClassLoader()
        )) {
            Class<?> harness = Class.forName("validation.GeneratedActionJdbcEvidenceHarness", true, loader);
            Object proof = harness.getMethod("runProof").invoke(null);
            String output = String.valueOf(proof);
            assertTrue(output.contains("JDBC generated action proof passed"), output);
            assertTrue(output.contains("npdev_event_store=1"), output);
            assertTrue(output.contains("npdev_trace=2"), output);
            assertTrue(output.matches("(?s).*npdev_audit_log=([3-9]|[1-9][0-9]+).*"), output);
            assertTrue(output.contains("npdev_idempotency=1"), output);
            assertTrue(output.contains("npdev_correlation_owner=1"), output);
            assertTrue(output.contains("business_users=1"), output);
            assertTrue(output.contains("dispatcher_invocations=1"), output);
            assertTrue(output.contains("provider_invocations=1"), output);
            assertTrue(output.contains("handler_invocations=1"), output);
        }
    }

    private Path emitTrustedActionApp() throws Exception {
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
                "write",
                new CompiledGeneratedActionDescriptorSpec(
                        "CreateUser",
                        List.of("User"),
                        "User",
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
        CompiledModel model = new CompiledModel(
                "trusted-jdbc-test",
                "1.0.0",
                "1.0.0",
                Map.<String, CompiledConcept>of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(procedure),
                List.of()
        );

        new TrustedSourceEmitter(new GeneratedSourceWriter(outputRoot, new RegenerationPolicy())).emit(model, modelSource);
        return outputRoot;
    }

    private String emitInternalSchemaSql() throws Exception {
        Path schemaOut = tempDir.resolve("schema-out");
        Path modelSource = tempDir.resolve("schema-model.json");
        Files.writeString(modelSource, "{}", StandardCharsets.UTF_8);
        CompiledModel model = new CompiledModel(
                "trusted-jdbc-test",
                "1.0.0",
                "1.0.0",
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        GeneratedDatabasePlan plan = new GeneratedDatabasePlan(
                "trusted-jdbc-test",
                DatabaseEngine.H2_LOCAL,
                "jdbc",
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "trusted_jdbc_test",
                "trusted_jdbc_test",
                "test",
                tempDir.resolve("db").toString(),
                "test-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:trusted_jdbc_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                true,
                false,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE,
                        false,
                        "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE
                ),
                "test-fingerprint",
                modelSource,
                List.of("test")
        );
        new SchemaRealizationEmitter().emit(model, schemaOut, plan, modelSource);
        return Files.readString(
                schemaOut.resolve("src/main/resources/db/schema-realization/V1__npdev_schema_realization.sql"),
                StandardCharsets.UTF_8
        );
    }

    private static void writeGeneratedAppValidationStubs(Path outputRoot, String internalSchemaSql) throws Exception {
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

                import com.npdev.adapters.audit.jdbc.JdbcAuditLogStore;
                import com.npdev.adapters.eventstore.jdbc.JdbcEventStore;
                import com.npdev.adapters.flowinstance.jdbc.JdbcCorrelationOwnershipStore;
                import com.npdev.adapters.idempotency.jdbc.JdbcIdempotencyStore;
                import com.npdev.adapters.tracestore.jdbc.JdbcTraceStore;
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
                import javax.sql.DataSource;

                public class KernelFacade {
                    private final JdbcEventStore eventStore;
                    private final JdbcAuditLogStore auditStore;
                    private final JdbcTraceStore traceStore;
                    private final JdbcIdempotencyStore idempotencyStore;
                    private final JdbcCorrelationOwnershipStore correlationStore;
                    public int publishCount = 0;

                    public KernelFacade(DataSource dataSource) {
                        this.eventStore = new JdbcEventStore(dataSource);
                        this.auditStore = new JdbcAuditLogStore(dataSource);
                        this.traceStore = new JdbcTraceStore(dataSource);
                        this.idempotencyStore = new JdbcIdempotencyStore(dataSource);
                        this.correlationStore = new JdbcCorrelationOwnershipStore(dataSource);
                    }

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

                    public ExecutionResult executeFlow(String flowName, Map<String, Object> input, ExecutionContext executionContext) {
                        String correlationId = input == null ? "" : String.valueOf(input.get("correlationId"));
                        return ExecutionResult.ok(flowName, Map.of(), List.of(), "exec-flow", correlationId, "exec-flow");
                    }

                    public Optional<FlowInstance> findExecution(String executionId, ExecutionContext executionContext) {
                        return Optional.empty();
                    }

                    public Map<String, Object> generatedActionEvidenceByExecution(String executionId, ExecutionContext executionContext) {
                        Optional<FlowTrace> trace = traceStore.findByExecutionId(executionId);
                        if (trace.isEmpty()) {
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
                        return generatedActionEvidenceByCorrelation(trace.get().meta().correlationId(), executionContext);
                    }

                    public Map<String, Object> generatedActionEvidenceByCorrelation(String correlationId, ExecutionContext executionContext) {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("executionId", "exec-item8-1");
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

                    public ExecutionResult resumeExecution(String executionId, ExecutionContext executionContext) {
                        String safeExecutionId = executionId == null || executionId.isBlank() ? "exec-flow" : executionId;
                        String safeCorrelationId = executionContext == null ? "corr-flow" : executionContext.correlationId();
                        return ExecutionResult.ok("resume", Map.of(), List.of(), safeExecutionId, safeCorrelationId, safeExecutionId);
                    }
                }
                """);
        write(outputRoot, "src/main/java/validation/GeneratedActionJdbcEvidenceHarness.java", """
                package validation;

                import com.npdev.generated.runtime.service.KernelFacade;
                import com.npdev.generated.runtime.service.RuntimeContextService;
                import com.npdev.generated.trusted.CreateUserProcedure;
                import com.npdev.generated.trusted.GeneratedActionCapabilityDispatcherFactory;
                import com.npdev.generated.trusted.GeneratedActionKernelRunner;
                import com.npdev.generated.trusted.GeneratedFlowCodaRunner;
                import com.npdev.generated.trusted.GeneratedTrustedSourceRuntimeController;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptListRequest;
                import com.npdev.kernel.concepts.ConceptReadRequest;
                import com.npdev.kernel.concepts.ConceptRecord;
                import com.npdev.kernel.concepts.ConceptWriteRequest;
                import org.h2.jdbcx.JdbcDataSource;
                import org.springframework.http.ResponseEntity;

                import java.sql.Connection;
                import java.sql.ResultSet;
                import java.sql.Statement;
                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import javax.sql.DataSource;

                public final class GeneratedActionJdbcEvidenceHarness {
                    private static final String INTERNAL_SCHEMA_SQL = %s;

                    public static String runProof() throws Exception {
                        GeneratedActionCapabilityDispatcherFactory.resetCounters();
                        JdbcDataSource dataSource = new JdbcDataSource();
                        dataSource.setURL("jdbc:h2:mem:item8_generated_action;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
                        dataSource.setUser("sa");
                        dataSource.setPassword("");
                        initializeSchema(dataSource);

                        JdbcConceptGateway gateway = new JdbcConceptGateway(dataSource);
                        KernelFacade facade = new KernelFacade(dataSource);
                        GeneratedActionKernelRunner runner = new GeneratedActionKernelRunner(gateway, facade);
                        GeneratedFlowCodaRunner flowRunner = new GeneratedFlowCodaRunner(facade, gateway);
                        GeneratedTrustedSourceRuntimeController controller = new GeneratedTrustedSourceRuntimeController(
                                new RuntimeContextService(),
                                gateway,
                                runner,
                                flowRunner,
                                facade
                        );

                        Map<String, Object> request = Map.of(
                                "executionId", "exec-item8-1",
                                "correlationId", "corr-item8-1",
                                "idempotencyKey", "idem-item8-1"
                        );
                        ResponseEntity<Map<String, Object>> actionResponse = controller.runAction("CreateUser", request, null);
                        Map<String, Object> first = actionResponse.getBody();
                        require("ok".equals(first.get("status")), "action endpoint status must be ok");
                        require("exec-item8-1".equals(first.get("executionId")), "executionId must be accepted");
                        require("corr-item8-1".equals(first.get("correlationId")), "correlationId must be accepted");
                        require(Integer.valueOf(1).equals(first.get("createdCount")), "first call must create one business row");
                        require(String.valueOf(first.get("eventStatus")).startsWith("written:"), "eventStatus must prove JDBC event row");
                        require(String.valueOf(first.get("auditStatus")).startsWith("written:"), "auditStatus must prove JDBC audit row");
                        require(String.valueOf(first.get("traceStatus")).startsWith("written:"), "traceStatus must prove JDBC trace row");
                        require(String.valueOf(first.get("idempotencyStatus")).startsWith("recorded:"), "idempotencyStatus must prove JDBC idempotency row");
                        require(String.valueOf(first.get("correlationStatus")).startsWith("owned:"), "correlationStatus must prove JDBC correlation owner row");
                        require(CreateUserProcedure.invocationCount == 1, "handler must run on first call");
                        require(GeneratedActionCapabilityDispatcherFactory.dispatcherInvocations() == 1, "dispatcher must be entered on first call");
                        require(GeneratedActionCapabilityDispatcherFactory.providerInvocations() == 1, "provider must be entered on first call");
                        require(GeneratedActionCapabilityDispatcherFactory.handlerInvocations() == 1, "handler must be invoked through provider on first call");

                        Map<String, Object> repeatRequest = Map.of(
                                "executionId", "exec-item8-2",
                                "correlationId", "corr-item8-1",
                                "idempotencyKey", "idem-item8-1"
                        );
                        ResponseEntity<Map<String, Object>> repeatResponse = controller.invokeProcedure("CreateUser", repeatRequest, null);
                        Map<String, Object> repeat = repeatResponse.getBody();
                        require("ok".equals(repeat.get("status")), "procedure endpoint repeat status must be ok");
                        require(Integer.valueOf(0).equals(repeat.get("createdCount")), "repeat call must not create business rows");
                        require(Integer.valueOf(1).equals(repeat.get("sideEffectCountBefore")), "repeat before count must see first business row");
                        require(Integer.valueOf(1).equals(repeat.get("sideEffectCountAfter")), "repeat after count must stay stable");
                        require(String.valueOf(repeat.get("idempotencyStatus")).startsWith("reused:"), "repeat idempotencyStatus must prove reuse");
                        require(CreateUserProcedure.invocationCount == 1, "handler must not run on idempotent repeat");
                        require(GeneratedActionCapabilityDispatcherFactory.dispatcherInvocations() == 1, "idempotency reuse must not redispatch side-effecting execution");
                        require(GeneratedActionCapabilityDispatcherFactory.providerInvocations() == 1, "idempotency reuse must not reenter provider");
                        require(GeneratedActionCapabilityDispatcherFactory.handlerInvocations() == 1, "idempotency reuse must not reenter handler through provider");

                        int events = count(dataSource, "SELECT COUNT(*) FROM npdev_event_store WHERE tenant_id = 'dev' AND correlation_id = 'corr-item8-1'");
                        int traces = count(dataSource, "SELECT COUNT(*) FROM npdev_trace WHERE tenant_id = 'dev' AND correlation_id = 'corr-item8-1'");
                        int audits = count(dataSource, "SELECT COUNT(*) FROM npdev_audit_log WHERE tenant_id = 'dev' AND resource_id IN ('exec-item8-1', 'exec-item8-2')");
                        int idempotency = count(dataSource, "SELECT COUNT(*) FROM npdev_idempotency WHERE tenant_id = 'dev' AND idempotency_key = 'idem-item8-1'");
                        int correlations = count(dataSource, "SELECT COUNT(*) FROM npdev_correlation_owner WHERE tenant_id = 'dev' AND correlation_id = 'corr-item8-1'");
                        int businessUsers = count(dataSource, "SELECT COUNT(*) FROM business_users");

                        require(events == 1, "exactly one event row expected");
                        require(traces == 2, "first call and repeat call must each write one trace row");
                        require(audits == 3, "start/completion/reuse audits expected");
                        require(idempotency == 1, "one idempotency row expected");
                        require(correlations == 1, "one correlation owner row expected");
                        require(businessUsers == 1, "business row count must not increase after repeat");

                        return "JDBC generated action proof passed; "
                                + "npdev_event_store=" + events + "; "
                                + "npdev_trace=" + traces + "; "
                                + "npdev_audit_log=" + audits + "; "
                                + "npdev_idempotency=" + idempotency + "; "
                                + "npdev_correlation_owner=" + correlations + "; "
                                + "business_users=" + businessUsers + "; "
                                + "dispatcher_invocations=" + GeneratedActionCapabilityDispatcherFactory.dispatcherInvocations() + "; "
                                + "provider_invocations=" + GeneratedActionCapabilityDispatcherFactory.providerInvocations() + "; "
                                + "handler_invocations=" + CreateUserProcedure.invocationCount;
                    }

                    private static void initializeSchema(DataSource dataSource) throws Exception {
                        try (Connection connection = dataSource.getConnection();
                             Statement statement = connection.createStatement()) {
                            for (String sql : INTERNAL_SCHEMA_SQL.split(";")) {
                                String trimmed = sql.trim();
                                if (!trimmed.isBlank()) {
                                    statement.execute(trimmed);
                                }
                            }
                            statement.execute(\"\"\"
                                    CREATE TABLE IF NOT EXISTS business_users (
                                        id TEXT PRIMARY KEY,
                                        tenant_id TEXT NOT NULL,
                                        name TEXT NOT NULL
                                    )
                                    \"\"\");
                        }
                    }

                    private static int count(DataSource dataSource, String sql) throws Exception {
                        try (Connection connection = dataSource.getConnection();
                             Statement statement = connection.createStatement();
                             ResultSet resultSet = statement.executeQuery(sql)) {
                            resultSet.next();
                            return resultSet.getInt(1);
                        }
                    }

                    private static void require(boolean condition, String message) {
                        if (!condition) {
                            throw new IllegalStateException(message);
                        }
                    }

                    private static final class JdbcConceptGateway implements ConceptGateway {
                        private final DataSource dataSource;

                        private JdbcConceptGateway(DataSource dataSource) {
                            this.dataSource = dataSource;
                        }

                        @Override
                        public Optional<ConceptRecord> read(ConceptReadRequest request, ExecutionContext context) {
                            return list(new ConceptListRequest(request.conceptName(), context.tenantId()), context).stream()
                                    .filter(record -> record.id().equals(request.id()))
                                    .findFirst();
                        }

                        @Override
                        public List<ConceptRecord> list(ConceptListRequest request, ExecutionContext context) {
                            if (!"User".equals(request.conceptName())) {
                                return List.of();
                            }
                            try (Connection connection = dataSource.getConnection();
                                 Statement statement = connection.createStatement();
                                 ResultSet resultSet = statement.executeQuery("SELECT id, tenant_id, name FROM business_users ORDER BY id")) {
                                List<ConceptRecord> rows = new ArrayList<>();
                                while (resultSet.next()) {
                                    rows.add(new ConceptRecord(
                                            "User",
                                            resultSet.getString("id"),
                                            resultSet.getString("tenant_id"),
                                            Map.of("id", resultSet.getString("id"), "name", resultSet.getString("name"))
                                    ));
                                }
                                return List.copyOf(rows);
                            } catch (Exception exception) {
                                throw new IllegalStateException("Failed listing business users", exception);
                            }
                        }

                        @Override
                        public ConceptRecord save(ConceptWriteRequest request, ExecutionContext context) {
                            if (!"User".equals(request.conceptName())) {
                                throw new IllegalArgumentException("Unsupported concept: " + request.conceptName());
                            }
                            String id = String.valueOf(request.data().getOrDefault("id", request.id()));
                            String name = String.valueOf(request.data().getOrDefault("name", ""));
                            try (Connection connection = dataSource.getConnection();
                                 Statement statement = connection.createStatement()) {
                                statement.executeUpdate("MERGE INTO business_users (id, tenant_id, name) KEY(id) VALUES ('" + id + "', '" + context.tenantId() + "', '" + name + "')");
                                Map<String, Object> data = new LinkedHashMap<>(request.data());
                                data.put("id", id);
                                data.put("name", name);
                                return new ConceptRecord("User", id, context.tenantId(), Map.copyOf(data));
                            } catch (Exception exception) {
                                throw new IllegalStateException("Failed saving business user", exception);
                            }
                        }

                        @Override
                        public void delete(ConceptReadRequest request, ExecutionContext context) {
                            throw new UnsupportedOperationException("delete not used by proof");
                        }
                    }
                }
                """.formatted(javaTextBlock(internalSchemaSql)));
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

    private static String javaTextBlock(String value) {
        return "\"\"\"\n" + value.replace("\\", "\\\\").replace("\"\"\"", "\\\"\\\"\\\"") + "\n\"\"\"";
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

