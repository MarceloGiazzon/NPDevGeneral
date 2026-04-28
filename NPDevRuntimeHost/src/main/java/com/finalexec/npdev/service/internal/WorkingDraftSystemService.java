package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.experimental.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.WorkingDraftLifecycleActionRequest;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkingDraftSystemService {

    private static final Path DRAFT_ROOT = Paths.get("runtime-data", "working-draft-systems");
    private static final Path LIFECYCLE_ROOT = Paths.get("runtime-data", "working-draft-lifecycle-records");
    private static final Path WORKSPACE_FILE = Paths.get("runtime-data", "working-draft-workspace", "draft-lifecycle-workspace.json");

    private static final List<String> SUPPORTED_LIFECYCLE_ACTIONS = List.of(
            "branchDraft",
            "promoteDraftCandidate",
            "markReadyForPublication",
            "supersedeDraft",
            "publishDraft"
    );

    private static final List<String> DIRECT_LIFECYCLE_ACTIONS = List.of(
            "branchDraft",
            "promoteDraftCandidate",
            "markReadyForPublication"
    );

    private final ObjectMapper objectMapper;

    public WorkingDraftSystemService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> summary() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("draftStoragePath", DRAFT_ROOT.toString().replace("\\", "/"));
        response.put("lifecycleStoragePath", LIFECYCLE_ROOT.toString().replace("\\", "/"));
        response.put("workspacePath", WORKSPACE_FILE.toString().replace("\\", "/"));
        response.put("mode", "tenant-tagged-draft-lifecycle-authority-v1");
        response.put("supportedLifecycleActions", SUPPORTED_LIFECYCLE_ACTIONS);
        response.put("directLifecycleActions", DIRECT_LIFECYCLE_ACTIONS);
        response.put("tenantPropagation", "tenantId propagated into draft and lifecycle records");
        response.put("lifecycleStates", List.of(
                "DRAFT",
                "CANDIDATE_FOR_PUBLICATION",
                "READY_FOR_PUBLICATION",
                "SUPERSEDED",
                "PUBLICATION_REVIEW_REQUIRED"
        ));
        return response;
    }

    public Map<String, Object> lifecycleSummary() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("mode", "tenant-tagged-draft-lifecycle-authority-v1");
        response.put("supportedLifecycleActions", SUPPORTED_LIFECYCLE_ACTIONS);
        response.put("directLifecycleActions", DIRECT_LIFECYCLE_ACTIONS);
        response.put("lifecycleStoragePath", LIFECYCLE_ROOT.toString().replace("\\", "/"));
        response.put("workspacePath", WORKSPACE_FILE.toString().replace("\\", "/"));
        response.put("tenantPropagation", "tenantId propagated into lifecycle records");
        return response;
    }

    public Map<String, Object> history() {
        return readJsonHistory(DRAFT_ROOT, "updatedAt", "createdAt");
    }

    public Map<String, Object> lifecycleHistory() {
        return readJsonHistory(LIFECYCLE_ROOT, "recordedAt", "recordedAt");
    }

    public Map<String, Object> publish(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            throw new IllegalArgumentException("Request body is required.");
        }

        String draftId = firstNonBlank(
                asString(body.get("workingDraftId")),
                asString(body.get("draftSystemId")),
                asString(body.get("draftId")),
                UUID.randomUUID().toString()
        );

        String draftName = firstNonBlank(
                asString(body.get("draftSystemName")),
                asString(body.get("workingDraftName")),
                asString(body.get("systemName")),
                asString(body.get("title")),
                draftId
        );

        String sourceType = firstNonBlank(
                asString(body.get("sourceType")),
                "imported-scenario"
        );

        String sourceReference = firstNonBlank(
                asString(body.get("sourceReference")),
                asString(body.get("onboardingRequestId")),
                asString(body.get("executionId")),
                "unspecified-source"
        );

        String requestedBy = firstNonBlank(
                asString(body.get("requestedBy")),
                "working-draft-publication"
        );

        String tenantId = firstNonBlank(
                asString(body.get("tenantId")),
                "global"
        );

        Map<String, Object> draftRecord = new LinkedHashMap<>();
        draftRecord.put("workingDraftId", draftId);
        draftRecord.put("draftSystemName", draftName);
        draftRecord.put("tenantId", tenantId);
        draftRecord.put("sourceType", sourceType);
        draftRecord.put("sourceReference", sourceReference);
        draftRecord.put("lifecycleState", "DRAFT");
        draftRecord.put("publicationStatus", "UNPUBLISHED");
        draftRecord.put("createdAt", utcNow());
        draftRecord.put("updatedAt", utcNow());
        draftRecord.put("requestedBy", requestedBy);

        persistDraft(draftId, draftRecord);

        Map<String, Object> lifecycleRecord = buildLifecycleRecord(
                draftId,
                tenantId,
                "createDraft",
                null,
                null,
                requestedBy,
                "Imported or supplied scenario published as working draft.",
                "ESTABLISHED",
                "DRAFT"
        );
        persistLifecycleRecord(lifecycleRecord);
        updateWorkspace(lifecycleRecord);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workingDraftId", draftId);
        response.put("draftSystemName", draftName);
        response.put("tenantId", tenantId);
        response.put("status", "WORKING_DRAFT_CREATED");
        response.put("lifecycleState", "DRAFT");
        response.put("publicationStatus", "UNPUBLISHED");
        response.put("sourceType", sourceType);
        response.put("sourceReference", sourceReference);
        response.put("lifecycleRecord", lifecycleRecord);
        response.put("message", "Working draft created with lifecycle authority.");
        return response;
    }

    public Map<String, Object> transitionLifecycle(WorkingDraftLifecycleActionRequest request) {
        validateLifecycleRequest(request);

        Map<String, Object> currentDraft = loadRequiredDraft(request.getWorkingDraftId());
        String action = request.getLifecycleAction();
        String outcome = DIRECT_LIFECYCLE_ACTIONS.contains(action) ? "ESTABLISHED" : "REVIEW_REQUIRED";
        String tenantId = firstNonBlank(asString(currentDraft.get("tenantId")), "global");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("workingDraftId", request.getWorkingDraftId());
        response.put("tenantId", tenantId);
        response.put("lifecycleAction", action);

        if ("branchDraft".equals(action)) {
            if (request.getRelatedDraftId() == null || request.getRelatedDraftId().isBlank()) {
                throw new IllegalArgumentException("relatedDraftId is required for branchDraft.");
            }
            if (request.getRelatedDraftName() == null || request.getRelatedDraftName().isBlank()) {
                throw new IllegalArgumentException("relatedDraftName is required for branchDraft.");
            }

            Map<String, Object> childDraft = new LinkedHashMap<>();
            childDraft.put("workingDraftId", request.getRelatedDraftId());
            childDraft.put("draftSystemName", request.getRelatedDraftName());
            childDraft.put("tenantId", tenantId);
            childDraft.put("sourceType", "branched-draft");
            childDraft.put("sourceReference", request.getWorkingDraftId());
            childDraft.put("parentDraftId", request.getWorkingDraftId());
            childDraft.put("lifecycleState", "DRAFT");
            childDraft.put("publicationStatus", "UNPUBLISHED");
            childDraft.put("createdAt", utcNow());
            childDraft.put("updatedAt", utcNow());
            childDraft.put("requestedBy", request.getRequestedBy());

            persistDraft(request.getRelatedDraftId(), childDraft);

            Map<String, Object> lifecycleRecord = buildLifecycleRecord(
                    request.getWorkingDraftId(),
                    tenantId,
                    action,
                    request.getRelatedDraftId(),
                    request.getRelatedDraftName(),
                    request.getRequestedBy(),
                    request.getRationale(),
                    outcome,
                    "DRAFT"
            );
            persistLifecycleRecord(lifecycleRecord);
            updateWorkspace(lifecycleRecord);

            response.put("status", outcome);
            response.put("childDraft", childDraft);
            response.put("lifecycleRecord", lifecycleRecord);
            response.put("message", "Draft branch established.");
            return response;
        }

        if ("promoteDraftCandidate".equals(action)) {
            currentDraft.put("lifecycleState", "CANDIDATE_FOR_PUBLICATION");
            currentDraft.put("updatedAt", utcNow());
            persistDraft(request.getWorkingDraftId(), currentDraft);

            Map<String, Object> lifecycleRecord = buildLifecycleRecord(
                    request.getWorkingDraftId(),
                    tenantId,
                    action,
                    null,
                    null,
                    request.getRequestedBy(),
                    request.getRationale(),
                    outcome,
                    "CANDIDATE_FOR_PUBLICATION"
            );
            persistLifecycleRecord(lifecycleRecord);
            updateWorkspace(lifecycleRecord);

            response.put("status", outcome);
            response.put("updatedDraft", currentDraft);
            response.put("lifecycleRecord", lifecycleRecord);
            response.put("message", "Draft promoted to publication candidate.");
            return response;
        }

        if ("markReadyForPublication".equals(action)) {
            currentDraft.put("lifecycleState", "READY_FOR_PUBLICATION");
            currentDraft.put("updatedAt", utcNow());
            persistDraft(request.getWorkingDraftId(), currentDraft);

            Map<String, Object> lifecycleRecord = buildLifecycleRecord(
                    request.getWorkingDraftId(),
                    tenantId,
                    action,
                    null,
                    null,
                    request.getRequestedBy(),
                    request.getRationale(),
                    outcome,
                    "READY_FOR_PUBLICATION"
            );
            persistLifecycleRecord(lifecycleRecord);
            updateWorkspace(lifecycleRecord);

            response.put("status", outcome);
            response.put("updatedDraft", currentDraft);
            response.put("lifecycleRecord", lifecycleRecord);
            response.put("message", "Draft marked ready for publication.");
            return response;
        }

        if ("supersedeDraft".equals(action)) {
            Map<String, Object> lifecycleRecord = buildLifecycleRecord(
                    request.getWorkingDraftId(),
                    tenantId,
                    action,
                    request.getRelatedDraftId(),
                    request.getRelatedDraftName(),
                    request.getRequestedBy(),
                    request.getRationale(),
                    outcome,
                    "SUPERSEDED"
            );
            persistLifecycleRecord(lifecycleRecord);
            updateWorkspace(lifecycleRecord);

            response.put("status", outcome);
            response.put("lifecycleRecord", lifecycleRecord);
            response.put("message", "Draft supersession requires review before authoritative state change.");
            return response;
        }

        if ("publishDraft".equals(action)) {
            Map<String, Object> lifecycleRecord = buildLifecycleRecord(
                    request.getWorkingDraftId(),
                    tenantId,
                    action,
                    null,
                    null,
                    request.getRequestedBy(),
                    request.getRationale(),
                    outcome,
                    "PUBLICATION_REVIEW_REQUIRED"
            );
            persistLifecycleRecord(lifecycleRecord);
            updateWorkspace(lifecycleRecord);

            response.put("status", outcome);
            response.put("lifecycleRecord", lifecycleRecord);
            response.put("message", "Draft publication requires review before canonical publication.");
            return response;
        }

        throw new IllegalArgumentException("Unsupported lifecycleAction: " + action);
    }

    private void validateLifecycleRequest(WorkingDraftLifecycleActionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getWorkingDraftId())) {
            throw new IllegalArgumentException("workingDraftId is required.");
        }
        if (isBlank(request.getLifecycleAction())) {
            throw new IllegalArgumentException("lifecycleAction is required.");
        }
        if (!SUPPORTED_LIFECYCLE_ACTIONS.contains(request.getLifecycleAction())) {
            throw new IllegalArgumentException("Unsupported lifecycleAction: " + request.getLifecycleAction());
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }
    }

    private Map<String, Object> buildLifecycleRecord(
            String workingDraftId,
            String tenantId,
            String lifecycleAction,
            String relatedDraftId,
            String relatedDraftName,
            String requestedBy,
            String rationale,
            String outcome,
            String resultingState
    ) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("lifecycleRecordId", UUID.randomUUID().toString());
        record.put("workingDraftId", workingDraftId);
        record.put("tenantId", tenantId);
        record.put("lifecycleAction", lifecycleAction);
        record.put("relatedDraftId", relatedDraftId);
        record.put("relatedDraftName", relatedDraftName);
        record.put("requestedBy", requestedBy);
        record.put("rationale", rationale);
        record.put("outcome", outcome);
        record.put("resultingState", resultingState);
        record.put("recordedAt", utcNow());
        return record;
    }

    private Map<String, Object> loadRequiredDraft(String workingDraftId) {
        try {
            Path file = DRAFT_ROOT.resolve(workingDraftId + ".json");
            if (!Files.exists(file)) {
                throw new IllegalArgumentException("Working draft not found: " + workingDraftId);
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> draft = objectMapper.readValue(file.toFile(), LinkedHashMap.class);
            return draft;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to load working draft: " + workingDraftId, ex);
        }
    }

    private void persistDraft(String workingDraftId, Map<String, Object> record) {
        try {
            Files.createDirectories(DRAFT_ROOT);
            Path output = DRAFT_ROOT.resolve(workingDraftId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist working draft record.", e);
        }
    }

    private void persistLifecycleRecord(Map<String, Object> record) {
        try {
            Files.createDirectories(LIFECYCLE_ROOT);
            String recordId = String.valueOf(record.get("lifecycleRecordId"));
            Path output = LIFECYCLE_ROOT.resolve(recordId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), record);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist working draft lifecycle record.", e);
        }
    }

    private void updateWorkspace(Map<String, Object> lifecycleRecord) {
        try {
            Files.createDirectories(WORKSPACE_FILE.getParent());

            Map<String, Object> workspace;
            if (Files.exists(WORKSPACE_FILE)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> existing = objectMapper.readValue(WORKSPACE_FILE.toFile(), LinkedHashMap.class);
                workspace = existing;
            } else {
                workspace = new LinkedHashMap<>();
                workspace.put("workspaceType", "working-draft-lifecycle-workspace");
                workspace.put("createdAt", utcNow());
                workspace.put("lifecycleRecords", new ArrayList<Map<String, Object>>());
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> lifecycleRecords =
                    (List<Map<String, Object>>) workspace.computeIfAbsent("lifecycleRecords", key -> new ArrayList<Map<String, Object>>());

            lifecycleRecords.add(lifecycleRecord);
            workspace.put("lastUpdatedAt", utcNow());
            workspace.put("lifecycleRecordCount", lifecycleRecords.size());

            objectMapper.writerWithDefaultPrettyPrinter().writeValue(WORKSPACE_FILE.toFile(), workspace);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update working draft lifecycle workspace.", e);
        }
    }

    private Map<String, Object> readJsonHistory(Path root, String preferredKey, String fallbackKey) {
        List<Map<String, Object>> items = new ArrayList<>();
        try {
            if (Files.exists(root)) {
                Files.list(root)
                        .filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(path -> {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> item = objectMapper.readValue(path.toFile(), LinkedHashMap.class);
                                items.add(item);
                            } catch (Exception ignored) {
                            }
                        });
            }
        } catch (Exception ignored) {
        }

        items.sort(Comparator.comparing(
                item -> String.valueOf(item.getOrDefault(preferredKey, item.getOrDefault(fallbackKey, ""))),
                Comparator.reverseOrder()
        ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String asString(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String utcNow() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}