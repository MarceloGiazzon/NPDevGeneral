package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledCapabilityCall;
import com.npdev.dsl.v1.compiled.CompiledCapabilityExecutionPolicy;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledGeneratedActionDescriptorSpec;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.assembly.FinalAppAssembler;
import com.npdev.generator.dbconfig.DatabaseEngine;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import com.npdev.generator.dbconfig.SchemaLifecycleStrategy;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

// storage/PLAN.md W2: the packaged-app proofs generate, build, boot and exercise a real
// FinalApp -- minutes each, and they are what caught the Windows/Linux build-root
// divergence. Tagged so a local `test` run can skip them (LOUDLY -- see build.gradle) while
// CI always runs them. Do not remove the tag to "speed up CI"; CI is where they earn their keep.
@Tag("packaged-proof")
final class TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Path WORKSPACE_ROOT = resolveWorkspaceRoot();
    private static final Path OUTSIDE_ROOT = WORKSPACE_ROOT.resolveSibling(WORKSPACE_ROOT.getFileName() + "__OutsideRepo");
    private static final Path ITEM12_ROOT = OUTSIDE_ROOT.resolve("item12-packaged-generated-app-runtime");

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES)
    void packagedGeneratedAppBootsHandlesHttpAndWritesJdbcEvidenceRows() throws Exception {
        String runId = "item12-" + System.currentTimeMillis();
        Path runRoot = ITEM12_ROOT.resolve(runId);
        try {
            runPackagedGeneratedAppProof(runId, runRoot);
        } finally {
            deleteRecursively(runRoot);
        }
    }

    private void runPackagedGeneratedAppProof(String runId, Path runRoot) throws Exception {
        Path generatedRoot = runRoot.resolve("generated-artifact");
        Path schemaRoot = generatedRoot.resolve("src/main/resources/db/schema-realization");
        Path finalAppRoot = runRoot.resolve("generated-app");
        Path evidenceRoot = runRoot.resolve("proof-output");
        Files.createDirectories(evidenceRoot);

        StringBuilder generationOutput = new StringBuilder();
        CompiledModel model = compiledProofModel();
        Path modelSource = writeTrustedSourceModel(runRoot);
        GeneratedDatabasePlan plan = h2JdbcPlan(runRoot, modelSource);
        new GeneratorFacade(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(generatedRoot, new RegenerationPolicy()))
                .generate(model, generatedRoot, schemaRoot, modelSource, plan);
        generationOutput.append("Generated artifact root: ").append(generatedRoot).append(System.lineSeparator());
        generationOutput.append("Schema realization root: ").append(schemaRoot).append(System.lineSeparator());

        FinalAppAssembler.AssemblyResult assemblyResult = new FinalAppAssembler().assemble(
                new FinalAppAssembler.Options(
                        WORKSPACE_ROOT.resolve("NPDevRuntimeHost"),
                        generatedRoot,
                        finalAppRoot,
                        schemaRoot,
                        "npdev-generated",
                        "npdev-meta",
                        true,
                        17
                )
        );
        generationOutput.append("Final app root: ").append(assemblyResult.finalAppRoot()).append(System.lineSeparator());
        generationOutput.append("Generated mount: ").append(assemblyResult.generatedMount()).append(System.lineSeparator());
        Files.writeString(evidenceRoot.resolve("packaged-app-generation-output.txt"), generationOutput.toString(), StandardCharsets.UTF_8);

        writePackagedProofController(finalAppRoot);
        Path runtimeHostLibs = ensureRuntimeHostLibs(evidenceRoot);

        CommandResult bootJar = runCommand(
                // REG-137: pass both the env var AND -P. FinalAppAssembler always bakes a
                // generation-time npdevRuntimeHostLibsDir default into the assembled app's
                // gradle.properties (REG-128), which -- absent this -P -- would win over a
                // bare env var via Gradle's own command-line/-property-file precedence.
                List.of(gradlewPath(finalAppRoot).toString(), "--no-daemon", "bootJar",
                        "-PnpdevRuntimeHostLibsDir=" + runtimeHostLibs),
                finalAppRoot,
                Map.of("NPDEV_RUNTIMEHOST_LIBS_DIR", runtimeHostLibs.toString()),
                Duration.ofMinutes(6)
        );
        Files.writeString(evidenceRoot.resolve("packaged-app-build-output.txt"), bootJar.output(), StandardCharsets.UTF_8);
        assertEquals(0, bootJar.exitCode(), bootJar.output());

        Path jar = findBootJar(finalAppRoot);
        int port = freePort();
        String jdbcUrl = "jdbc:h2:mem:" + runId.replace("-", "_")
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE";
        Process app = startPackagedApp(finalAppRoot, jar, port, jdbcUrl, runtimeHostLibs, evidenceRoot);
        try {
            waitForHealth(port, evidenceRoot);
            HttpClient client = HttpClient.newHttpClient();

            Map<String, Object> action = postJson(client, port, "/generated/actions/CreateItem12User/run", Map.of(
                    "executionId", "exec-item12-1",
                    "correlationId", "corr-item12-1",
                    "idempotencyKey", "idem-item12-1"
            ));
            assertEquals("ok", action.get("status"), () -> "action response: " + action);
            assertEquals("exec-item12-1", action.get("executionId"));
            assertEquals("corr-item12-1", action.get("correlationId"));
            assertEquals("generated.action.CreateItem12User", action.get("capabilityId"));
            assertTrue(String.valueOf(action.get("capabilityDispatchStatus")).startsWith("dispatched:"),
                    () -> "action response: " + action);
            assertEquals(1, number(action.get("createdCount")), () -> "action response: " + action);

            Map<String, Object> repeat = postJson(client, port, "/generated/procedures/CreateItem12User", Map.of(
                    "executionId", "exec-item12-2",
                    "correlationId", "corr-item12-1",
                    "idempotencyKey", "idem-item12-1"
            ));
            assertEquals("ok", repeat.get("status"), () -> "repeat response: " + repeat);
            assertEquals(0, number(repeat.get("createdCount")), () -> "repeat response: " + repeat);
            assertEquals("generated.action.CreateItem12User", repeat.get("capabilityId"));
            assertTrue(String.valueOf(repeat.get("capabilityDispatchStatus")).startsWith("prevented: idempotency reused"),
                    () -> "repeat response: " + repeat);
            assertTrue(String.valueOf(repeat.get("idempotencyStatus")).startsWith("reused:"), () -> "repeat response: " + repeat);

            Map<String, Object> executionViewer = getJson(client, port, "/generated/actions/executions/exec-item12-1");
            Map<String, Object> correlationViewer = getJson(client, port, "/generated/actions/correlations/corr-item12-1");
            Map<String, Object> missingViewer = getJson(client, port, "/generated/actions/executions/missing-exec-item14");
            assertEquals("exec-item12-1", executionViewer.get("executionId"), () -> "execution viewer: " + executionViewer);
            assertEquals("corr-item12-1", executionViewer.get("correlationId"), () -> "execution viewer: " + executionViewer);
            assertEquals("corr-item12-1", correlationViewer.get("correlationId"), () -> "correlation viewer: " + correlationViewer);
            assertEquals("CreateItem12User", correlationViewer.get("actionName"), () -> "correlation viewer: " + correlationViewer);
            assertEquals("generated.action.CreateItem12User", correlationViewer.get("capabilityId"), () -> "correlation viewer: " + correlationViewer);
            assertTrue(number(correlationViewer.get("eventCount")) >= 1, () -> "correlation viewer: " + correlationViewer);
            assertTrue(number(correlationViewer.get("traceCount")) >= 2, () -> "correlation viewer: " + correlationViewer);
            assertTrue(number(correlationViewer.get("auditCount")) >= 3, () -> "correlation viewer: " + correlationViewer);
            assertEquals(1, number(correlationViewer.get("idempotencyCount")), () -> "correlation viewer: " + correlationViewer);
            assertEquals(1, number(correlationViewer.get("correlationOwnerCount")), () -> "correlation viewer: " + correlationViewer);
            assertEquals("not_found", missingViewer.get("status"), () -> "missing viewer: " + missingViewer);
            assertTrue(String.valueOf(missingViewer.get("warnings")).contains("correlationId"),
                    () -> "missing viewer: " + missingViewer);

            String panelHtml = getText(client, port, "/item12-panel");
            String bridgeJs = getText(client, port, "/generated/trusted-source/npdev-panel-runtime.js");
            assertTrue(panelHtml.contains("/generated/trusted-source/npdev-panel-runtime.js"), panelHtml);
            assertTrue(bridgeJs.contains("window.NPDev.renderActionResultHtml = function(response)"), bridgeJs);
            assertTrue(bridgeJs.contains("data-npdev-execution-id"), bridgeJs);
            assertTrue(bridgeJs.contains("data-npdev-execution-evidence-link"), bridgeJs);
            assertTrue(bridgeJs.contains("data-npdev-correlation-evidence-link"), bridgeJs);
            assertTrue(bridgeJs.contains("data-npdev-evidence-link-status"), bridgeJs);
            Map<String, Object> evidence = getJson(client, port, "/item12/proof/evidence");
            assertEquals("H2/JDBC packaged runtime proof", evidence.get("proofType"));
            assertEquals(1, number(evidence.get("businessRows")), () -> "evidence: " + evidence);
            assertEquals(1, number(evidence.get("npdevEventStoreRows")), () -> "evidence: " + evidence);
            assertEquals(2, number(evidence.get("npdevTraceRows")), () -> "evidence: " + evidence);
            assertTrue(number(evidence.get("npdevAuditLogRows")) >= 3, () -> "evidence: " + evidence);
            assertEquals(1, number(evidence.get("npdevIdempotencyRows")), () -> "evidence: " + evidence);
            assertEquals(1, number(evidence.get("npdevCorrelationOwnerRows")), () -> "evidence: " + evidence);
            assertEquals(1, number(evidence.get("dispatcherInvocations")), () -> "evidence: " + evidence);
            assertEquals(1, number(evidence.get("providerInvocations")), () -> "evidence: " + evidence);
            assertEquals(1, number(evidence.get("handlerInvocations")), () -> "evidence: " + evidence);
            assertEquals(1, number(evidence.get("procedureInvocations")), () -> "evidence: " + evidence);

            Map<String, Object> flowStart = postJson(client, port, "/generated/flows/CreateItem12UserFlow/start", Map.of(
                    "executionId", "exec-item15-flow-request-1",
                    "correlationId", "corr-item15-flow-1",
                    "idempotencyKey", "idem-item15-flow-1"
            ));
            assertEquals("ok", flowStart.get("status"), () -> "flow start response: " + flowStart);
            assertEquals("CreateItem12UserFlow", flowStart.get("flowName"), () -> "flow start response: " + flowStart);
            assertEquals("COMPLETED", flowStart.get("flowInstanceStatus"), () -> "flow start response: " + flowStart);
            assertEquals("corr-item15-flow-1", flowStart.get("correlationId"), () -> "flow start response: " + flowStart);
            assertEquals("generated.action.CreateItem12User", flowStart.get("capabilityId"), () -> "flow start response: " + flowStart);
            assertTrue(String.valueOf(flowStart.get("capabilityDispatchStatus")).startsWith("dispatched: KernelRunner -> CapabilityDispatcher"),
                    () -> "flow start response: " + flowStart);
            assertEquals(1, number(flowStart.get("createdCount")), () -> "flow start response: " + flowStart);
            assertEquals(1, number(flowStart.get("sideEffectCountBefore")), () -> "flow start response: " + flowStart);
            assertEquals(2, number(flowStart.get("sideEffectCountAfter")), () -> "flow start response: " + flowStart);
            assertTrue(String.valueOf(flowStart.get("flowStartIdempotencyStatus")).startsWith("recorded: generated flow-start idempotency guard"),
                    () -> "flow start response: " + flowStart);

            Map<String, Object> flowRepeat = postJson(client, port, "/generated/flows/CreateItem12UserFlow/start", Map.of(
                    "executionId", "exec-item15-flow-request-2",
                    "correlationId", "corr-item15-flow-1",
                    "idempotencyKey", "idem-item15-flow-1"
            ));
            assertEquals(flowStart.get("executionId"), flowRepeat.get("executionId"), () -> "flow repeat should replay original execution id: " + flowRepeat);
            assertTrue(String.valueOf(flowRepeat.get("flowStartIdempotencyStatus")).startsWith("reused: generated flow-start idempotency guard"),
                    () -> "flow repeat response: " + flowRepeat);
            assertEquals("ok", flowRepeat.get("status"), () -> "flow repeat response: " + flowRepeat);
            assertEquals("COMPLETED", flowRepeat.get("flowInstanceStatus"), () -> "flow repeat response: " + flowRepeat);
            assertEquals(0, number(flowRepeat.get("createdCount")), () -> "flow repeat response: " + flowRepeat);
            assertEquals(2, number(flowRepeat.get("sideEffectCountBefore")), () -> "flow repeat response: " + flowRepeat);
            assertEquals(2, number(flowRepeat.get("sideEffectCountAfter")), () -> "flow repeat response: " + flowRepeat);
            assertTrue(String.valueOf(flowRepeat.get("capabilityDispatchStatus")).startsWith("prevented: generated flow-start idempotency guard"),
                    () -> "flow repeat response: " + flowRepeat);

            Map<String, Object> waitingStart = postJson(client, port, "/generated/flows/AwaitThenCreateItem12UserFlow/start", Map.of(
                    "executionId", "exec-item16-waiting-flow-1",
                    "correlationId", "corr-item16-waiting-flow-1",
                    "idempotencyKey", "idem-item16-waiting-flow-1"
            ));
            assertEquals("waiting", waitingStart.get("status"), () -> "waiting start response: " + waitingStart);
            assertEquals("WAITING_EVENT", waitingStart.get("flowInstanceStatus"), () -> "waiting start response: " + waitingStart);
            assertEquals("corr-item16-waiting-flow-1", waitingStart.get("correlationId"), () -> "waiting start response: " + waitingStart);
            assertEquals(0, number(waitingStart.get("createdCount")), () -> "waiting start response: " + waitingStart);
            assertTrue(String.valueOf(waitingStart.get("capabilityDispatchStatus")).startsWith("waiting: KernelRunner persisted WAITING_EVENT"),
                    () -> "waiting start response: " + waitingStart);
            assertTrue(String.valueOf(waitingStart.get("flowStartIdempotencyStatus")).startsWith("recorded: generated flow-start idempotency guard"),
                    () -> "waiting start response: " + waitingStart);

            Map<String, Object> waitingStartRepeat = postJson(client, port, "/generated/flows/AwaitThenCreateItem12UserFlow/start", Map.of(
                    "executionId", "exec-item16-waiting-flow-repeat-1",
                    "correlationId", "corr-item16-waiting-flow-1",
                    "idempotencyKey", "idem-item16-waiting-flow-1"
            ));
            assertEquals("waiting", waitingStartRepeat.get("status"), () -> "waiting repeat response: " + waitingStartRepeat);
            assertEquals("WAITING_EVENT", waitingStartRepeat.get("flowInstanceStatus"), () -> "waiting repeat response: " + waitingStartRepeat);
            assertEquals(waitingStart.get("executionId"), waitingStartRepeat.get("executionId"), () -> "waiting repeat should replay original execution id: " + waitingStartRepeat);
            assertEquals(0, number(waitingStartRepeat.get("createdCount")), () -> "waiting repeat response: " + waitingStartRepeat);
            assertTrue(String.valueOf(waitingStartRepeat.get("capabilityDispatchStatus")).startsWith("prevented: generated flow-start idempotency guard"),
                    () -> "waiting repeat response: " + waitingStartRepeat);
            assertTrue(String.valueOf(waitingStartRepeat.get("flowStartIdempotencyStatus")).startsWith("reused: generated flow-start idempotency guard"),
                    () -> "waiting repeat response: " + waitingStartRepeat);

            String waitingExecutionId = String.valueOf(waitingStart.get("executionId"));
            assertTrue(!(waitingExecutionId == null || waitingExecutionId.isBlank() || "null".equals(waitingExecutionId)),
                    () -> "waiting start response missing executionId: " + waitingStart);

            Map<String, Object> waitingResume = postJson(client, port, "/generated/flows/AwaitThenCreateItem12UserFlow/events/item12.user.approved", Map.of(
                    "executionId", waitingExecutionId,
                    "correlationId", "corr-item16-waiting-flow-1",
                    "idempotencyKey", "idem-item16-waiting-flow-1",
                    "approved", true
            ));
            assertEquals("ok", waitingResume.get("status"), () -> "waiting resume response: " + waitingResume);
            assertEquals("COMPLETED", waitingResume.get("flowInstanceStatus"), () -> "waiting resume response: " + waitingResume);
            assertEquals("corr-item16-waiting-flow-1", waitingResume.get("correlationId"), () -> "waiting resume response: " + waitingResume);
            assertEquals(1, number(waitingResume.get("createdCount")), () -> "waiting resume response: " + waitingResume);
            assertTrue(String.valueOf(waitingResume.get("capabilityDispatchStatus")).startsWith("resumed: external event -> KernelRunner.resumeExecution"),
                    () -> "waiting resume response: " + waitingResume);

            String uiRenderProof = runNodeRendererProof(evidenceRoot, bridgeJs, action, repeat, flowStart, waitingStart, waitingResume);
            Files.writeString(evidenceRoot.resolve("ui-render-proof-output.txt"), uiRenderProof, StandardCharsets.UTF_8);
            Files.writeString(evidenceRoot.resolve("ui-flow-render-proof-output.txt"), uiRenderProof, StandardCharsets.UTF_8);
            Files.writeString(evidenceRoot.resolve("packaged-app-ui-proof-output.txt"),
                    "Temporary generated app path: " + finalAppRoot + System.lineSeparator()
                            + "Packaged app built with bootJar and booted from: " + jar + System.lineSeparator()
                            + "Generated panel route served over HTTP: /item12-panel -> contains npdev-panel-runtime.js" + System.lineSeparator()
                            + "Generated bridge resource served over HTTP: /generated/trusted-source/npdev-panel-runtime.js" + System.lineSeparator()
                            + "Action endpoint response metadata includes capabilityId=" + action.get("capabilityId") + System.lineSeparator()
                            + "Generated bridge includes execution/correlation evidence link hooks" + System.lineSeparator()
                            + "Flow start response rendered by served JS renderer: PASS" + System.lineSeparator()
                            + "Waiting/resume response rendered by served JS renderer: PASS" + System.lineSeparator()
                            + "UI/resource rendering proof: PASS" + System.lineSeparator(),
                    StandardCharsets.UTF_8);

            Map<String, Object> waitingViewer = getJson(client, port, "/generated/actions/correlations/corr-item16-waiting-flow-1");
            Map<String, Object> flowEvidenceExecution = getJson(client, port, "/generated/flows/executions/" + String.valueOf(flowStart.get("executionId")));
            Map<String, Object> flowEvidenceInstance = getJson(client, port, "/generated/flows/instances/" + String.valueOf(flowStart.get("flowInstanceId")));
            Map<String, Object> flowEvidenceCorrelation = getJson(client, port, "/generated/flows/correlations/corr-item15-flow-1");
            Map<String, Object> waitingFlowEvidenceCorrelation = getJson(client, port, "/generated/flows/correlations/corr-item16-waiting-flow-1");
            assertEquals("corr-item16-waiting-flow-1", waitingViewer.get("correlationId"), () -> "waiting viewer: " + waitingViewer);
            assertTrue(number(waitingViewer.get("eventCount")) >= 1, () -> "waiting viewer: " + waitingViewer);
            assertTrue(number(waitingViewer.get("traceCount")) >= 1, () -> "waiting viewer: " + waitingViewer);
            assertTrue(number(waitingViewer.get("auditCount")) >= 1, () -> "waiting viewer: " + waitingViewer);
            assertEquals("flow-execution", String.valueOf(flowEvidenceExecution.get("viewerType")), "flow execution viewer should identify viewer type");
            assertEquals("flow-instance", String.valueOf(flowEvidenceInstance.get("viewerType")), "flow instance viewer should identify viewer type");
            assertEquals("flow-correlation", String.valueOf(flowEvidenceCorrelation.get("viewerType")), "flow correlation viewer should identify viewer type");
            assertEquals("flow-correlation", String.valueOf(waitingFlowEvidenceCorrelation.get("viewerType")), "waiting flow correlation viewer should identify viewer type");
            assertEquals("available", String.valueOf(flowEvidenceExecution.get("sourceEvidenceStatus")), "flow execution viewer should delegate to available source evidence");
            assertEquals("available", String.valueOf(flowEvidenceCorrelation.get("sourceEvidenceStatus")), "flow correlation viewer should delegate to available source evidence");
            Files.writeString(evidenceRoot.resolve("flow-evidence-viewer-proof-output.txt"),
                    "Flow execution viewer endpoint: " + flowEvidenceExecution + System.lineSeparator()
                            + "Flow instance viewer endpoint: " + flowEvidenceInstance + System.lineSeparator()
                            + "Flow correlation viewer endpoint: " + flowEvidenceCorrelation + System.lineSeparator()
                            + "Waiting flow correlation viewer endpoint: " + waitingFlowEvidenceCorrelation + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.writeString(evidenceRoot.resolve("flow-start-idempotency-proof-output.txt"),
                    "Initial flow start: " + flowStart + System.lineSeparator()
                            + "Repeated flow start: " + flowRepeat + System.lineSeparator()
                            + "Initial waiting flow start: " + waitingStart + System.lineSeparator()
                            + "Repeated waiting flow start: " + waitingStartRepeat + System.lineSeparator(),
                    StandardCharsets.UTF_8);

            Map<String, Object> flowEvidence = getJson(client, port, "/item12/proof/evidence");
            assertEquals("H2/JDBC packaged runtime proof", flowEvidence.get("proofType"));
            assertTrue(number(flowEvidence.get("businessRows")) >= 3, () -> "flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("npdevFlowInstanceRows")) >= 1, () -> "flow evidence after flow-start replay should keep one completed-flow instance row: " + flowEvidence);
            assertTrue(number(flowEvidence.get("flowEventRows")) >= 1, () -> "flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("flowTraceRows")) >= 1, () -> "flow evidence after flow-start replay should not require duplicate completed-flow trace rows: " + flowEvidence);
            assertTrue(number(flowEvidence.get("flowAuditRows")) >= 1, () -> "flow evidence after flow-start replay should not require duplicate completed-flow audit rows: " + flowEvidence);
            assertTrue(number(flowEvidence.get("flowIdempotencyRows")) >= 1, () -> "flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("flowCorrelationOwnerRows")) >= 1, () -> "flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("waitingFlowInstanceRows")) >= 1, () -> "waiting flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("waitingFlowEventRows")) >= 2, () -> "waiting flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("waitingFlowTraceRows")) >= 1, () -> "waiting flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("waitingFlowAuditRows")) >= 1, () -> "waiting flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("waitingFlowIdempotencyRows")) >= 1, () -> "waiting flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("waitingFlowCorrelationOwnerRows")) >= 1, () -> "waiting flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("dispatcherInvocations")) >= 3, () -> "flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("providerInvocations")) >= 3, () -> "flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("handlerInvocations")) >= 3, () -> "flow evidence: " + flowEvidence);
            assertTrue(number(flowEvidence.get("procedureInvocations")) >= 3, () -> "flow evidence: " + flowEvidence);

            String httpOutput = "ACTION_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(action)
                    + System.lineSeparator()
                    + "PROCEDURE_REPEAT_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(repeat)
                    + System.lineSeparator()
                    + "FLOW_START_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(flowStart)
                    + System.lineSeparator()
                    + "FLOW_REPEAT_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(flowRepeat)
                    + System.lineSeparator()
                    + "WAITING_FLOW_START_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(waitingStart)
                    + System.lineSeparator()
                    + "WAITING_FLOW_RESUME_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(waitingResume)
                    + System.lineSeparator()
                    + "VIEWER_EXECUTION_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(executionViewer)
                    + System.lineSeparator()
                    + "VIEWER_CORRELATION_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(correlationViewer)
                    + System.lineSeparator()
                    + "VIEWER_MISSING_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(missingViewer)
                    + System.lineSeparator()
                    + "DB_EVIDENCE_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(flowEvidence)
                    + System.lineSeparator();
            Files.writeString(evidenceRoot.resolve("http-call-output.txt"), httpOutput, StandardCharsets.UTF_8);
            Files.writeString(evidenceRoot.resolve("flow-start-http-proof-output.txt"),
                    "FLOW_START_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(flowStart)
                            + System.lineSeparator()
                            + "FLOW_REPEAT_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(flowRepeat)
                            + System.lineSeparator()
                            + "WAITING_FLOW_START_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(waitingStart)
                            + System.lineSeparator()
                            + "WAITING_FLOW_RESUME_RESPONSE=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(waitingResume)
                            + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.writeString(evidenceRoot.resolve("npdev-flow-instance-sql-evidence-output.txt"),
                    "npdev_flow_instance SQL: " + flowEvidence.get("flowInstanceSql") + " -> "
                            + flowEvidence.get("npdevFlowInstanceRows") + System.lineSeparator()
                            + "Flow status proof: " + flowStart.get("flowInstanceStatus") + System.lineSeparator()
                            + "Waiting flow npdev_flow_instance SQL: " + flowEvidence.get("waitingFlowInstanceSql") + " -> "
                            + flowEvidence.get("waitingFlowInstanceRows") + System.lineSeparator()
                            + "Waiting flow start status: " + waitingStart.get("flowInstanceStatus") + System.lineSeparator()
                            + "Waiting flow resume status: " + waitingResume.get("flowInstanceStatus") + System.lineSeparator()
                            + "Repeat flow action idempotency proof: business rows stayed at "
                            + flowEvidence.get("businessRows") + ", handler invocations stayed at "
                            + flowEvidence.get("handlerInvocations") + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.writeString(evidenceRoot.resolve("viewer-http-output.txt"), httpOutput, StandardCharsets.UTF_8);
            Files.writeString(evidenceRoot.resolve("viewer-endpoint-proof-output.txt"),
                    viewerEndpointProofOutput(executionViewer, correlationViewer, missingViewer), StandardCharsets.UTF_8);
            Files.writeString(evidenceRoot.resolve("ui-evidence-link-proof-output.txt"),
                    uiEvidenceLinkProofOutput(bridgeJs, uiRenderProof), StandardCharsets.UTF_8);
            Files.writeString(evidenceRoot.resolve("jdbc-db-evidence-output.txt"), jdbcEvidenceOutput(jdbcUrl, flowEvidence), StandardCharsets.UTF_8);
            Files.writeString(evidenceRoot.resolve("dispatcher-path-proof.txt"), dispatcherProofOutput(flowEvidence), StandardCharsets.UTF_8);
        } finally {
            app.destroy();
            if (!app.waitFor(15, TimeUnit.SECONDS)) {
                app.destroyForcibly();
                app.waitFor(15, TimeUnit.SECONDS);
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    // best-effort cleanup; a locked file (e.g. a not-yet-released jar handle) should not fail the test
                }
            });
        }
    }

    private static CompiledModel compiledProofModel() {
        CompiledConcept concept = new CompiledConcept(
                "Item12User",
                "Item12User",
                "item12_users",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, true),
                        new CompiledField("name", "string", "String", false, true, false)
                )
        );
        CompiledProcedure procedure = new CompiledProcedure(
                "CreateItem12User",
                "Creates one Item 12 proof user through trusted source",
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of("ADMIN"),
                "record",
                "write",
                new CompiledGeneratedActionDescriptorSpec(
                        "CreateItem12User",
                        List.of("Item12User"),
                        "Item12User",
                        "generated.action.item12-user.completed",
                        "ITEM12_USER",
                        "record",
                        "record",
                        "claim",
                        true
                ),
                Map.of("trustedSourceEntrypoint", "trusted/CreateItem12UserProcedure.java")
        );
        CompiledPanel panel = new CompiledPanel(
                "item12-panel",
                "/item12-panel",
                "Item 12 Packaged Proof Panel",
                List.of(),
                null,
                List.of(),
                "",
                "",
                List.of(),
                Map.of(),
                Map.of("trustedSourceEntrypoint", "panel/item12-panel.html"),
                null
        );
        CompiledFlow flow = new CompiledFlow(
                "CreateItem12UserFlow",
                "Item12User",
                "sync",
                List.of(
                        new CompiledFlowStep(
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
                                        "generated.action.CreateItem12User",
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
                                "CreateItem12User"
                        ),
                        new CompiledFlowStep(
                                "emitFlowCompleted",
                                "emitEvent",
                                "",
                                "",
                                List.of(),
                                "generated.flow.CreateItem12UserFlow.completed",
                                "actionResult",
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
                                null,
                                null,
                                null
                        )
                ),
                null,
                null,
                null,
                true
        );
        CompiledFlow waitingFlow = new CompiledFlow(
                "AwaitThenCreateItem12UserFlow",
                "Item12User",
                "sync",
                List.of(
                        new CompiledFlowStep(
                                "waitForApproval",
                                "await",
                                "",
                                "",
                                List.of(),
                                null,
                                null,
                                Map.of(),
                                null,
                                List.of(),
                                List.of(),
                                "item12.user.approved",
                                "approval",
                                null,
                                Map.of(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        ),
                        new CompiledFlowStep(
                                "runGeneratedActionAfterApproval",
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
                                        "generated.action.CreateItem12User",
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
                                "CreateItem12User"
                        ),
                        new CompiledFlowStep(
                                "emitWaitingFlowCompleted",
                                "emitEvent",
                                "",
                                "",
                                List.of(),
                                "generated.flow.AwaitThenCreateItem12UserFlow.completed",
                                "actionResult",
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
                                null,
                                null,
                                null
                        )
                ),
                null,
                null,
                null,
                true
        );
        return new CompiledModel(
                "item12.packaged.proof",
                "1.0.0",
                "1.0.0",
                Map.of("Item12User", concept),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(flow, waitingFlow),
                List.of(),
                List.of(),
                List.of(),
                List.of(procedure),
                List.of(panel)
        );
    }

    private static Path writeTrustedSourceModel(Path runRoot) throws Exception {
        Path modelRoot = runRoot.resolve("model");
        Files.createDirectories(modelRoot.resolve("trusted"));
        Files.createDirectories(modelRoot.resolve("panel"));
        Path procedure = modelRoot.resolve("trusted/CreateItem12UserProcedure.java");
        Files.writeString(procedure, """
                import java.util.List;
                import java.util.Map;

                public class CreateItem12UserProcedure {
                    public static int invocationCount = 0;

                    public Map<String, Object> execute(NPDevProcedureContext context) {
                        invocationCount++;
                        String id = String.format("00000000-0000-0000-0000-%012d", invocationCount);
                        List<Map<String, Object>> saved = context.saveMany(
                                "Item12User",
                                List.of(Map.of("id", id, "name", "Ada Item12 " + invocationCount))
                        );
                        return Map.of("handlerInvoked", true, "savedCount", saved.size());
                    }
                }
                """, StandardCharsets.UTF_8);
        Path panel = modelRoot.resolve("panel/item12-panel.html");
        Files.writeString(panel, """
                <!doctype html>
                <html>
                  <body>
                    <main>
                      <h1>Item 12 Packaged Proof Panel</h1>
                      <button id="createItem12">Create Item 12 User</button>
                      <script>
                        window.NPDev.callProcedure("CreateItem12User", {
                          executionId: "exec-item12-panel",
                          correlationId: "corr-item12-panel"
                        });
                      </script>
                    </main>
                  </body>
                </html>
                """, StandardCharsets.UTF_8);
        Files.writeString(modelRoot.resolve("trusted-source-manifest.json"), """
                {
                  "schemaVersion": "npdev-trusted-source-manifest.v1",
                  "entries": [
                    {
                      "entryId": "create-item12-user",
                      "kind": "procedure",
                      "relativePath": "trusted/CreateItem12UserProcedure.java",
                      "language": "java",
                      "sha256": "%s",
                      "runtimeBinding": "procedure:CreateItem12User",
                      "className": "CreateItem12UserProcedure",
                      "method": "execute",
                      "requiredRole": "ADMIN",
                      "tenantScoped": true
                    },
                    {
                      "entryId": "panel-item12-proof",
                      "kind": "panel",
                      "relativePath": "panel/item12-panel.html",
                      "language": "html",
                      "sha256": "%s",
                      "runtimeBinding": "panel:/item12-panel",
                      "className": "",
                      "method": "",
                      "requiredRole": "ADMIN",
                      "tenantScoped": true
                    }
                  ]
                }
                """.formatted(sha256(procedure), sha256(panel)), StandardCharsets.UTF_8);
        Path modelSource = modelRoot.resolve("model.json");
        Files.writeString(modelSource, """
                {
                  "namespace": "item12.packaged.proof",
                  "dslVersion": "1.0.0",
                  "version": "1.0.0",
                  "concepts": [
                    {
                      "name": "Item12User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string", "required": true }
                      ]
                    }
                  ],
                  "procedures": [
                    {
                      "name": "CreateItem12User",
                      "steps": [{ "type": "return" }],
                      "actionDescriptor": {
                        "actionName": "CreateItem12User",
                        "affectedConcepts": ["Item12User"],
                        "sideEffectConcept": "Item12User",
                        "eventNameOnSuccess": "generated.action.item12-user.completed",
                        "auditResourceType": "ITEM12_USER",
                        "idempotencyPolicy": "record",
                        "tracePolicy": "record",
                        "correlationPolicy": "claim"
                      }
                    }
                  ],
                  "flows": [
                    {
                      "name": "CreateItem12UserFlow",
                      "concept": "Item12User",
                      "startEndpoint": true,
                      "steps": [
                        {
                          "name": "runGeneratedAction",
                          "type": "generatedAction",
                          "actionName": "CreateItem12User",
                          "args": ["input"],
                          "input": "input",
                          "output": "actionResult",
                          "policy": {
                            "idempotencyKeyField": "input.idempotencyKey"
                          }
                        },
                        {
                          "name": "emitFlowCompleted",
                          "type": "emitEvent",
                          "event": "generated.flow.CreateItem12UserFlow.completed",
                          "payload": "actionResult"
                        }
                      ]
                    },
                    {
                      "name": "AwaitThenCreateItem12UserFlow",
                      "concept": "Item12User",
                      "startEndpoint": true,
                      "steps": [
                        {
                          "name": "waitForApproval",
                          "type": "awaitEvent",
                          "awaitEvent": "item12.user.approved",
                          "awaitRef": "approval"
                        },
                        {
                          "name": "runGeneratedActionAfterApproval",
                          "type": "generatedAction",
                          "actionName": "CreateItem12User",
                          "args": ["input"],
                          "input": "input",
                          "output": "actionResult",
                          "policy": {
                            "idempotencyKeyField": "input.idempotencyKey"
                          }
                        },
                        {
                          "name": "emitWaitingFlowCompleted",
                          "type": "emitEvent",
                          "event": "generated.flow.AwaitThenCreateItem12UserFlow.completed",
                          "payload": "actionResult"
                        }
                      ]
                    },
                    {
                      "name": "SumItem12NamesForEachFlow",
                      "concept": "Item12User",
                      "startEndpoint": true,
                      "steps": [
                        {
                          "name": "emitPerNameEvent",
                          "type": "forEach",
                          "collection": "input.names",
                          "itemKey": "name",
                          "maxLoopIterations": 50,
                          "steps": [
                            {
                              "name": "emitNameSeen",
                              "type": "emitEvent",
                              "event": "generated.flow.SumItem12NamesForEachFlow.nameSeen",
                              "payload": "name"
                            }
                          ]
                        },
                        {
                          "name": "returnInput",
                          "type": "return",
                          "value": "input"
                        }
                      ]
                    }
                  ],
                  "panels": [
                    {
                      "name": "item12-panel",
                      "route": "/item12-panel",
                      "title": "Item 12 Packaged Proof Panel",
                      "metadata": {
                        "trustedSourceEntrypoint": "panel/item12-panel.html"
                      }
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        return modelSource;
    }

    private static GeneratedDatabasePlan h2JdbcPlan(Path runRoot, Path modelSource) {
        return new GeneratedDatabasePlan(
                "item12-packaged-proof",
                DatabaseEngine.H2_LOCAL,
                "jdbc",
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "item12_packaged_proof",
                "item12_packaged_proof",
                "item12-proof",
                runRoot.resolve("runtime-data").toString(),
                "item12-proof-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:item12_packaged_proof;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "org.h2.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                true,
                true,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE,
                        false,
                        "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE
                ),
                "sha256:item12-packaged-proof",
                modelSource,
                List.of("item12-packaged-proof")
        );
    }

    private static void writePackagedProofController(Path finalAppRoot) throws IOException {
        Path source = finalAppRoot.resolve("src/main/java/com/finalexec/item12/Item12PackagedProofController.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.finalexec.item12;

                import com.npdev.generated.trusted.CreateItem12UserProcedure;
                import com.npdev.generated.trusted.GeneratedActionCapabilityDispatcherFactory;
                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                import javax.sql.DataSource;
                import java.sql.Connection;
                import java.sql.ResultSet;
                import java.sql.Statement;
                import java.util.LinkedHashMap;
                import java.util.Map;

                @RestController
                public class Item12PackagedProofController {
                    private final DataSource dataSource;

                    public Item12PackagedProofController(DataSource dataSource) {
                        this.dataSource = dataSource;
                    }

                    @GetMapping("/item12/proof/evidence")
                    public Map<String, Object> evidence() throws Exception {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("proofType", "H2/JDBC packaged runtime proof");
                        out.put("businessSql", "SELECT COUNT(*) FROM item12_users");
                        out.put("eventSql", "SELECT COUNT(*) FROM npdev_event_store WHERE tenant_id = 'dev' AND correlation_id = 'corr-item12-1'");
                        out.put("traceSql", "SELECT COUNT(*) FROM npdev_trace WHERE tenant_id = 'dev' AND correlation_id = 'corr-item12-1'");
                        out.put("auditSql", "SELECT COUNT(*) FROM npdev_audit_log WHERE tenant_id = 'dev' AND resource_id IN ('exec-item12-1', 'exec-item12-2')");
                        out.put("idempotencySql", "SELECT COUNT(*) FROM npdev_idempotency WHERE tenant_id = 'dev' AND idempotency_key = 'idem-item12-1'");
                        out.put("correlationSql", "SELECT COUNT(*) FROM npdev_correlation_owner WHERE tenant_id = 'dev' AND correlation_id = 'corr-item12-1'");
                        out.put("flowInstanceSql", "SELECT COUNT(*) FROM npdev_flow_instance WHERE tenant_id = 'dev' AND correlation_id = 'corr-item15-flow-1' AND status = 'COMPLETED'");
                        out.put("flowEventSql", "SELECT COUNT(*) FROM npdev_event_store WHERE tenant_id = 'dev' AND correlation_id = 'corr-item15-flow-1'");
                        out.put("flowTraceSql", "SELECT COUNT(*) FROM npdev_trace WHERE tenant_id = 'dev' AND correlation_id = 'corr-item15-flow-1'");
                        out.put("flowAuditSql", "SELECT COUNT(*) FROM npdev_audit_log WHERE tenant_id = 'dev' AND resource_id IN (SELECT execution_id FROM npdev_flow_instance WHERE tenant_id = 'dev' AND correlation_id = 'corr-item15-flow-1')");
                        out.put("flowIdempotencySql", "SELECT COUNT(*) FROM npdev_idempotency WHERE tenant_id = 'dev' AND idempotency_key = 'idem-item15-flow-1'");
                        out.put("flowCorrelationSql", "SELECT COUNT(*) FROM npdev_correlation_owner WHERE tenant_id = 'dev' AND correlation_id = 'corr-item15-flow-1'");
                        out.put("waitingFlowInstanceSql", "SELECT COUNT(*) FROM npdev_flow_instance WHERE tenant_id = 'dev' AND correlation_id = 'corr-item16-waiting-flow-1' AND status = 'COMPLETED'");
                        out.put("waitingFlowEventSql", "SELECT COUNT(*) FROM npdev_event_store WHERE tenant_id = 'dev' AND correlation_id = 'corr-item16-waiting-flow-1'");
                        out.put("waitingFlowTraceSql", "SELECT COUNT(*) FROM npdev_trace WHERE tenant_id = 'dev' AND correlation_id = 'corr-item16-waiting-flow-1'");
                        out.put("waitingFlowAuditSql", "SELECT COUNT(*) FROM npdev_audit_log WHERE tenant_id = 'dev' AND resource_id IN (SELECT execution_id FROM npdev_flow_instance WHERE tenant_id = 'dev' AND correlation_id = 'corr-item16-waiting-flow-1')");
                        out.put("waitingFlowIdempotencySql", "SELECT COUNT(*) FROM npdev_idempotency WHERE tenant_id = 'dev' AND idempotency_key = 'idem-item16-waiting-flow-1'");
                        out.put("waitingFlowCorrelationSql", "SELECT COUNT(*) FROM npdev_correlation_owner WHERE tenant_id = 'dev' AND correlation_id = 'corr-item16-waiting-flow-1'");
                        out.put("businessRows", count((String) out.get("businessSql")));
                        out.put("npdevEventStoreRows", count((String) out.get("eventSql")));
                        out.put("npdevTraceRows", count((String) out.get("traceSql")));
                        out.put("npdevAuditLogRows", count((String) out.get("auditSql")));
                        out.put("npdevIdempotencyRows", count((String) out.get("idempotencySql")));
                        out.put("npdevCorrelationOwnerRows", count((String) out.get("correlationSql")));
                        out.put("npdevFlowInstanceRows", count((String) out.get("flowInstanceSql")));
                        out.put("flowEventRows", count((String) out.get("flowEventSql")));
                        out.put("flowTraceRows", count((String) out.get("flowTraceSql")));
                        out.put("flowAuditRows", count((String) out.get("flowAuditSql")));
                        out.put("flowIdempotencyRows", count((String) out.get("flowIdempotencySql")));
                        out.put("flowCorrelationOwnerRows", count((String) out.get("flowCorrelationSql")));
                        out.put("waitingFlowInstanceRows", count((String) out.get("waitingFlowInstanceSql")));
                        out.put("waitingFlowEventRows", count((String) out.get("waitingFlowEventSql")));
                        out.put("waitingFlowTraceRows", count((String) out.get("waitingFlowTraceSql")));
                        out.put("waitingFlowAuditRows", count((String) out.get("waitingFlowAuditSql")));
                        out.put("waitingFlowIdempotencyRows", count((String) out.get("waitingFlowIdempotencySql")));
                        out.put("waitingFlowCorrelationOwnerRows", count((String) out.get("waitingFlowCorrelationSql")));
                        out.put("dispatcherInvocations", GeneratedActionCapabilityDispatcherFactory.dispatcherInvocations());
                        out.put("providerInvocations", GeneratedActionCapabilityDispatcherFactory.providerInvocations());
                        out.put("handlerInvocations", GeneratedActionCapabilityDispatcherFactory.handlerInvocations());
                        out.put("procedureInvocations", CreateItem12UserProcedure.invocationCount);
                        out.put("dispatchPath", "GeneratedActionKernelRunner -> CapabilityDispatcher -> GeneratedActionCapabilityAdapter -> GeneratedActionRegistry handler -> KernelFacade/JDBC evidence path");
                        return out;
                    }

                    private int count(String sql) throws Exception {
                        try (Connection connection = dataSource.getConnection();
                             Statement statement = connection.createStatement();
                             ResultSet resultSet = statement.executeQuery(sql)) {
                            resultSet.next();
                            return resultSet.getInt(1);
                        }
                    }
                }
                """, StandardCharsets.UTF_8);
    }

    private static Path ensureRuntimeHostLibs(Path evidenceRoot) throws Exception {
        return withKernelBuildLock(() -> doEnsureRuntimeHostLibs(evidenceRoot));
    }

    /**
     * CI_RED_PLAN.md I1 (2026-08-05): {@code HardenGcDeleteReplaceCascade...}, {@code
     * HardenObjstoreFileUpload...}, and {@code TrustedSourceEmitter...} each call this method,
     * which spawns its own {@code --no-daemon} Gradle subprocess against the SAME NPDevKernel
     * project directory. {@code generator/build.gradle}'s {@code test} task runs with {@code
     * maxParallelForks = 2}, so two of these three classes can run concurrently in separate
     * forked JVMs -- two independent, uncoordinated Gradle processes writing to the same
     * incremental-compilation state (e.g. {@code
     * Build/gradle/npdev-kernel/adapters/authz-default/tmp/compileJava/previous-compilation-data.bin})
     * corrupts it for whichever one loses the race: {@code Cannot access output property
     * 'previousCompilationData' ... Failed to create MD5 hash for file ... as it does not
     * exist}. Reproduced live by running all three together: all three failed (with three
     * different symptoms) in the same run; the failure vanished running any one alone. A
     * cross-process file lock is required -- JUnit 5's own {@code @ResourceLock} only
     * coordinates within one JVM's thread pool, not across Gradle's separately forked test-worker
     * processes.
     */
    private static <T> T withKernelBuildLock(java.util.concurrent.Callable<T> action) throws Exception {
        java.nio.file.Path lockFile = WORKSPACE_ROOT.resolve("Build").resolve("npdev-kernel-adapter-build.lock");
        Files.createDirectories(lockFile.getParent());
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                lockFile, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock lock = channel.lock()) {
            return action.call();
        }
    }

    private static Path doEnsureRuntimeHostLibs(Path evidenceRoot) throws Exception {
        Path runtimeHostLibs = OUTSIDE_ROOT.resolve("runtimehost-libs").toAbsolutePath().normalize();
        Path manifest = runtimeHostLibs.resolve("runtimehost-libs-manifest.json");
        CommandResult adapterJars = runCommand(
                List.of(
                        gradlewPath(WORKSPACE_ROOT.resolve("NPDevKernel")).toString(),
                        ":adapters:auth-context-jwt:jar",
                        ":adapters:authz-default:jar",
                        ":adapters:bulkhead-inproc:jar",
                        ":adapters:bulkhead-postgres:jar",
                        ":adapters:circuit-inproc:jar",
                        ":adapters:circuit-postgres:jar",
                        // REG-12 Slice 3: NpdevDocumentRenderConfig imports both document-render
                        // adapter classes unconditionally (same reason the mail adapters are listed
                        // below) -- both jars must exist or the generated app fails to compile.
                        ":adapters:document-render-inproc:jar",
                        ":adapters:document-render-stub:jar",
                        ":adapters:expression-cel:jar",
                        ":adapters:external-ai-http:jar",
                        ":adapters:external-ai-inproc:jar",
                        ":adapters:external-ai-pack-core:jar",
                        ":adapters:file-store-inproc:jar",
                        ":adapters:file-store-objectstore:jar",
                        ":adapters:flow-compiled:jar",
                        ":adapters:json-jackson:jar",
                        ":adapters:metrics-micrometer:jar",
                        ":adapters:notification-inproc:jar",
                        // REG-10: the RuntimeHost template's NpdevPluginConfig imports the mail adapters,
                        // so the generated app cannot compile without their jars. On the dev machine these
                        // were already present in the libs dir from prior builds (masking the gap); on a
                        // clean CI runner only explicitly-built adapters exist -> compile error. Build them.
                        ":adapters:mail-inproc:jar",
                        ":adapters:mail-smtp:jar",
                        ":adapters:persistence-inproc:jar",
                        ":adapters:persistence-postgres:jar",
                        ":adapters:resume-bootstrap-spring:jar",
                        ":adapters:runtime-validation:jar",
                        ":adapters:schema-validator-default:jar",
                        ":adapters:tracing-redaction-default:jar",
                        ":adapters:webhook-inproc:jar",
                        "--no-daemon",
                        "--console=plain"
                ),
                WORKSPACE_ROOT.resolve("NPDevKernel"),
                Map.of(),
                Duration.ofMinutes(5)
        );
        assertEquals(0, adapterJars.exitCode(), adapterJars.output());

        Path report = evidenceRoot.resolve("runtimehost-libs-sync-report.json");
        CommandResult result = runCommand(
                List.of(
                        // REG-10/LNCH-20: resolve PowerShell 7 via PATH ("pwsh"), not a hardcoded
                        // Windows install path. The absolute "C:\Program Files (x86)\PowerShell\7\pwsh.exe"
                        // does not exist on a Linux CI runner, so ProcessBuilder.start() threw
                        // java.io.IOException (No such file or directory) -- the first-ever GitHub Actions
                        // run caught exactly this. "pwsh" is on PATH on both Windows (confirmed 7.x) and the
                        // GitHub ubuntu-latest runner (PowerShell 7 preinstalled); -ExecutionPolicy is a
                        // harmless no-op on Linux.
                        "pwsh",
                        "-NoProfile",
                        "-ExecutionPolicy",
                        "Bypass",
                        "-File",
                        WORKSPACE_ROOT.resolve("scripts/runtimehost/sync-runtimehost-libs.ps1").toString(),
                        "-WorkspaceRoot",
                        WORKSPACE_ROOT.toString(),
                        "-RuntimeHostLibs",
                        runtimeHostLibs.toString(),
                        "-ReportPath",
                        report.toString()
                ),
                WORKSPACE_ROOT,
                Map.of(),
                Duration.ofMinutes(4)
        );
        Files.writeString(evidenceRoot.resolve("runtimehost-libs-output.txt"),
                "TARGETED_ADAPTER_JARS_BUILD=" + System.lineSeparator()
                        + adapterJars.output()
                        + System.lineSeparator()
                        + "RUNTIMEHOST_LIBS_SYNC=" + System.lineSeparator()
                        + result.output(),
                StandardCharsets.UTF_8);
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.isRegularFile(manifest), "RuntimeHost libs manifest must exist after sync: " + manifest);
        return runtimeHostLibs;
    }

    private static Process startPackagedApp(
            Path finalAppRoot,
            Path jar,
            int port,
            String jdbcUrl,
            Path runtimeHostLibs,
            Path evidenceRoot
    ) throws IOException {
        Path bootLog = evidenceRoot.resolve("packaged-app-boot-output.txt");
        ProcessBuilder builder = new ProcessBuilder(
                "java",
                "-jar",
                jar.toString(),
                "--server.port=" + port,
                "--npdev.storage.mode=jdbc",
                "--npdev.database.engine=H2",
                "--npdev.auth.enabled=false",
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
                "--spring.flyway.enabled=true",
                "--spring.flyway.locations=classpath:db/schema-realization"
        );
        builder.directory(finalAppRoot.toFile());
        builder.environment().put("NPDEV_RUNTIMEHOST_LIBS_DIR", runtimeHostLibs.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        Thread logThread = new Thread(() -> copyProcessOutput(process, bootLog), "item12-packaged-app-log");
        logThread.setDaemon(true);
        logThread.start();
        return process;
    }

    /** How long a packaged app may take to answer /actuator/health. See waitForHealth. */
    private static final Duration HEALTH_TIMEOUT = Duration.ofMinutes(6);

    private static void waitForHealth(int port, Path evidenceRoot) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI uri = URI.create("http://localhost:" + port + "/actuator/health");
        // F7 (POST_PROGRAMME_AUDIT_PLAN §2.4): 2 minutes was not enough. Gradle runs this suite
        // with maxParallelForks = 2, so this test's packaged Spring Boot app can be booting at the
        // same time as the other packaged-app proof's -- two JVMs each starting Tomcat, Flyway and
        // a datasource, on a machine already busy compiling. That produced three intermittent
        // "did not become healthy" failures across the 2026-07-25 security programme, every one of
        // which passed on an isolated re-run. The app is not broken; it is queued behind the other
        // one. Raised to 6 minutes, which is well clear of the observed worst case and still far
        // below the 12-minute @Timeout on the test itself, so a genuine boot failure is still
        // caught -- just later.
        Instant deadline = Instant.now().plus(HEALTH_TIMEOUT);
        Exception last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    Files.writeString(evidenceRoot.resolve("packaged-app-readiness-output.txt"),
                            "READY " + response.statusCode() + " " + response.body(),
                            StandardCharsets.UTF_8);
                    return;
                }
            } catch (Exception exception) {
                last = exception;
            }
            Thread.sleep(1000L);
        }
        // Say how long we actually waited: "did not become healthy" alone cost a diagnosis cycle
        // every time it fired, because it does not distinguish "app crashed" from "app was slow".
        throw new IllegalStateException(
                "Packaged app did not become healthy on port " + port + " within " + HEALTH_TIMEOUT
                        + " (see " + evidenceRoot.resolve("packaged-app-boot-output.txt") + "). If the log shows a"
                        + " normal startup that simply ran past the deadline, this is F7's fork-contention"
                        + " flake, not a regression -- re-run this test alone to confirm.", last);
    }

    private static Map<String, Object> postJson(HttpClient client, int port, String path, Map<String, Object> payload)
            throws Exception {
        String json = OBJECT_MAPPER.writeValueAsString(payload);
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "HTTP " + response.statusCode() + " for " + path + ": " + response.body());
        return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
    }

    private static Map<String, Object> getJson(HttpClient client, int port, String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "HTTP " + response.statusCode() + " for " + path + ": " + response.body());
        return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
    }

    private static String getText(HttpClient client, int port, String path) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "HTTP " + response.statusCode() + " for " + path + ": " + response.body());
        return response.body();
    }

    private static String runNodeRendererProof(
            Path evidenceRoot,
            String bridgeJs,
            Map<String, Object> action,
            Map<String, Object> repeat,
            Map<String, Object> flowStart,
            Map<String, Object> waitingStart,
            Map<String, Object> waitingResume
    ) throws Exception {
        Path bridge = evidenceRoot.resolve("served-npdev-panel-runtime.js");
        Files.writeString(bridge, bridgeJs, StandardCharsets.UTF_8);
        Path script = evidenceRoot.resolve("item13-render-proof.js");
        Files.writeString(script, """
                const fs = require('fs');
                const vm = require('vm');
                const bridge = fs.readFileSync(process.argv[2], 'utf8');
                const success = %s;
                const repeat = %s;
                const flowStart = %s;
                const waitingStart = %s;
                const waitingResume = %s;
                const failure = {
                  status: 'failed',
                  executionId: 'exec-error',
                  correlationId: 'corr-error',
                  actionName: 'CreateItem12User',
                  procedureName: 'CreateItem12User',
                  capabilityId: 'generated.action.CreateItem12User',
                  capabilityDispatchStatus: 'failed: simulated dispatch failure',
                  eventStatus: 'failed: simulated event failure',
                  traceStatus: 'written: executionId=exec-error',
                  auditStatus: 'written: auditId=audit-error',
                  idempotencyStatus: 'disabled: no idempotency key',
                  correlationStatus: 'owned: correlationId=corr-error',
                  createdCount: 0,
                  sideEffectCountBefore: 1,
                  sideEffectCountAfter: 1,
                  message: 'handler failed',
                  error: 'simulated failure'
                };
                const missing = {
                  status: 'ok',
                  executionId: null,
                  idempotencyStatus: 'unavailable: not configured'
                };
                const document = {
                  body: { appendChild() {} },
                  querySelector() { return null; },
                  createElement() {
                    return {
                      setAttribute() {},
                      appendChild() {},
                      innerHTML: ''
                    };
                  }
                };
                const context = { window: {}, document };
                vm.runInNewContext(bridge, context);
                const render = context.window.NPDev.renderActionResultHtml;
                const renderFlow = context.window.NPDev.renderFlowResultHtml;
                function assertVisible(condition, message) {
                  if (!condition) {
                    throw new Error(message);
                  }
                }
                function requireContains(html, value, label) {
                  assertVisible(html.includes(value), label + ' missing: ' + value + '\\n' + html);
                }
                const requiredHooks = [
                  'data-npdev-action-result',
                  'data-npdev-execution-id',
                  'data-npdev-correlation-id',
                  'data-npdev-action-name',
                  'data-npdev-procedure-name',
                  'data-npdev-capability-id',
                  'data-npdev-dispatch-status',
                  'data-npdev-event-status',
                  'data-npdev-trace-status',
                  'data-npdev-audit-status',
                  'data-npdev-idempotency-status',
                  'data-npdev-correlation-status',
                  'data-npdev-created-count',
                  'data-npdev-side-effect-before',
                  'data-npdev-side-effect-after',
                  'data-npdev-message',
                  'data-npdev-error',
                  'data-npdev-execution-evidence-link',
                  'data-npdev-correlation-evidence-link',
                  'data-npdev-evidence-link-status'
                ];
                const successHtml = render(success);
                const repeatHtml = render(repeat);
                const failureHtml = render(failure);
                const missingHtml = render(missing);
                assertVisible(typeof context.window.NPDev.startFlow === 'function', 'startFlow API missing from served JS');
                assertVisible(typeof context.window.NPDev.resumeFlow === 'function', 'resumeFlow API missing from served JS');
                assertVisible(typeof renderFlow === 'function', 'renderFlowResultHtml API missing from served JS');
                const flowStartHtml = renderFlow(flowStart);
                const waitingStartHtml = renderFlow(waitingStart);
                const waitingResumeHtml = renderFlow(waitingResume);
                const flowMissingHtml = renderFlow({ status: 'ok', flowName: null });
                const requiredFlowHooks = [
                  'data-npdev-flow-result',
                  'data-npdev-flow-name',
                  'data-npdev-flow-instance-id',
                  'data-npdev-flow-status',
                  'data-npdev-execution-id',
                  'data-npdev-correlation-id',
                  'data-npdev-waiting-status',
                  'data-npdev-resume-status',
                  'data-npdev-capability-id',
                  'data-npdev-dispatch-status',
                  'data-npdev-event-status',
                  'data-npdev-trace-status',
                  'data-npdev-audit-status',
                  'data-npdev-idempotency-status',
                  'data-npdev-correlation-status',
                  'data-npdev-created-count',
                  'data-npdev-side-effect-before',
                  'data-npdev-side-effect-after',
                  'data-npdev-flow-message',
                  'data-npdev-flow-error',
                  'data-npdev-flow-evidence-link',
                  'data-npdev-flow-correlation-evidence-link'
                ];
                for (const hook of requiredFlowHooks) {
                  requireContains(flowStartHtml, hook, 'flow start hook');
                }
                requireContains(flowStartHtml, 'CreateItem12UserFlow', 'flow start name');
                requireContains(flowStartHtml, 'COMPLETED', 'flow start status');
                requireContains(flowStartHtml, '/generated/flows/correlations/corr-item15-flow-1', 'flow start correlation evidence link');
                requireContains(waitingStartHtml, 'AwaitThenCreateItem12UserFlow', 'waiting flow name');
                requireContains(waitingStartHtml, 'WAITING_EVENT', 'waiting flow status');
                requireContains(waitingResumeHtml, 'AwaitThenCreateItem12UserFlow', 'waiting resume flow name');
                requireContains(waitingResumeHtml, 'COMPLETED', 'waiting resume completed status');
                requireContains(waitingResumeHtml, '/generated/flows/correlations/corr-item16-waiting-flow-1', 'waiting resume correlation evidence link');
                requireContains(flowMissingHtml, 'unavailable: runtime returned null', 'flow null visibility');
                requireContains(flowMissingHtml, 'unavailable: not returned by runtime', 'flow missing visibility');
                for (const hook of requiredHooks) {
                  requireContains(successHtml, hook, 'success hook');
                }
                requireContains(successHtml, 'exec-item12-1', 'success execution id');
                requireContains(successHtml, 'generated.action.CreateItem12User', 'success capability id');
                requireContains(successHtml, 'dispatched:', 'success dispatch status');
                requireContains(successHtml, 'Action completed', 'success state');
                requireContains(successHtml, '/generated/actions/executions/exec-item12-1', 'execution evidence link');
                requireContains(successHtml, '/generated/actions/correlations/corr-item12-1', 'correlation evidence link');
                requireContains(successHtml, 'View execution evidence', 'execution evidence label');
                requireContains(successHtml, 'View correlation evidence', 'correlation evidence label');
                requireContains(repeatHtml, 'Action reused / duplicate prevented', 'reuse state');
                requireContains(repeatHtml, 'prevented: idempotency reused', 'reuse dispatch status');
                requireContains(repeatHtml, 'reused:', 'reuse idempotency status');
                requireContains(failureHtml, 'Action failed', 'failure state');
                requireContains(failureHtml, 'simulated failure', 'failure error');
                requireContains(failureHtml, 'failed: simulated event failure', 'failure event status');
                requireContains(missingHtml, 'unavailable: runtime returned null', 'null visibility');
                requireContains(missingHtml, 'unavailable: not returned by runtime', 'missing visibility');
                requireContains(missingHtml, 'Evidence link unavailable: executionId/correlationId not returned by runtime', 'missing evidence link status');
                console.log('generated UI renders success metadata: PASS');
                console.log('generated UI renders failure metadata: PASS');
                console.log('generated UI renders idempotency reuse/prevented metadata: PASS');
                console.log('stable data-npdev-* hooks are present: PASS');
                console.log('generated UI renders evidence links and missing-link reason: PASS');
                console.log('generated UI renders real flow start response metadata: PASS');
                console.log('generated UI renders real waiting/resume flow response metadata: PASS');
                console.log('stable data-npdev-flow-* hooks are present: PASS');
                console.log('unavailable/disabled/failed statuses are visible, not hidden: PASS');
                """.formatted(
                        OBJECT_MAPPER.writeValueAsString(action),
                        OBJECT_MAPPER.writeValueAsString(repeat),
                        OBJECT_MAPPER.writeValueAsString(flowStart),
                        OBJECT_MAPPER.writeValueAsString(waitingStart),
                        OBJECT_MAPPER.writeValueAsString(waitingResume)
                ), StandardCharsets.UTF_8);
        CommandResult result = runCommand(
                List.of("node", script.toString(), bridge.toString()),
                evidenceRoot,
                Map.of(),
                Duration.ofSeconds(30)
        );
        assertEquals(0, result.exitCode(), result.output());
        return result.output();
    }

    private static String jdbcEvidenceOutput(String jdbcUrl, Map<String, Object> evidence) throws Exception {
        return "DB type: H2/JDBC packaged runtime proof" + System.lineSeparator()
                + "JDBC URL: " + jdbcUrl + System.lineSeparator()
                + "business: " + evidence.get("businessSql") + " -> " + evidence.get("businessRows") + System.lineSeparator()
                + "npdev_event_store: " + evidence.get("eventSql") + " -> " + evidence.get("npdevEventStoreRows") + System.lineSeparator()
                + "npdev_trace: " + evidence.get("traceSql") + " -> " + evidence.get("npdevTraceRows") + System.lineSeparator()
                + "npdev_audit_log: " + evidence.get("auditSql") + " -> " + evidence.get("npdevAuditLogRows") + System.lineSeparator()
                + "npdev_idempotency: " + evidence.get("idempotencySql") + " -> " + evidence.get("npdevIdempotencyRows") + System.lineSeparator()
                + "npdev_correlation_owner: " + evidence.get("correlationSql") + " -> " + evidence.get("npdevCorrelationOwnerRows") + System.lineSeparator()
                + "npdev_flow_instance: " + evidence.get("flowInstanceSql") + " -> " + evidence.get("npdevFlowInstanceRows") + System.lineSeparator()
                + "flow npdev_event_store: " + evidence.get("flowEventSql") + " -> " + evidence.get("flowEventRows") + System.lineSeparator()
                + "flow npdev_trace: " + evidence.get("flowTraceSql") + " -> " + evidence.get("flowTraceRows") + System.lineSeparator()
                + "flow npdev_audit_log: " + evidence.get("flowAuditSql") + " -> " + evidence.get("flowAuditRows") + System.lineSeparator()
                + "flow npdev_idempotency: " + evidence.get("flowIdempotencySql") + " -> " + evidence.get("flowIdempotencyRows") + System.lineSeparator()
                + "flow npdev_correlation_owner: " + evidence.get("flowCorrelationSql") + " -> " + evidence.get("flowCorrelationOwnerRows") + System.lineSeparator()
                + "waiting flow npdev_flow_instance: " + evidence.get("waitingFlowInstanceSql") + " -> " + evidence.get("waitingFlowInstanceRows") + System.lineSeparator()
                + "waiting flow npdev_event_store: " + evidence.get("waitingFlowEventSql") + " -> " + evidence.get("waitingFlowEventRows") + System.lineSeparator()
                + "waiting flow npdev_trace: " + evidence.get("waitingFlowTraceSql") + " -> " + evidence.get("waitingFlowTraceRows") + System.lineSeparator()
                + "waiting flow npdev_audit_log: " + evidence.get("waitingFlowAuditSql") + " -> " + evidence.get("waitingFlowAuditRows") + System.lineSeparator()
                + "waiting flow npdev_idempotency: " + evidence.get("waitingFlowIdempotencySql") + " -> " + evidence.get("waitingFlowIdempotencyRows") + System.lineSeparator()
                + "waiting flow npdev_correlation_owner: " + evidence.get("waitingFlowCorrelationSql") + " -> " + evidence.get("waitingFlowCorrelationOwnerRows") + System.lineSeparator()
                + "idempotency repeat proof: business rows stayed at " + evidence.get("businessRows")
                + ", handler invocations stayed at " + evidence.get("handlerInvocations") + System.lineSeparator();
    }

    private static String viewerEndpointProofOutput(
            Map<String, Object> executionViewer,
            Map<String, Object> correlationViewer,
            Map<String, Object> missingViewer
    ) throws Exception {
        return "Routes: GET /generated/actions/executions/{executionId}, GET /generated/actions/correlations/{correlationId}" + System.lineSeparator()
                + "Lookup mode: correlationId primary; executionId resolves through existing trace/audit evidence" + System.lineSeparator()
                + "Execution viewer status: " + executionViewer.get("status") + ", evidenceStatus=" + executionViewer.get("evidenceStatus") + System.lineSeparator()
                + "Correlation viewer status: " + correlationViewer.get("status") + ", evidenceStatus=" + correlationViewer.get("evidenceStatus") + System.lineSeparator()
                + "eventCount=" + correlationViewer.get("eventCount") + System.lineSeparator()
                + "traceCount=" + correlationViewer.get("traceCount") + System.lineSeparator()
                + "auditCount=" + correlationViewer.get("auditCount") + System.lineSeparator()
                + "idempotencyCount=" + correlationViewer.get("idempotencyCount") + System.lineSeparator()
                + "correlationOwnerCount=" + correlationViewer.get("correlationOwnerCount") + System.lineSeparator()
                + "Missing execution lookup status: " + missingViewer.get("status") + ", warnings=" + missingViewer.get("warnings") + System.lineSeparator()
                + "Sample correlation response JSON=" + OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(correlationViewer) + System.lineSeparator();
    }

    private static String uiEvidenceLinkProofOutput(String bridgeJs, String uiRenderProof) {
        return "Generated UI includes evidence link: " + bridgeJs.contains("View execution evidence") + System.lineSeparator()
                + "Hook data-npdev-execution-evidence-link present: " + bridgeJs.contains("data-npdev-execution-evidence-link") + System.lineSeparator()
                + "Hook data-npdev-correlation-evidence-link present: " + bridgeJs.contains("data-npdev-correlation-evidence-link") + System.lineSeparator()
                + "Hook data-npdev-evidence-link-status present: " + bridgeJs.contains("data-npdev-evidence-link-status") + System.lineSeparator()
                + "Link uses executionId/correlationId from response: "
                + (bridgeJs.contains("npdevRawField(response, 'executionId')")
                && bridgeJs.contains("npdevRawField(response, 'correlationId')")) + System.lineSeparator()
                + "Missing ID renders unavailable reason: "
                + bridgeJs.contains("Evidence link unavailable: executionId/correlationId not returned by runtime") + System.lineSeparator()
                + "Node render proof:" + System.lineSeparator() + uiRenderProof;
    }

    private static String dispatcherProofOutput(Map<String, Object> evidence) {
        return "GeneratedActionKernelRunner entered via real HTTP controller: PASS" + System.lineSeparator()
                + "CapabilityDispatcher invoked: " + evidence.get("dispatcherInvocations") + System.lineSeparator()
                + "GeneratedActionCapabilityAdapter/provider invoked: " + evidence.get("providerInvocations") + System.lineSeparator()
                + "GeneratedActionRegistry handler invoked on first call: " + evidence.get("handlerInvocations") + System.lineSeparator()
                + "Procedure invocation counter after idempotent repeat: " + evidence.get("procedureInvocations") + System.lineSeparator()
                + "FlowCoda endpoint entered KernelFacade.executeFlow and persisted npdev_flow_instance rows: "
                + evidence.get("npdevFlowInstanceRows") + System.lineSeparator()
                + "Path: " + evidence.get("dispatchPath") + System.lineSeparator();
    }

    /** LNCH-20: the platform ships one gradlew per OS (no `.bat` on Linux/macOS); this test
     * hardcoded `gradlew.bat` unconditionally, which fails to exec at all on a Linux CI runner
     * (confirmed live). Also defensively marks the resolved wrapper executable -- a fresh copy
     * made by {@code FinalAppAssembler} (or any plain file copy) does not necessarily preserve
     * the source file's POSIX execute bit. */
    private static Path gradlewPath(Path root) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path gradlew = root.resolve(windows ? "gradlew.bat" : "gradlew");
        if (!windows) {
            gradlew.toFile().setExecutable(true);
        }
        return gradlew;
    }

    private static CommandResult runCommand(
            List<String> command,
            Path workingDirectory,
            Map<String, String> environment,
            Duration timeout
    ) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.environment().putAll(environment);
        Process process = builder.start();
        StringBuilder output = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    output.append(line).append(System.lineSeparator());
                }
            } catch (IOException exception) {
                output.append("Failed reading command output: ").append(exception).append(System.lineSeparator());
            }
        });
        reader.start();
        boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        reader.join(5000L);
        return new CommandResult(finished ? process.exitValue() : -1, output.toString());
    }

    private static Path findBootJar(Path finalAppRoot) throws IOException {
        Path libs = finalAppRoot.resolve("build/libs");
        try (Stream<Path> stream = Files.list(libs)) {
            Optional<Path> jar = stream
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().contains("plain"))
                    .findFirst();
            assertTrue(jar.isPresent(), "Expected bootJar under " + libs);
            return jar.get();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("localhost", 0));
            return socket.getLocalPort();
        }
    }

    private static int number(Object value) {
        assertNotNull(value, "Expected numeric value");
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static void copyProcessOutput(Process process, Path destination) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
                Files.write(destination, lines, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
        }
    }

    private static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    private static Path resolveWorkspaceRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("NPDevRuntimeHost"))
                    && Files.isDirectory(candidate.resolve("NPDevGenerator"))
                    && Files.isDirectory(candidate.resolve("NPDevContract"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to resolve NPDev_General workspace root from " + current);
    }

    private record CommandResult(int exitCode, String output) {
    }
}







