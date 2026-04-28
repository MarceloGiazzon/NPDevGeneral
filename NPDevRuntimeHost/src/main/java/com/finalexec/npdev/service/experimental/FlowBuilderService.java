package com.finalexec.npdev.service.experimental;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.internal.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.FlowBuilderDraftRequest;
import com.finalexec.npdev.dto.FlowBuilderStepRequest;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class FlowBuilderService {

    private static final Path FLOW_BUILDER_ROOT = Path.of("runtime-data", "flow-builder");
    private static final Path FLOW_DRAFTS_ROOT = FLOW_BUILDER_ROOT.resolve("drafts");
    private static final Path FLOW_HISTORY_ROOT = FLOW_BUILDER_ROOT.resolve("history");

    private final ObjectMapper objectMapper;

    public FlowBuilderService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> listDrafts() {
        List<Map<String, Object>> drafts = new ArrayList<>();
        try {
            Files.createDirectories(FLOW_DRAFTS_ROOT);
            try (var stream = Files.list(FLOW_DRAFTS_ROOT)) {
                for (Path path : stream.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    Map<String, Object> draft = readJson(path);
                    if (draft != null) {
                        drafts.add(draft);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list flow builder drafts.", e);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", drafts.size());
        response.put("storagePath", FLOW_DRAFTS_ROOT.toString().replace("\\", "/"));
        response.put("items", drafts);
        return response;
    }

    public Map<String, Object> draftHistory() {
        List<Map<String, Object>> history = new ArrayList<>();
        try {
            Files.createDirectories(FLOW_HISTORY_ROOT);
            try (var stream = Files.list(FLOW_HISTORY_ROOT)) {
                for (Path path : stream.filter(file -> file.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    Map<String, Object> item = readJson(path);
                    if (item != null) {
                        history.add(item);
                    }
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read flow builder history.", e);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", history.size());
        response.put("storagePath", FLOW_HISTORY_ROOT.toString().replace("\\", "/"));
        response.put("items", history);
        return response;
    }

    public Map<String, Object> saveDraft(FlowBuilderDraftRequest request) {
        validateDraftRequest(request);

        String flowName = request.getFlowName().trim();
        String now = now();
        Map<String, Object> existing = readJson(flowDraftPath(flowName));
        Map<String, Object> definition = existingDefinition(existing);
        List<Map<String, Object>> steps = request.getSteps() == null
                ? existingSteps(existing)
                : normalizeSteps(request.getSteps());

        definition.put("flowName", flowName);
        definition.put("displayName", defaultIfBlank(request.getDisplayName(), flowName));
        definition.put("description", defaultIfBlank(request.getDescription(), ""));
        definition.put("steps", steps);

        Map<String, Object> draft = new LinkedHashMap<>();
        draft.put("builderDraftId", existing == null ? UUID.randomUUID().toString() : stringValue(existing.get("builderDraftId")));
        draft.put("flowName", flowName);
        draft.put("displayName", definition.get("displayName"));
        draft.put("description", definition.get("description"));
        draft.put("tenantId", defaultIfBlank(request.getTenantId(), stringValue(existing == null ? null : existing.get("tenantId"))));
        draft.put("actorId", defaultIfBlank(request.getActorId(), stringValue(existing == null ? null : existing.get("actorId"))));
        draft.put("saveMode", defaultIfBlank(request.getSaveMode(), "DRAFT_SAVE"));
        draft.put("builderStatus", "DRAFT_SAVED");
        draft.put("stepCount", steps.size());
        draft.put("capabilityConnectionCount", countCapabilityConnections(steps));
        draft.put("createdAt", existing == null ? now : stringValue(existing.get("createdAt")));
        draft.put("updatedAt", now);
        draft.put("definition", definition);
        draft.put("surface", "Flow Builder UI");

        persistDraft(flowName, draft);
        persistHistory(flowName, "FLOW_DRAFT_SAVED", draft);
        return draft;
    }

    public Map<String, Object> addStep(String flowName, FlowBuilderStepRequest request) {
        if (isBlank(flowName)) {
            throw new IllegalArgumentException("flow name is required.");
        }
        validateStepRequest(request);

        String normalizedFlowName = flowName.trim();
        Map<String, Object> draft = readJson(flowDraftPath(normalizedFlowName));
        if (draft == null) {
            throw new IllegalArgumentException("No draft flow found for " + normalizedFlowName + ".");
        }

        Map<String, Object> definition = existingDefinition(draft);
        List<Map<String, Object>> steps = existingSteps(draft);
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepId", defaultIfBlank(request.getStepId(), slugify(request.getStepName()) + "-" + (steps.size() + 1)));
        step.put("stepName", request.getStepName().trim());
        step.put("stepType", defaultIfBlank(request.getStepType(), "capability"));
        step.put("capabilityKey", request.getCapabilityKey().trim());
        step.put("operationKey", defaultIfBlank(request.getOperationKey(), ""));
        step.put("notes", defaultIfBlank(request.getNotes(), ""));
        step.put("nextSteps", normalizeStringList(request.getNextSteps()));
        step.put("defaults", request.getDefaults() == null ? Map.of() : new LinkedHashMap<>(request.getDefaults()));
        step.put("sequence", steps.size() + 1);

        steps.add(step);
        definition.put("steps", steps);
        draft.put("stepCount", steps.size());
        draft.put("capabilityConnectionCount", countCapabilityConnections(steps));
        draft.put("updatedAt", now());
        draft.put("builderStatus", "STEP_ADDED");
        draft.put("definition", definition);

        persistDraft(normalizedFlowName, draft);
        persistHistory(normalizedFlowName, "FLOW_STEP_ADDED", draft);
        return draft;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> existingDefinition(Map<String, Object> draft) {
        if (draft != null && draft.get("definition") instanceof Map<?, ?> rawMap) {
            Map<String, Object> definition = new LinkedHashMap<>();
            rawMap.forEach((key, value) -> definition.put(String.valueOf(key), value));
            return definition;
        }
        return new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> existingSteps(Map<String, Object> draft) {
        Map<String, Object> definition = existingDefinition(draft);
        Object raw = definition.get("steps");
        List<Map<String, Object>> steps = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Map<?, ?> rawMap) {
                    Map<String, Object> step = new LinkedHashMap<>();
                    rawMap.forEach((key, value) -> step.put(String.valueOf(key), value));
                    steps.add(step);
                }
            }
        }
        return steps;
    }

    private List<Map<String, Object>> normalizeSteps(List<Map<String, Object>> rawSteps) {
        List<Map<String, Object>> steps = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> rawStep : rawSteps) {
            Map<String, Object> step = new LinkedHashMap<>();
            String stepName = stringValue(rawStep.get("stepName"));
            step.put("stepId", defaultIfBlank(stringValue(rawStep.get("stepId")), slugify(stepName) + "-" + index));
            step.put("stepName", defaultIfBlank(stepName, "step-" + index));
            step.put("stepType", defaultIfBlank(stringValue(rawStep.get("stepType")), "capability"));
            step.put("capabilityKey", defaultIfBlank(stringValue(rawStep.get("capabilityKey")), ""));
            step.put("operationKey", defaultIfBlank(stringValue(rawStep.get("operationKey")), ""));
            step.put("notes", defaultIfBlank(stringValue(rawStep.get("notes")), ""));
            step.put("sequence", index);
            step.put("nextSteps", normalizeStringList(rawStep.get("nextSteps")));
            step.put("defaults", normalizeObjectMap(rawStep.get("defaults")));
            steps.add(step);
            index++;
        }
        return steps;
    }

    private List<String> normalizeStringList(Object value) {
        List<String> items = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String normalized = stringValue(item);
                if (!normalized.isBlank()) {
                    items.add(normalized);
                }
            }
        }
        return items;
    }

    private Map<String, Object> normalizeObjectMap(Object value) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> rawMap) {
            rawMap.forEach((key, item) -> normalized.put(String.valueOf(key), item));
        }
        return normalized;
    }

    private int countCapabilityConnections(List<Map<String, Object>> steps) {
        int count = 0;
        for (Map<String, Object> step : steps) {
            if (!stringValue(step.get("capabilityKey")).isBlank()) {
                count++;
            }
        }
        return count;
    }

    private void validateDraftRequest(FlowBuilderDraftRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getFlowName())) {
            throw new IllegalArgumentException("flowName is required.");
        }
    }

    private void validateStepRequest(FlowBuilderStepRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getStepName())) {
            throw new IllegalArgumentException("stepName is required.");
        }
        if (isBlank(request.getCapabilityKey())) {
            throw new IllegalArgumentException("capabilityKey is required.");
        }
    }

    private void persistDraft(String flowName, Map<String, Object> draft) {
        try {
            Files.createDirectories(FLOW_DRAFTS_ROOT);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(flowDraftPath(flowName).toFile(), draft);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist flow builder draft.", e);
        }
    }

    private void persistHistory(String flowName, String eventType, Map<String, Object> draft) {
        try {
            Files.createDirectories(FLOW_HISTORY_ROOT);
            Map<String, Object> history = new LinkedHashMap<>();
            history.put("historyId", UUID.randomUUID().toString());
            history.put("eventType", eventType);
            history.put("flowName", flowName);
            history.put("recordedAt", now());
            history.put("draft", draft);
            Path output = FLOW_HISTORY_ROOT.resolve(slugify(flowName) + "-" + history.get("historyId") + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), history);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist flow builder history.", e);
        }
    }

    private Map<String, Object> readJson(Path path) {
        try {
            if (!Files.exists(path)) {
                return null;
            }
            return objectMapper.readValue(path.toFile(), LinkedHashMap.class);
        } catch (Exception e) {
            return null;
        }
    }

    private Path flowDraftPath(String flowName) {
        return FLOW_DRAFTS_ROOT.resolve(slugify(flowName) + ".json");
    }

    private String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private String slugify(String value) {
        String normalized = stringValue(value).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
        String compact = normalized.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return compact.isBlank() ? "flow-draft" : compact;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
