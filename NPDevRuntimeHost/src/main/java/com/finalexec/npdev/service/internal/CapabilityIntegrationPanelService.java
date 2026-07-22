package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.generated.runtime.service.KernelFacade;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CapabilityIntegrationPanelService {

    private static final String MODEL_CLASSPATH_LOCATION = "npdev/model.json";
    private static final Path DIRECT_EXECUTION_ROOT = Path.of("runtime-data", "direct-executions");

    private final ObjectMapper objectMapper;
    private final KernelFacade kernelFacade;
    private final FlowBuilderService flowBuilderService;
    private final PublicationChainReferenceResolver referenceResolver;
    private final CapabilityReadinessClassifier capabilityReadinessClassifier;

    public CapabilityIntegrationPanelService(
            ObjectMapper objectMapper,
            KernelFacade kernelFacade,
            FlowBuilderService flowBuilderService,
            PublicationChainReferenceResolver referenceResolver,
            CapabilityReadinessClassifier capabilityReadinessClassifier
    ) {
        this.objectMapper = objectMapper;
        this.kernelFacade = kernelFacade;
        this.flowBuilderService = flowBuilderService;
        this.referenceResolver = referenceResolver;
        this.capabilityReadinessClassifier = capabilityReadinessClassifier;
    }

    public Map<String, Object> catalog() {
        Map<String, Object> model = loadModel();
        List<Map<String, Object>> runtimeFlows = runtimeFlows(model);
        List<Map<String, Object>> draftFlows = draftFlows();
        List<Map<String, Object>> capabilities = capabilityCards(model, runtimeFlows, draftFlows);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Capability Integration Panel");
        response.put("modelSource", MODEL_CLASSPATH_LOCATION);
        response.put("capabilityCount", capabilities.size());
        response.put("readyCount", countByClassification(capabilities, "RUNNABLE_TODAY"));
        response.put("partialCount", countByClassification(capabilities, "PARTIALLY_RUNNABLE"));
        response.put("inspectionOnlyCount", countByClassification(capabilities, "INSPECTION_ONLY"));
        response.put("blockedCount", countBlocked(capabilities));
        response.put("capabilities", capabilities);
        return response;
    }

    public Map<String, Object> bindings() {
        Map<String, Object> model = loadModel();
        List<Map<String, Object>> runtimeFlows = runtimeFlows(model);
        List<Map<String, Object>> draftFlows = draftFlows();
        List<Map<String, Object>> capabilities = capabilityCards(model, runtimeFlows, draftFlows);
        List<Map<String, Object>> bindings = new ArrayList<>();

        for (Map<String, Object> capability : capabilities) {
            Map<String, Object> binding = new LinkedHashMap<>();
            binding.put("capabilityName", capability.get("capabilityName"));
            binding.put("capabilityType", capability.get("capabilityType"));
            binding.put("bindingAdapter", capability.get("bindingAdapter"));
            binding.put("bindingStatus", capability.get("bindingStatus"));
            binding.put("readinessStatus", capability.get("readinessStatus"));
            binding.put("readinessClassification", capability.get("readinessClassification"));
            binding.put("runtimeProofStatus", capability.get("runtimeProofStatus"));
            binding.put("runtimeFlowCount", capability.get("runtimeFlowCount"));
            binding.put("draftFlowCount", capability.get("draftFlowCount"));
            binding.put("successfulExecutionCount", capability.get("successfulExecutionCount"));
            binding.put("latestSuccessfulDirectExecutionReference", capability.get("latestSuccessfulDirectExecutionReference"));
            binding.put("linkedFlows", capability.get("linkedFlows"));
            binding.put("guidance", capability.get("guidance"));
            bindings.add(binding);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Capability Integration Panel");
        response.put("bindingCount", bindings.size());
        response.put("boundCount", countByBinding(bindings, "BOUND"));
        response.put("missingBindingCount", countByBinding(bindings, "MISSING_BINDING"));
        response.put("bindings", bindings);
        return response;
    }

    public Map<String, Object> readiness() {
        Map<String, Object> model = loadModel();
        List<Map<String, Object>> runtimeFlows = runtimeFlows(model);
        List<Map<String, Object>> draftFlows = draftFlows();
        List<Map<String, Object>> capabilities = capabilityCards(model, runtimeFlows, draftFlows);
        List<Map<String, Object>> readinessCards = new ArrayList<>();

        for (Map<String, Object> capability : capabilities) {
            Map<String, Object> readiness = new LinkedHashMap<>();
            readiness.put("capabilityName", capability.get("capabilityName"));
            readiness.put("readinessStatus", capability.get("readinessStatus"));
            readiness.put("readinessClassification", capability.get("readinessClassification"));
            readiness.put("readinessBand", capability.get("readinessBand"));
            readiness.put("runtimeProofStatus", capability.get("runtimeProofStatus"));
            readiness.put("bindingStatus", capability.get("bindingStatus"));
            readiness.put("linkedFlowCount", capability.get("linkedFlowCount"));
            readiness.put("runtimeFlowCount", capability.get("runtimeFlowCount"));
            readiness.put("draftFlowCount", capability.get("draftFlowCount"));
            readiness.put("successfulExecutionCount", capability.get("successfulExecutionCount"));
            readiness.put("latestSuccessfulDirectExecutionReference", capability.get("latestSuccessfulDirectExecutionReference"));
            readiness.put("readinessNarrative", capability.get("readinessNarrative"));
            readiness.put("guidance", capability.get("guidance"));
            readiness.put("notes", capability.get("notes"));
            readinessCards.add(readiness);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Capability Integration Panel");
        response.put("readyCount", countByClassification(capabilities, "RUNNABLE_TODAY"));
        response.put("runnableTodayCount", countByClassification(capabilities, "RUNNABLE_TODAY"));
        response.put("partiallyRunnableCount", countByClassification(capabilities, "PARTIALLY_RUNNABLE"));
        response.put("inspectionOnlyCount", countByClassification(capabilities, "INSPECTION_ONLY"));
        response.put("draftReadyCount", countByStatus(capabilities, "READY_FOR_DRAFT_CONFIGURATION"));
        response.put("configuredButUnusedCount", countByStatus(capabilities, "CONFIGURED_BUT_UNUSED"));
        response.put("blockedCount", countBlocked(capabilities));
        response.put("definedButNotConnectedCount", countByStatus(capabilities, "DEFINED_BUT_NOT_CONNECTED"));
        response.put("capabilities", readinessCards);
        return response;
    }

    public Map<String, Object> flowLinks() {
        Map<String, Object> model = loadModel();
        List<Map<String, Object>> runtimeFlows = runtimeFlows(model);
        List<Map<String, Object>> draftFlows = draftFlows();
        List<Map<String, Object>> capabilities = capabilityCards(model, runtimeFlows, draftFlows);
        List<Map<String, Object>> flowLinks = new ArrayList<>();

        for (Map<String, Object> runtimeFlow : runtimeFlows) {
            flowLinks.add(flowLink(runtimeFlow, "runtime"));
        }
        for (Map<String, Object> draftFlow : draftFlows) {
            flowLinks.add(flowLink(draftFlow, "draft"));
        }

        List<Map<String, Object>> orphans = new ArrayList<>();
        for (Map<String, Object> capability : capabilities) {
            if (intValue(capability.get("linkedFlowCount")) == 0) {
                Map<String, Object> orphan = new LinkedHashMap<>();
                orphan.put("capabilityName", capability.get("capabilityName"));
                orphan.put("readinessStatus", capability.get("readinessStatus"));
                orphan.put("guidance", capability.get("guidance"));
                orphans.add(orphan);
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Capability Integration Panel");
        response.put("runtimeFlowCount", runtimeFlows.size());
        response.put("draftFlowCount", draftFlows.size());
        response.put("linkedFlowCount", flowLinks.size());
        response.put("flows", flowLinks);
        response.put("orphanCapabilities", orphans);
        return response;
    }

    private Map<String, Object> flowLink(Map<String, Object> rawFlow, String flowKind) {
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("flowKind", flowKind);
        flow.put("flowName", firstNonBlank(rawFlow.get("name"), rawFlow.get("flowName")));
        flow.put("displayName", firstNonBlank(rawFlow.get("displayName"), rawFlow.get("name"), rawFlow.get("flowName")));
        flow.put("concept", stringValue(rawFlow.get("concept")));
        flow.put("builderStatus", stringValue(rawFlow.get("builderStatus")));
        flow.put("stepCount", intValue(rawFlow.get("stepCount")));
        flow.put("tenantId", stringValue(rawFlow.get("tenantId")));
        flow.put("capabilityConnections", stringList(rawFlow.get("capabilityConnections")));
        flow.put("capabilityConnectionCount", stringList(rawFlow.get("capabilityConnections")).size());
        flow.put("integrationStatus", flowIntegrationStatus(stringList(rawFlow.get("capabilityConnections"))));
        return flow;
    }

    private String flowIntegrationStatus(List<String> capabilityConnections) {
        if (capabilityConnections.isEmpty()) {
            return "NO_CAPABILITY_LINKS";
        }
        return "CAPABILITY_LINKS_PRESENT";
    }

    private List<Map<String, Object>> capabilityCards(
            Map<String, Object> model,
            List<Map<String, Object>> runtimeFlows,
            List<Map<String, Object>> draftFlows
    ) {
        Map<String, String> bindings = bindingLookup(model);
        Map<String, Integer> successfulExecutions = successfulExecutionCountsByCapability();
        Map<String, String> latestSuccessfulExecutionReferences = latestSuccessfulExecutionReferenceByCapability();
        List<Map<String, Object>> capabilities = new ArrayList<>();

        for (Map<String, Object> raw : mapList(model.get("capabilities"))) {
            String capabilityName = stringValue(raw.get("name"));
            List<String> runtimeUsage = usage(runtimeFlows, capabilityName);
            List<String> draftUsage = usage(draftFlows, capabilityName);
            String bindingAdapter = bindings.getOrDefault(capabilityName, "");
            String bindingStatus = bindingAdapter.isBlank() ? "MISSING_BINDING" : "BOUND";
            String readinessStatus = readinessStatus(bindingStatus, runtimeUsage, draftUsage);
            int successfulExecutionCount = successfulExecutions.getOrDefault(capabilityName, 0);
            String readinessClassification = capabilityReadinessClassifier.classify(
                    bindingStatus,
                    runtimeUsage,
                    draftUsage,
                    successfulExecutionCount
            );
            String runtimeProofStatus = capabilityReadinessClassifier.proofStatus(
                    readinessClassification,
                    bindingStatus,
                    runtimeUsage,
                    draftUsage,
                    successfulExecutionCount
            );
            List<String> operations = operations(raw.get("operations"));

            Map<String, Object> capability = new LinkedHashMap<>();
            capability.put("capabilityName", capabilityName);
            capability.put("capabilityType", stringValue(raw.get("type")));
            capability.put("operations", operations);
            capability.put("operationCount", operations.size());
            capability.put("bindingAdapter", bindingAdapter);
            capability.put("bindingStatus", bindingStatus);
            capability.put("readinessStatus", readinessStatus);
            capability.put("readinessClassification", readinessClassification);
            capability.put("readinessBand", capabilityReadinessClassifier.band(readinessClassification));
            capability.put("runtimeProofStatus", runtimeProofStatus);
            capability.put("runtimeFlowCount", runtimeUsage.size());
            capability.put("draftFlowCount", draftUsage.size());
            capability.put("linkedFlowCount", runtimeUsage.size() + draftUsage.size());
            capability.put("runtimeFlows", runtimeUsage);
            capability.put("draftFlows", draftUsage);
            capability.put("linkedFlows", merge(runtimeUsage, draftUsage));
            capability.put("successfulExecutionCount", successfulExecutionCount);
            capability.put("latestSuccessfulDirectExecutionReference", latestSuccessfulExecutionReferences.getOrDefault(capabilityName, ""));
            capability.put("readinessNarrative", capabilityReadinessClassifier.narrative(
                    readinessClassification,
                    runtimeProofStatus,
                    runtimeUsage,
                    draftUsage,
                    successfulExecutionCount
            ));
            capability.put("guidance", guidanceFor(
                    capabilityName,
                    bindingStatus,
                    readinessStatus,
                    readinessClassification,
                    runtimeUsage,
                    draftUsage,
                    successfulExecutionCount
            ));
            capability.put("notes", notesFor(bindingAdapter, runtimeUsage, draftUsage, runtimeProofStatus));
            capabilities.add(capability);
        }

        return capabilities;
    }

    private String notesFor(
            String bindingAdapter,
            List<String> runtimeUsage,
            List<String> draftUsage,
            String runtimeProofStatus
    ) {
        if (bindingAdapter.isBlank()) {
            return "No adapter binding is configured for this capability.";
        }
        if ("SUCCESSFUL_RUNTIME_PROOF".equals(runtimeProofStatus)) {
            return "Capability has successful runtime proof through the governed same-tenant execution path.";
        }
        if (!runtimeUsage.isEmpty()) {
            return "Runtime flows already depend on this bound capability.";
        }
        if (!draftUsage.isEmpty()) {
            return "Draft flows depend on this capability, but runtime usage is not visible yet.";
        }
        return "Capability is configured but not currently linked by visible flows.";
    }

    private List<String> guidanceFor(
            String capabilityName,
            String bindingStatus,
            String readinessStatus,
            String readinessClassification,
            List<String> runtimeUsage,
            List<String> draftUsage,
            int successfulExecutionCount
    ) {
        List<String> guidance = new ArrayList<>();
        if ("MISSING_BINDING".equals(bindingStatus)) {
            guidance.add("Bind an adapter/provider before expecting runtime execution.");
        }
        if ("RUNNABLE_TODAY".equals(readinessClassification)) {
            guidance.add("Same-tenant direct execution has already produced successful runtime proof for this capability.");
        }
        if (!runtimeUsage.isEmpty()) {
            guidance.add("Validate the runtime adapter path for flows: " + String.join(", ", runtimeUsage));
        }
        if (!runtimeUsage.isEmpty() && successfulExecutionCount == 0) {
            guidance.add("Runtime flow linkage exists, but a successful governed execution proof is still missing.");
        }
        if (runtimeUsage.isEmpty() && !draftUsage.isEmpty()) {
            guidance.add("Capability is present in drafts; promote or align runtime flow definitions when ready.");
        }
        if ("CONFIGURED_BUT_UNUSED".equals(readinessStatus) || "DEFINED_BUT_NOT_CONNECTED".equals(readinessStatus)) {
            guidance.add("Review whether " + capabilityName + " is still needed or should be linked to a flow.");
        }
        if (guidance.isEmpty()) {
            guidance.add("Capability integration is visible and does not currently expose a blocker.");
        }
        return guidance;
    }

    private String readinessStatus(String bindingStatus, List<String> runtimeUsage, List<String> draftUsage) {
        if ("MISSING_BINDING".equals(bindingStatus) && (!runtimeUsage.isEmpty() || !draftUsage.isEmpty())) {
            return "BLOCKED_MISSING_BINDING";
        }
        if ("BOUND".equals(bindingStatus) && !runtimeUsage.isEmpty()) {
            return "READY_FOR_RUNTIME_EXECUTION";
        }
        if ("BOUND".equals(bindingStatus) && runtimeUsage.isEmpty() && !draftUsage.isEmpty()) {
            return "READY_FOR_DRAFT_CONFIGURATION";
        }
        if ("BOUND".equals(bindingStatus)) {
            return "CONFIGURED_BUT_UNUSED";
        }
        return "DEFINED_BUT_NOT_CONNECTED";
    }

    private int countByBinding(List<Map<String, Object>> records, String status) {
        int count = 0;
        for (Map<String, Object> record : records) {
            if (status.equals(record.get("bindingStatus"))) {
                count++;
            }
        }
        return count;
    }

    private int countByStatus(List<Map<String, Object>> records, String status) {
        int count = 0;
        for (Map<String, Object> record : records) {
            if (status.equals(record.get("readinessStatus"))) {
                count++;
            }
        }
        return count;
    }

    private int countByClassification(List<Map<String, Object>> records, String classification) {
        int count = 0;
        for (Map<String, Object> record : records) {
            if (classification.equals(record.get("readinessClassification"))) {
                count++;
            }
        }
        return count;
    }

    private int countBlocked(List<Map<String, Object>> records) {
        int count = 0;
        for (Map<String, Object> record : records) {
            String readinessClassification = stringValue(record.get("readinessClassification"));
            if ("BLOCKED".equals(readinessClassification)) {
                count++;
            }
        }
        return count;
    }

    private List<Map<String, Object>> runtimeFlows(Map<String, Object> model) {
        return kernelFacade.listFlowDefinitions().stream()
                .map(flow -> {
                    Map<String, Object> runtimeFlow = new LinkedHashMap<>();
                    runtimeFlow.put("name", flow.name());
                    runtimeFlow.put("displayName", flow.name());
                    runtimeFlow.put("concept", flow.concept());
                    runtimeFlow.put("inputSchema", flow.inputSchema());
                    runtimeFlow.put("outputSchema", flow.outputSchema());
                    runtimeFlow.put("capabilityConnections", capabilityConnectionsForFlow(model, flow.name()));
                    return runtimeFlow;
                })
                .toList();
    }

    private List<Map<String, Object>> draftFlows() {
        List<Map<String, Object>> drafts = new ArrayList<>();
        Object rawItems = flowBuilderService.listDrafts().get("items");
        for (Map<String, Object> item : mapList(rawItems)) {
            Map<String, Object> draft = new LinkedHashMap<>();
            draft.put("flowName", item.get("flowName"));
            draft.put("displayName", item.get("displayName"));
            draft.put("builderStatus", item.get("builderStatus"));
            draft.put("stepCount", item.get("stepCount"));
            draft.put("tenantId", item.get("tenantId"));
            draft.put("capabilityConnections", draftCapabilityConnections(item));
            drafts.add(draft);
        }
        return drafts;
    }

    private List<String> capabilityConnectionsForFlow(Map<String, Object> model, String flowName) {
        for (Map<String, Object> flow : modelFlows(model)) {
            if (flowName.equals(flow.get("name"))) {
                return stringList(flow.get("capabilityConnections"));
            }
        }
        return List.of();
    }

    private List<String> draftCapabilityConnections(Map<String, Object> item) {
        List<String> capabilities = new ArrayList<>();
        Map<String, Object> definition = mapValue(item.get("definition"));
        for (Map<String, Object> step : mapList(definition.get("steps"))) {
            String capability = firstNonBlank(step.get("capabilityKey"), step.get("cap"));
            if (!capability.isBlank() && !capabilities.contains(capability)) {
                capabilities.add(capability);
            }
        }
        return capabilities;
    }

    private Map<String, String> bindingLookup(Map<String, Object> model) {
        Map<String, String> bindings = new LinkedHashMap<>();
        for (Map<String, Object> binding : mapList(model.get("bindings"))) {
            bindings.put(stringValue(binding.get("capability")), stringValue(binding.get("adapter")));
        }
        return bindings;
    }

    private List<String> usage(List<Map<String, Object>> flows, String capabilityName) {
        List<String> usage = new ArrayList<>();
        for (Map<String, Object> flow : flows) {
            if (stringList(flow.get("capabilityConnections")).contains(capabilityName)) {
                usage.add(firstNonBlank(flow.get("name"), flow.get("flowName")));
            }
        }
        return usage;
    }

    private List<String> merge(List<String> left, List<String> right) {
        List<String> merged = new ArrayList<>(left);
        for (String item : right) {
            if (!merged.contains(item)) {
                merged.add(item);
            }
        }
        return merged;
    }

    private List<Map<String, Object>> modelFlows(Map<String, Object> model) {
        List<Map<String, Object>> flows = new ArrayList<>();
        for (Map<String, Object> flow : mapList(model.get("flows"))) {
            Map<String, Object> normalized = new LinkedHashMap<>(flow);
            normalized.put("capabilityConnections", merge(
                    stringList(flow.get("capabilityConnections")),
                    capabilityConnectionsFromSteps(flow.get("steps"))
            ));
            flows.add(normalized);
        }
        for (Map<String, Object> rule : mapList(model.get("orchestrationRules"))) {
            if (!stringValue(rule.get("name")).isBlank()) {
                Map<String, Object> flow = new LinkedHashMap<>();
                flow.put("name", rule.get("name"));
                flow.put("concept", stringValue(rule.get("concept")));
                flow.put("capabilityConnections", capabilityConnectionsFromActions(rule.get("actions")));
                flows.add(flow);
            }
        }
        return flows;
    }

    private Map<String, Integer> successfulExecutionCountsByCapability() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> record : referenceResolver.readRecords(DIRECT_EXECUTION_ROOT)) {
            if (!isSuccessfulExecution(record)) {
                continue;
            }
            for (String capabilityName : capabilityNamesFromExecutionRecord(record)) {
                counts.put(capabilityName, counts.getOrDefault(capabilityName, 0) + 1);
            }
        }
        return counts;
    }

    private Map<String, String> latestSuccessfulExecutionReferenceByCapability() {
        Map<String, String> references = new LinkedHashMap<>();
        for (Map<String, Object> record : referenceResolver.readRecords(DIRECT_EXECUTION_ROOT)) {
            if (!isSuccessfulExecution(record)) {
                continue;
            }
            String reference = referenceResolver.extractFirstString(record, "directExecutionReference");
            for (String capabilityName : capabilityNamesFromExecutionRecord(record)) {
                references.putIfAbsent(capabilityName, reference);
            }
        }
        return references;
    }

    private boolean isSuccessfulExecution(Map<String, Object> record) {
        String resultStatus = referenceResolver.extractFirstString(record, "executionResultStatus");
        return "OK".equals(resultStatus) || "WAITING_EVENT".equals(resultStatus);
    }

    private List<String> capabilityNamesFromExecutionRecord(Map<String, Object> record) {
        List<String> capabilities = new ArrayList<>();
        Map<String, Object> route = mapValue(record.get("capabilityRoute"));
        for (Map<String, Object> capability : mapList(route.get("capabilities"))) {
            String capabilityName = stringValue(capability.get("capabilityName"));
            if (!capabilityName.isBlank() && !capabilities.contains(capabilityName)) {
                capabilities.add(capabilityName);
            }
        }
        return capabilities;
    }

    private List<String> capabilityConnectionsFromActions(Object rawActions) {
        List<String> capabilities = new ArrayList<>();
        if (rawActions instanceof Collection<?> collection) {
            for (Object action : collection) {
                if (action instanceof Map<?, ?> rawMap) {
                    String capability = "";
                    if (rawMap.containsKey("capability")) {
                        capability = String.valueOf(rawMap.get("capability"));
                    } else if ("notify".equals(String.valueOf(rawMap.get("type")))) {
                        capability = "notification";
                    } else if ("create".equals(String.valueOf(rawMap.get("type")))
                            || "update".equals(String.valueOf(rawMap.get("type")))
                            || "delete".equals(String.valueOf(rawMap.get("type")))) {
                        capability = "persistence";
                    }
                    if (!capability.isBlank() && !capabilities.contains(capability)) {
                        capabilities.add(capability);
                    }
                }
            }
        }
        return capabilities;
    }

    private List<String> capabilityConnectionsFromSteps(Object rawSteps) {
        List<String> capabilities = new ArrayList<>();
        for (Map<String, Object> step : mapList(rawSteps)) {
            String capability = firstNonBlank(step.get("capabilityKey"), step.get("cap"));
            if (!capability.isBlank() && !capabilities.contains(capability)) {
                capabilities.add(capability);
            }
            for (String nested : capabilityConnectionsFromSteps(step.get("thenSteps"))) {
                if (!capabilities.contains(nested)) {
                    capabilities.add(nested);
                }
            }
            for (String nested : capabilityConnectionsFromSteps(step.get("elseSteps"))) {
                if (!capabilities.contains(nested)) {
                    capabilities.add(nested);
                }
            }
        }
        return capabilities;
    }

    private List<String> operations(Object rawOperations) {
        List<String> operations = new ArrayList<>();
        if (rawOperations instanceof Collection<?> collection) {
            for (Object operation : collection) {
                if (operation instanceof Map<?, ?> rawMap) {
                    Object name = rawMap.get("name");
                    if (name != null) {
                        operations.add(String.valueOf(name));
                    }
                } else if (operation != null) {
                    operations.add(String.valueOf(operation));
                }
            }
        }
        return operations;
    }

    private Map<String, Object> loadModel() {
        try (InputStream inputStream = new ClassPathResource(MODEL_CLASSPATH_LOCATION).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load capability integration model from classpath.", e);
        }
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item instanceof Map<?, ?> rawMap) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    rawMap.forEach((key, entryValue) -> normalized.put(String.valueOf(key), entryValue));
                    items.add(normalized);
                }
            }
        }
        return items;
    }

    private List<String> stringList(Object value) {
        List<String> items = new ArrayList<>();
        if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                String text = stringValue(item);
                if (!text.isBlank()) {
                    items.add(text);
                }
            }
        }
        return items;
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstNonBlank(Object... candidates) {
        for (Object candidate : candidates) {
            String value = stringValue(candidate);
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }
}
