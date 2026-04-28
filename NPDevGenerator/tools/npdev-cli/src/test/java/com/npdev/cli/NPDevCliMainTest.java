package com.npdev.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NPDevCliMainTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper().findAndRegisterModules();

    @Test
    void executeAndTraceCommandsWorkWithSharedStoreDir() throws Exception {
        Path storeDir = Files.createTempDirectory("npdev-cli-store-");
        Path input = writeJson(Map.of("email", "ana@example.com", "name", "Ana"), "input", ".json");
        Path permissions = writeText("""
                {
                  "grants": [
                    {
                      "permission": "flow.execute",
                      "tenantId": "tenant-a",
                      "actorId": "actor-a"
                    },
                    {
                      "permission": "capability.invoke",
                      "tenantId": "tenant-a",
                      "actorId": "actor-a"
                    }
                  ]
                }
                """, "permissions-flow", ".json");
        Path simulation = writeText("""
                {
                  "operations": {
                    "persistence.unique": {
                      "default": {
                        "ok": true,
                        "value": true
                      }
                    },
                    "persistence.save": {
                      "default": {
                        "ok": true,
                        "value": {
                          "id": "user-1",
                          "email": "ana@example.com",
                          "name": "Ana"
                        }
                      }
                    }
                  }
                }
                """, "sim-store-trace", ".json");

        CommandResult execute = runCli(
                "execute",
                "--model", modelPath().toString(),
                "--flow", "CreateUser",
                "--tenant", "tenant-a",
                "--actor", "actor-a",
                "--json", input.toString(),
                "--permissions", permissions.toString(),
                "--sim", simulation.toString(),
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, execute.exitCode(), execute.stderr());
        Map<String, Object> executePayload = parseObject(execute.stdout());
        assertEquals("OK", executePayload.get("status"), execute.stdout());
        assertEquals("CreateUser", executePayload.get("flowName"));
        Map<String, Object> output = objectValue(executePayload.get("output"));
        assertEquals("ana@example.com", output.get("email"));
        assertEquals("Ana", output.get("name"));
        String executionId = requiredString(executePayload.get("executionId"));
        String correlationId = requiredString(executePayload.get("correlationId"));
        assertTrue(Files.exists(storeDir.resolve("cli-state.json")), "Expected persisted CLI state in shared store");

        CommandResult trace = runCli(
                "trace",
                "--model", modelPath().toString(),
                "--execution", executionId,
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, trace.exitCode(), trace.stderr());
        Map<String, Object> tracePayload = parseObject(trace.stdout());
        Map<String, Object> traceMeta = objectValue(tracePayload.get("meta"));
        assertEquals(executionId, traceMeta.get("executionId"));
        assertEquals(correlationId, traceMeta.get("correlationId"));
        assertEquals("CreateUser", traceMeta.get("flowName"));
        assertEquals("tenant-a", traceMeta.get("tenantId"));
        assertEquals("actor-a", traceMeta.get("actorId"));
        assertEquals("OK", tracePayload.get("outcome"));

        List<Map<String, Object>> steps = objectList(tracePayload.get("steps"));
        assertTrue(hasStepNamed(steps, "validate-user"));
        assertTrue(hasStepNamed(steps, "save-user"));
        assertTrue(hasStepNamed(steps, "emit-user-created"));
        assertTrue(hasStepNamed(steps, "return-user"));
    }

    @Test
    void waitingPublishAndResumeLifecycleWorksInCli() throws Exception {
        Path storeDir = Files.createTempDirectory("npdev-cli-store-");
        Path input = writeJson(Map.of("correlationId", "corr-await-1"), "input-await", ".json");
        Path eventPayload = writeJson(Map.of("status", "ok"), "event", ".json");
        Path permissions = writeText("""
                {
                  "grants": [
                    {
                      "permission": "flow.execute",
                      "tenantId": "tenant-a",
                      "actorId": "actor-a"
                    },
                    {
                      "permission": "event.publish",
                      "tenantId": "tenant-a",
                      "actorId": "actor-a"
                    }
                  ]
                }
                """, "permissions-await", ".json");

        CommandResult execute = runCli(
                "execute",
                "--model", modelPath().toString(),
                "--flow", "AwaitDemo",
                "--tenant", "tenant-a",
                "--actor", "actor-a",
                "--json", input.toString(),
                "--permissions", permissions.toString(),
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, execute.exitCode(), execute.stderr());
        Map<String, Object> executePayload = parseObject(execute.stdout());
        assertEquals("WAITING_EVENT", executePayload.get("status"));
        assertEquals("AwaitDemo", executePayload.get("flowName"));
        assertEquals("SomethingHappened", executePayload.get("awaitedEventName"));
        String executionId = requiredString(executePayload.get("executionId"));
        String correlationId = requiredString(executePayload.get("correlationId"));
        assertEquals("corr-await-1", correlationId);

        CommandResult waitingBeforePublish = runCli(
                "list-executions",
                "--model", modelPath().toString(),
                "--mode", "waiting",
                "--tenant", "tenant-a",
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, waitingBeforePublish.exitCode(), waitingBeforePublish.stderr());
        List<Map<String, Object>> waitingExecutions = parseList(waitingBeforePublish.stdout());
        assertEquals(1, waitingExecutions.size());
        assertEquals(executionId, waitingExecutions.get(0).get("executionId"));
        assertEquals("WAITING_EVENT", waitingExecutions.get(0).get("status"));

        CommandResult publish = runCli(
                "publish-event",
                "--model", modelPath().toString(),
                "--event", "SomethingHappened",
                "--correlation", correlationId,
                "--tenant", "tenant-a",
                "--actor", "actor-a",
                "--json", eventPayload.toString(),
                "--permissions", permissions.toString(),
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, publish.exitCode(), publish.stderr());
        Map<String, Object> publishPayload = parseObject(publish.stdout());
        assertEquals("SomethingHappened", publishPayload.get("eventName"));
        assertEquals(correlationId, publishPayload.get("correlationId"));
        assertEquals("tenant-a", publishPayload.get("tenantId"));
        assertEquals("actor-a", publishPayload.get("actorId"));

        CommandResult waitingAfterPublish = runCli(
                "list-executions",
                "--model", modelPath().toString(),
                "--mode", "waiting",
                "--tenant", "tenant-a",
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, waitingAfterPublish.exitCode(), waitingAfterPublish.stderr());
        assertEquals(List.of(), parseList(waitingAfterPublish.stdout()));

        CommandResult trace = runCli(
                "trace",
                "--model", modelPath().toString(),
                "--execution", executionId,
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, trace.exitCode(), trace.stderr());
        Map<String, Object> tracePayload = parseObject(trace.stdout());
        assertEquals("OK", tracePayload.get("outcome"));
        Map<String, Object> traceMeta = objectValue(tracePayload.get("meta"));
        assertEquals(executionId, traceMeta.get("executionId"));
        assertEquals(correlationId, traceMeta.get("correlationId"));

        List<Map<String, Object>> steps = objectList(tracePayload.get("steps"));
        Map<String, Object> awaitStep = stepNamed(steps, "await-something");
        assertEquals("OK", awaitStep.get("outcome"));
        Map<String, Object> awaitInfo = objectValue(awaitStep.get("info"));
        assertNotNull(awaitInfo.get("awaitedEventFoundEventId"));
        assertTrue(hasStepNamed(steps, "return-await"));
    }

    @Test
    void idempotencyHitReturnsCachedResultMetadata() throws Exception {
        Path storeDir = Files.createTempDirectory("npdev-cli-store-");
        Path model = writeText(idempotencyModelJson(), "model-idem", ".json");
        Path input = writeJson(Map.of("requestId", "idem-1", "email", "a@b.com"), "input-idem", ".json");
        Path permissions = writeText("""
                {
                  "grants": [
                    {
                      "permission": "flow.execute",
                      "tenantId": "tenant-idem",
                      "actorId": "actor-idem"
                    },
                    {
                      "permission": "capability.invoke",
                      "tenantId": "tenant-idem",
                      "actorId": "actor-idem"
                    }
                  ]
                }
                """, "permissions-idem", ".json");
        Path simulation = writeText("""
                {
                  "operations": {
                    "persistence.save": {
                      "default": {
                        "ok": true,
                        "value": {
                          "id": "u-idem-1",
                          "email": "a@b.com"
                        }
                      }
                    }
                  }
                }
                """, "sim-idem", ".json");

        CommandResult first = runCli(
                "execute",
                "--model", model.toString(),
                "--flow", "CreateUser",
                "--tenant", "tenant-idem",
                "--actor", "actor-idem",
                "--json", input.toString(),
                "--permissions", permissions.toString(),
                "--sim", simulation.toString(),
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, first.exitCode(), first.stderr());
        Map<String, Object> firstPayload = parseObject(first.stdout());
        assertEquals("OK", firstPayload.get("status"), first.stdout());
        Map<String, Object> firstOutput = objectValue(firstPayload.get("output"));
        assertEquals("u-idem-1", firstOutput.get("id"));
        assertEquals("a@b.com", firstOutput.get("email"));
        String firstExecutionId = requiredString(firstPayload.get("executionId"));

        CommandResult second = runCli(
                "execute",
                "--model", model.toString(),
                "--flow", "CreateUser",
                "--tenant", "tenant-idem",
                "--actor", "actor-idem",
                "--json", input.toString(),
                "--permissions", permissions.toString(),
                "--sim", simulation.toString(),
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, second.exitCode(), second.stderr());
        Map<String, Object> secondPayload = parseObject(second.stdout());
        assertEquals("OK", secondPayload.get("status"), second.stdout());
        Map<String, Object> secondOutput = objectValue(secondPayload.get("output"));
        assertEquals(firstOutput, secondOutput);
        String secondExecutionId = requiredString(secondPayload.get("executionId"));
        assertTrue(!firstExecutionId.equals(secondExecutionId), "Expected a fresh execution id for the cached run");

        CommandResult trace = runCli(
                "trace",
                "--model", model.toString(),
                "--execution", secondExecutionId,
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, trace.exitCode(), trace.stderr());
        Map<String, Object> tracePayload = parseObject(trace.stdout());
        Map<String, Object> saveStep = stepNamed(objectList(tracePayload.get("steps")), "save");
        Map<String, Object> saveInfo = objectValue(saveStep.get("info"));
        assertEquals("HIT", saveInfo.get("idempotencyState"));
        assertEquals("idem-1", saveInfo.get("idempotencyKey"));

        Map<String, Object> persistedState = readJsonObjectFile(storeDir.resolve("cli-state.json"));
        List<Map<String, Object>> idempotencyRecords = objectList(persistedState.get("idempotencyRecords"));
        assertEquals(1, idempotencyRecords.size());
        Map<String, Object> record = idempotencyRecords.get(0);
        assertEquals("tenant-idem", record.get("tenantId"));
        assertEquals("idem-1", record.get("idempotencyKey"));
        assertEquals("persistence", record.get("capabilityName"));
        assertEquals("save", record.get("operationName"));
        assertEquals("SUCCESS", record.get("status"));
        assertNotNull(record.get("resultJsonRedacted"));
    }

    @Test
    void circuitBreakerCanBeExercisedFromCliSimulation() throws Exception {
        Path storeDir = Files.createTempDirectory("npdev-cli-store-");
        Path input = writeJson(Map.of("email", "ana@example.com", "name", "Ana"), "input-circuit", ".json");
        Path permissions = writeText("""
                {
                  "grants": [
                    {
                      "permission": "flow.execute",
                      "tenantId": "tenant-circuit",
                      "actorId": "actor-circuit"
                    },
                    {
                      "permission": "capability.invoke",
                      "tenantId": "tenant-circuit",
                      "actorId": "actor-circuit"
                    }
                  ]
                }
                """, "permissions-circuit", ".json");
        Path sim = writeText("""
                {
                  "operations": {
                    "persistence.unique": {
                      "default": {
                        "ok": true,
                        "value": true
                      }
                    },
                    "persistence.save": {
                      "default": {
                        "ok": false,
                        "error": {
                          "kind": "TRANSIENT",
                          "code": "SIM_TRANSIENT",
                          "message": "temporary"
                        }
                      }
                    }
                  }
                }
                """, "sim-circuit", ".json");

        for (int attempt = 1; attempt <= 5; attempt++) {
            CommandResult failed = runCli(
                    "execute",
                    "--model", modelPath().toString(),
                    "--flow", "CreateUser",
                    "--tenant", "tenant-circuit",
                    "--actor", "actor-circuit",
                    "--json", input.toString(),
                    "--permissions", permissions.toString(),
                    "--sim", sim.toString(),
                    "--store-dir", storeDir.toString(),
                    "--format", "json"
            );
            assertEquals(0, failed.exitCode(), failed.stderr());
            Map<String, Object> failedPayload = parseObject(failed.stdout());
            assertEquals("CAPABILITY_FAILED", failedPayload.get("status"), failed.stdout());
            assertEquals("persistence", failedPayload.get("capabilityName"));
            assertEquals("save", failedPayload.get("capabilityOperation"));
            Map<String, Object> capabilityError = objectValue(failedPayload.get("capabilityError"));
            assertEquals("SIM_TRANSIENT", capabilityError.get("code"));
            assertEquals("temporary", capabilityError.get("message"));
        }

        CommandResult shortCircuited = runCli(
                "execute",
                "--model", modelPath().toString(),
                "--flow", "CreateUser",
                "--tenant", "tenant-circuit",
                "--actor", "actor-circuit",
                "--json", input.toString(),
                "--permissions", permissions.toString(),
                "--sim", sim.toString(),
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, shortCircuited.exitCode(), shortCircuited.stderr());
        Map<String, Object> shortPayload = parseObject(shortCircuited.stdout());
        assertEquals("CAPABILITY_FAILED", shortPayload.get("status"), shortCircuited.stdout());
        Map<String, Object> shortCircuitError = objectValue(shortPayload.get("capabilityError"));
        assertEquals("CAPABILITY_CIRCUIT_OPEN", shortCircuitError.get("code"));
        String executionId = requiredString(shortPayload.get("executionId"));

        CommandResult trace = runCli(
                "trace",
                "--model", modelPath().toString(),
                "--execution", executionId,
                "--store-dir", storeDir.toString(),
                "--format", "json"
        );
        assertEquals(0, trace.exitCode(), trace.stderr());
        Map<String, Object> tracePayload = parseObject(trace.stdout());
        Map<String, Object> saveStep = stepNamed(objectList(tracePayload.get("steps")), "save-user");
        Map<String, Object> saveInfo = objectValue(saveStep.get("info"));
        assertEquals("OPEN", saveInfo.get("circuitState"));
        assertEquals("NOT_USED", saveInfo.get("bulkheadState"));

        Map<String, Object> persistedState = readJsonObjectFile(storeDir.resolve("cli-state.json"));
        Map<String, Object> circuitStates = objectValue(persistedState.get("circuitStates"));
        Map<String, Object> saveCircuitState = objectValue(circuitStates.get("tenant-circuit|persistence|save"));
        assertEquals("OPEN", saveCircuitState.get("state"));
        assertEquals(5, ((Number) saveCircuitState.get("consecutiveFailures")).intValue());
        assertNotNull(saveCircuitState.get("openedAtMs"));
    }

    @Test
    void compileModelFailsFastWhenSchemaIsInvalid() throws Exception {
        Path model = writeText("""
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "v1",
                  "unexpectedRootKey": "boom",
                  "concepts": [
                    {
                      "name": "User",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    }
                  ]
                }
                """, "model-invalid-schema", ".json");
        Path out = Files.createTempFile("compiled-model", ".json");

        CommandResult compile = runCli(
                "compile-model",
                "--model", model.toString(),
                "--out", out.toString()
        );

        assertEquals(1, compile.exitCode());
        assertTrue(compile.stderr().contains("Model schema validation failed"));
        assertTrue(compile.stderr().contains("unexpectedRootKey"));
    }

    @Test
    void validateBundleAndRunProcedureAreScriptable() throws Exception {
        Path model = writeText(procedureModelJson(), "model-procedure", ".json");
        Path input = writeJson(Map.of("hello", "world"), "procedure-input", ".json");

        CommandResult validate = runCli(
                "validate-bundle",
                "--model", model.toString(),
                "--format", "json"
        );

        assertEquals(0, validate.exitCode());
        assertEquals(Boolean.TRUE, parseObject(validate.stdout()).get("valid"));

        CommandResult list = runCli(
                "list-procedures",
                "--model", model.toString(),
                "--format", "json"
        );

        assertEquals(0, list.exitCode());
        assertTrue(list.stdout().contains("EchoInput"));
        assertTrue(list.stdout().contains("assign"));

        CommandResult run = runCli(
                "run-procedure",
                "--model", model.toString(),
                "--procedure", "EchoInput",
                "--json", input.toString(),
                "--format", "json"
        );

        assertEquals(0, run.exitCode());
        Map<String, Object> payload = parseObject(run.stdout());
        assertEquals(Boolean.TRUE, payload.get("ok"));
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) payload.get("state");
        assertEquals(Map.of("hello", "world"), state.get("return"));
    }

    private static Path modelPath() {
        Path direct = Path.of("test-models", "user-minimal", "model.json");
        if (Files.exists(direct)) {
            return direct.toAbsolutePath().normalize();
        }
        Path nested = Path.of("..", "..", "test-models", "user-minimal", "model.json").normalize();
        if (Files.exists(nested)) {
            return nested.toAbsolutePath().normalize();
        }
        throw new IllegalStateException("Could not locate test model");
    }

    private static CommandResult runCli(String... args) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        NPDevCliMain cli = new NPDevCliMain(
                new PrintStream(stdout, true),
                new PrintStream(stderr, true),
                OBJECT_MAPPER
        );
        int exitCode = cli.run(args);
        return new CommandResult(exitCode, stdout.toString(), stderr.toString());
    }

    private static Map<String, Object> parseObject(String json) throws IOException {
        return OBJECT_MAPPER.readValue(json, new TypeReference<>() {
        });
    }

    private static List<Map<String, Object>> parseList(String json) throws IOException {
        return OBJECT_MAPPER.readValue(json, new TypeReference<>() {
        });
    }

    private static Map<String, Object> readJsonObjectFile(Path path) throws IOException {
        return parseObject(Files.readString(path));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectValue(Object value) {
        assertTrue(value instanceof Map<?, ?>, "Expected object payload but got: " + value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Object value) {
        assertTrue(value instanceof List<?>, "Expected list payload but got: " + value);
        return (List<Map<String, Object>>) value;
    }

    private static String requiredString(Object value) {
        assertNotNull(value, "Expected non-null string value");
        String text = String.valueOf(value);
        assertTrue(!text.isBlank(), "Expected non-blank string value");
        return text;
    }

    private static boolean hasStepNamed(List<Map<String, Object>> steps, String stepName) {
        return steps.stream().anyMatch(step -> stepName.equals(step.get("stepName")));
    }

    private static Map<String, Object> stepNamed(List<Map<String, Object>> steps, String stepName) {
        return steps.stream()
                .filter(step -> stepName.equals(step.get("stepName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected step named " + stepName));
    }

    private static Path writeJson(Map<String, Object> payload, String prefix, String suffix) throws IOException {
        Path file = Files.createTempFile(prefix, suffix);
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), payload);
        return file;
    }

    private static Path writeText(String payload, String prefix, String suffix) throws IOException {
        Path file = Files.createTempFile(prefix, suffix);
        Files.writeString(file, payload);
        return file;
    }

    private static String idempotencyModelJson() {
        return """
                {
                  "namespace":"demo",
                  "dslVersion":"1.0.0",
                  "version":"v1",
                  "concepts":[
                    {
                      "name":"User",
                      "fields":[
                        {"name":"id","type":"uuid","id":true},
                        {"name":"email","type":"string","required":true}
                      ]
                    }
                  ],
                  "capabilities":[
                    {
                      "name":"persistence",
                      "type":"PersistenceCapability",
                      "operations":["save"]
                    }
                  ],
                  "bindings":[
                    {"capability":"persistence","adapter":"inmemory"}
                  ],
                  "flows":[
                    {
                      "name":"CreateUser",
                      "concept":"User",
                      "steps":[
                        {
                          "name":"save",
                          "type":"capabilityCall",
                          "cap":"persistence",
                          "op":"save",
                          "args":["$input"],
                          "out":"$saved",
                          "policy":{
                            "retryCount":1,
                            "retryDelayMs":0,
                            "timeoutMs":0,
                            "idempotencyKeyField":"$input.requestId"
                          }
                        },
                        {"type":"return","value":"$saved"}
                      ]
                    }
                  ]
                }
                """;
    }

    private static String procedureModelJson() {
        return """
                {
                  "namespace":"demo",
                  "dslVersion":"1.0.0",
                  "version":"v1",
                  "concepts":[
                    {
                      "name":"WorkItem",
                      "fields":[
                        {"name":"id","type":"uuid","id":true},
                        {"name":"title","type":"string"}
                      ]
                    }
                  ],
                  "procedures":[
                    {
                      "name":"EchoInput",
                      "steps":[
                        {"name":"copy-input","type":"assign","target":"copied","value":"$input"},
                        {"name":"return-copy","type":"return","value":"$copied"}
                      ]
                    }
                  ]
                }
                """;
    }

    private record CommandResult(int exitCode, String stdout, String stderr) {
    }
}

