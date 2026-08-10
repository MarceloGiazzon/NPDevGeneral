package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.dto.SemanticBehaviorCanonicalizationRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
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
public class SemanticBehaviorWriteBackCanonicalizationService {

    private static final String RULES_CLASSPATH_LOCATION =
            "npdev-semantic-behavior-canonicalization/semantic-behavior-canonicalization-rules.json";

    private static final Path PLAN_ROOT =
            Paths.get("runtime-data", "semantic-behavior-canonicalization-plans");

    private final ObjectMapper objectMapper;

    public SemanticBehaviorWriteBackCanonicalizationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> summary() {
        Map<String, Object> rules = loadRules();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("rulesPath", RULES_CLASSPATH_LOCATION);
        response.put("version", rules.getOrDefault("version", 1));
        response.put("supportedActions", rules.getOrDefault("supportedActions", List.of()));
        response.put("planStoragePath", PLAN_ROOT.toString().replace("\\", "/"));
        response.put("mode", "semantic-behavior-write-back-canonicalization-foundation");
        return response;
    }

    public Map<String, Object> history() {
        List<Map<String, Object>> items = new ArrayList<>();

        try {
            // try-with-resources (QUAL-2) -- see TemplateLibraryManagementService for the full note.
            if (Files.exists(PLAN_ROOT)) {
                try (var paths = Files.list(PLAN_ROOT)) {
                    paths
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
            }
        } catch (Exception ignored) {
        }

        items.sort(Comparator.comparing(
                item -> String.valueOf(item.getOrDefault("plannedAt", "")),
                Comparator.reverseOrder()
        ));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", items.size());
        response.put("items", items);
        return response;
    }

    public Map<String, Object> canonicalize(SemanticBehaviorCanonicalizationRequest request) {
        validate(request);

        Map<String, Object> rules = loadRules();
        Map<String, Object> matchedRule = findRule(rules, request.getActionType());

        String outcome;
        String canonicalTarget;
        String note;

        if (matchedRule == null) {
            outcome = String.valueOf(rules.getOrDefault("unsupportedActionsDefaultOutcome", "UNSUPPORTED"));
            canonicalTarget = "unmapped";
            note = "Action type is not currently supported for semantic behavior canonicalization.";
        } else {
            outcome = String.valueOf(matchedRule.getOrDefault("defaultOutcome", "REVIEW_REQUIRED"));
            canonicalTarget = String.valueOf(matchedRule.getOrDefault("canonicalTarget", "unmapped"));
            note = String.valueOf(matchedRule.getOrDefault("notes", ""));
        }

        String planId = UUID.randomUUID().toString();
        String plannedAt = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        List<Map<String, Object>> planItems = buildPlanItems(request, outcome, canonicalTarget);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("planId", planId);
        response.put("requestId", request.getRequestId());
        response.put("actionType", request.getActionType());
        response.put("flowName", request.getFlowName());
        response.put("stepName", request.getStepName());
        response.put("requestedBy", request.getRequestedBy());
        response.put("canonicalTarget", canonicalTarget);
        response.put("outcome", outcome);
        response.put("note", note);
        response.put("planItems", planItems);
        response.put("plannedAt", plannedAt);
        response.put("status", "PLANNED");

        persistPlan(planId, response);
        return response;
    }

    private void validate(SemanticBehaviorCanonicalizationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        if (isBlank(request.getActionType())) {
            throw new IllegalArgumentException("actionType is required.");
        }
        if (isBlank(request.getFlowName())) {
            throw new IllegalArgumentException("flowName is required.");
        }
        if (isBlank(request.getRequestedBy())) {
            throw new IllegalArgumentException("requestedBy is required.");
        }

        switch (request.getActionType()) {
            case "addApprovalStep" -> {
                if (isBlank(request.getStepName())) {
                    throw new IllegalArgumentException("stepName is required for addApprovalStep.");
                }
            }
            case "addNotificationStep" -> {
                if (isBlank(request.getStepName())) {
                    throw new IllegalArgumentException("stepName is required for addNotificationStep.");
                }
                if (isBlank(request.getNotificationChannel())) {
                    throw new IllegalArgumentException("notificationChannel is required for addNotificationStep.");
                }
            }
            case "setRetryPolicy" -> {
                if (request.getRetryCount() == null) {
                    throw new IllegalArgumentException("retryCount is required for setRetryPolicy.");
                }
            }
            case "setTimeoutPolicy" -> {
                if (request.getTimeoutSeconds() == null) {
                    throw new IllegalArgumentException("timeoutSeconds is required for setTimeoutPolicy.");
                }
            }
            default -> {
            }
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> loadRules() {
        try (InputStream inputStream = new ClassPathResource(RULES_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load semantic behavior canonicalization rules.", e);
        }
    }

    private Map<String, Object> findRule(Map<String, Object> rules, String actionType) {
        Object raw = rules.get("supportedActions");
        if (!(raw instanceof List<?> list)) {
            return null;
        }

        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) map;
                if (actionType.equals(String.valueOf(typed.get("actionType")))) {
                    return typed;
                }
            }
        }
        return null;
    }

    private List<Map<String, Object>> buildPlanItems(
            SemanticBehaviorCanonicalizationRequest request,
            String outcome,
            String canonicalTarget
    ) {
        List<Map<String, Object>> items = new ArrayList<>();

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("step", 1);
        item.put("actionType", request.getActionType());
        item.put("canonicalTarget", canonicalTarget);

        switch (request.getActionType()) {
            case "addApprovalStep" ->
                    item.put("description", "Plan approval-step insertion for flow '" + request.getFlowName() + "' at step '" + request.getStepName() + "'.");
            case "addNotificationStep" ->
                    item.put("description", "Plan notification-step insertion for flow '" + request.getFlowName() + "' at step '" + request.getStepName() + "' using channel '" + request.getNotificationChannel() + "'.");
            case "setRetryPolicy" ->
                    item.put("description", "Plan retry policy update for flow '" + request.getFlowName() + "' with retryCount=" + request.getRetryCount() + ".");
            case "setTimeoutPolicy" ->
                    item.put("description", "Plan timeout policy update for flow '" + request.getFlowName() + "' with timeoutSeconds=" + request.getTimeoutSeconds() + ".");
            default ->
                    item.put("description", "No canonicalization plan available for unsupported action type.");
        }

        item.put("outcome", outcome);
        items.add(item);

        return items;
    }

    private void persistPlan(String planId, Map<String, Object> plan) {
        try {
            Files.createDirectories(PLAN_ROOT);
            Path output = PLAN_ROOT.resolve(planId + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), plan);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist semantic behavior canonicalization plan.", e);
        }
    }
}