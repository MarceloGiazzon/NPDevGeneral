package com.finalexec.npdev.service.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-138: applyMutationToModelSourceAt is the first real content-mutation logic in this
 * platform's whole "canonical source mutation" family -- every sibling (CanonicalSourceArtifactStore,
 * RealPublicationExecutorService, etc.) only journals bookkeeping records and never writes real
 * model content. These tests prove the JSON surgery itself: a valid target inserts a real,
 * schema-shaped step and leaves everything else in the file untouched; every invalid target
 * declines with a reason instead of corrupting the file.
 */
final class SemanticBehaviorWriteBackServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SemanticBehaviorWriteBackService service = new SemanticBehaviorWriteBackService(
            objectMapper,
            new SemanticBehaviorWriteBackCanonicalizationService(objectMapper)
    );

    @Test
    void insertsARealCapabilityCallStepAndPreservesEverythingElseInTheFile(@TempDir Path tempDir) throws Exception {
        Path modelPath = writeModel(tempDir, """
                {
                  "namespace": "trial.widgets",
                  "version": "1.0",
                  "capabilities": [
                    { "name": "notification", "type": "NotificationCapability", "operations": [ { "name": "send" } ] }
                  ],
                  "flows": [
                    { "name": "OtherFlow", "steps": [ { "name": "untouchedStep", "type": "return" } ] },
                    { "name": "SubmitOrder", "steps": [ { "name": "validate", "type": "invariantCheck" } ] }
                  ]
                }
                """);

        var result = service.applyMutationToModelSourceAt(mutation("SubmitOrder", "notifyCustomer"), modelPath);

        assertTrue(result.applied(), () -> "expected applied, got reviewRequired: " + result.reason());
        assertEquals(modelPath.toString().replace('\\', '/'), result.modelSourcePath());

        JsonNode rewritten = objectMapper.readTree(modelPath.toFile());
        assertEquals("trial.widgets", rewritten.path("namespace").asText());
        assertEquals(1, rewritten.path("flows").get(0).path("steps").size(), "OtherFlow's own step must be untouched");
        assertEquals("untouchedStep", rewritten.path("flows").get(0).path("steps").get(0).path("name").asText());

        JsonNode submitOrderSteps = rewritten.path("flows").get(1).path("steps");
        assertEquals(2, submitOrderSteps.size());
        JsonNode newStep = submitOrderSteps.get(1);
        assertEquals("notifyCustomer", newStep.path("name").asText());
        assertEquals("capabilityCall", newStep.path("type").asText());
        assertEquals("notification", newStep.path("capability").asText());
        assertEquals("send", newStep.path("operation").asText());
    }

    @Test
    void preservesCrlfLineEndingsWhenTheOriginalFileUsedThem(@TempDir Path tempDir) throws Exception {
        String crlfJson = ("{\r\n"
                + "  \"namespace\": \"trial.widgets\",\r\n"
                + "  \"version\": \"1.0\",\r\n"
                + "  \"capabilities\": [ { \"name\": \"notification\", \"type\": \"NotificationCapability\" } ],\r\n"
                + "  \"flows\": [ { \"name\": \"SubmitOrder\", \"steps\": [] } ]\r\n"
                + "}");
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, crlfJson);

        var result = service.applyMutationToModelSourceAt(mutation("SubmitOrder", "notifyCustomer"), modelPath);

        assertTrue(result.applied(), () -> "expected applied, got reviewRequired: " + result.reason());
        String rewritten = Files.readString(modelPath);
        assertTrue(rewritten.contains("\r\n"), "expected CRLF line endings to be preserved");
        assertFalse(rewritten.replace("\r\n", "").contains("\n"), "no line should be LF-only when the source was CRLF");
    }

    @Test
    void preservesATrailingNewlineWhenTheOriginalFileHadOne(@TempDir Path tempDir) throws Exception {
        Path modelPath = writeModel(tempDir, """
                { "namespace": "trial.widgets", "version": "1.0",
                  "capabilities": [ { "name": "notification", "type": "NotificationCapability" } ],
                  "flows": [ { "name": "SubmitOrder", "steps": [] } ] }
                """);
        assertTrue(Files.readString(modelPath).endsWith("\n"), "fixture setup: the text block itself ends with a newline");

        var result = service.applyMutationToModelSourceAt(mutation("SubmitOrder", "notifyCustomer"), modelPath);

        assertTrue(result.applied(), () -> "expected applied, got reviewRequired: " + result.reason());
        assertTrue(Files.readString(modelPath).endsWith("\n"), "a source file that ended with a newline should still end with one");
    }

    @Test
    void declinesWhenTheModelSourceFileDoesNotExist(@TempDir Path tempDir) {
        var result = service.applyMutationToModelSourceAt(
                mutation("SubmitOrder", "notifyCustomer"),
                tempDir.resolve("does-not-exist.json")
        );

        assertFalse(result.applied());
        assertTrue(result.reason().contains("not found"), result.reason());
    }

    @Test
    void declinesWhenTheModelHasNoNotificationCapability(@TempDir Path tempDir) throws Exception {
        Path modelPath = writeModel(tempDir, """
                { "namespace": "trial.widgets", "version": "1.0", "capabilities": [], "flows": [
                  { "name": "SubmitOrder", "steps": [] }
                ] }
                """);

        var result = service.applyMutationToModelSourceAt(mutation("SubmitOrder", "notifyCustomer"), modelPath);

        assertFalse(result.applied());
        assertTrue(result.reason().contains("notification"), result.reason());
        assertEquals(originalContent(modelPath), Files.readString(modelPath), "a declined mutation must not touch the file");
    }

    @Test
    void declinesWhenTheTargetFlowDoesNotExist(@TempDir Path tempDir) throws Exception {
        Path modelPath = writeModel(tempDir, """
                { "namespace": "trial.widgets", "version": "1.0",
                  "capabilities": [ { "name": "notification", "type": "NotificationCapability" } ],
                  "flows": [ { "name": "OtherFlow", "steps": [] } ] }
                """);

        var result = service.applyMutationToModelSourceAt(mutation("NoSuchFlow", "notifyCustomer"), modelPath);

        assertFalse(result.applied());
        assertTrue(result.reason().contains("NoSuchFlow"), result.reason());
    }

    @Test
    void declinesOnADuplicateStepName(@TempDir Path tempDir) throws Exception {
        Path modelPath = writeModel(tempDir, """
                { "namespace": "trial.widgets", "version": "1.0",
                  "capabilities": [ { "name": "notification", "type": "NotificationCapability" } ],
                  "flows": [ { "name": "SubmitOrder", "steps": [ { "name": "notifyCustomer", "type": "return" } ] } ] }
                """);

        var result = service.applyMutationToModelSourceAt(mutation("SubmitOrder", "notifyCustomer"), modelPath);

        assertFalse(result.applied());
        assertTrue(result.reason().contains("already has a step"), result.reason());
    }

    private Map<String, Object> mutation(String flowName, String stepName) {
        Map<String, Object> mutation = new LinkedHashMap<>();
        mutation.put("mutationType", "addNotificationStep");
        mutation.put("flowName", flowName);
        mutation.put("stepName", stepName);
        return mutation;
    }

    private Path writeModel(Path tempDir, String json) throws Exception {
        Path path = tempDir.resolve("model.json");
        Files.writeString(path, json);
        return path;
    }

    private String originalContent(Path path) throws Exception {
        return Files.readString(path);
    }
}
