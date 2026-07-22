package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import com.npdev.generated.runtime.service.KernelFacade;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RuntimeTopologyExplorerService {

    private static final Path DIRECT_EXECUTION_ROOT = Path.of("runtime-data", "direct-executions");
    private static final Path CROSS_TENANT_GOVERNANCE_ROOT = Path.of("runtime-data", "cross-tenant-governance");
    private static final Path WORKSPACE_DECISION_ROOT = Path.of("runtime-data", "governance-workspace-decisions");
    private static final Path COMPILER_IMPACT_ROOT = Path.of("runtime-data", "compiler-impact-classifications");
    private static final Path GENERATED_PREVIEW_ROOT = Path.of("runtime-data", "generated-artifact-previews");

    private final KernelFacade kernelFacade;
    private final FlowBuilderService flowBuilderService;
    private final CapabilityIntegrationPanelService capabilityIntegrationPanelService;
    private final GovernanceWorkspaceService governanceWorkspaceService;
    private final PublicationChainReferenceResolver referenceResolver;

    public RuntimeTopologyExplorerService(
            KernelFacade kernelFacade,
            FlowBuilderService flowBuilderService,
            CapabilityIntegrationPanelService capabilityIntegrationPanelService,
            GovernanceWorkspaceService governanceWorkspaceService,
            PublicationChainReferenceResolver referenceResolver
    ) {
        this.kernelFacade = kernelFacade;
        this.flowBuilderService = flowBuilderService;
        this.capabilityIntegrationPanelService = capabilityIntegrationPanelService;
        this.governanceWorkspaceService = governanceWorkspaceService;
        this.referenceResolver = referenceResolver;
    }

    public Map<String, Object> topology() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        Set<String> seenNodes = new LinkedHashSet<>();

        List<Map<String, Object>> executionRecords = recentDirectExecutions();
        List<Map<String, Object>> previewRecords = recentPreviewRecords();
        List<Map<String, Object>> governanceRecords = recentGovernanceRecords();
        List<Map<String, Object>> capabilityCards = capabilityCards();

        for (Map<String, Object> runtimeFlow : runtimeFlows()) {
            addNode(nodes, seenNodes, node(
                    "runtime-flow:" + stringValue(runtimeFlow.get("flowName")),
                    "runtime-flow",
                    stringValue(runtimeFlow.get("flowName")),
                    Map.of("concept", stringValue(runtimeFlow.get("concept")))
            ));
        }

        for (Map<String, Object> draftFlow : draftFlows()) {
            addNode(nodes, seenNodes, node(
                    "draft-flow:" + stringValue(draftFlow.get("flowName")),
                    "draft-flow",
                    stringValue(draftFlow.get("displayName")),
                    Map.of("builderStatus", stringValue(draftFlow.get("builderStatus")))
            ));
        }

        for (Map<String, Object> capability : capabilityCards) {
            addNode(nodes, seenNodes, node(
                    "capability:" + stringValue(capability.get("capabilityName")),
                    "capability",
                    stringValue(capability.get("capabilityName")),
                    Map.of(
                            "bindingAdapter", stringValue(capability.get("bindingAdapter")),
                            "readinessStatus", stringValue(capability.get("readinessStatus"))
                    )
            ));
        }

        for (Map<String, Object> execution : executionRecords) {
            String executionRef = stringValue(execution.get("directExecutionReference"));
            addNode(nodes, seenNodes, node(
                    "execution:" + executionRef,
                    "execution",
                    executionRef,
                    Map.of(
                            "flowName", stringValue(execution.get("flowName")),
                            "tenantScopeStatus", stringValue(execution.get("tenantScopeStatus")),
                            "executionResultStatus", stringValue(execution.get("executionResultStatus"))
                    )
            ));
            addEdge(edges, "execution:" + executionRef, "runtime-flow:" + stringValue(execution.get("flowName")), "invokes");
            for (Map<String, Object> route : listOfMaps(nestedValue(execution, "capabilityRoute", "capabilities"))) {
                addEdge(edges, "execution:" + executionRef, "capability:" + stringValue(route.get("capabilityName")), "uses-capability");
            }
            String governanceRef = stringValue(nestedValue(execution, "governanceRecord", "governanceReference"));
            if (!governanceRef.isBlank()) {
                addEdge(edges, "execution:" + executionRef, "governance:" + governanceRef, "governed-by");
            }
        }

        for (Map<String, Object> governance : governanceRecords) {
            String reference = firstNonBlank(
                    governance.get("governanceReference"),
                    governance.get("targetReference"),
                    governance.get("crossTenantGovernanceId"),
                    governance.get("workspaceDecisionId")
            );
            addNode(nodes, seenNodes, node(
                    "governance:" + reference,
                    "governance",
                    reference,
                    Map.of("status", governanceStatus(governance))
            ));
        }

        for (Map<String, Object> preview : previewRecords) {
            String previewRef = firstNonBlank(
                    preview.get("previewReference"),
                    preview.get("classificationReference"),
                    preview.get("diffReference")
            );
            addNode(nodes, seenNodes, node(
                    "preview:" + previewRef,
                    "preview",
                    previewRef,
                    Map.of("impactClass", previewImpactClass(preview))
            ));
            addPreviewEdges(edges, preview);
        }

        addCapabilityEdges(edges, capabilityCards);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Runtime Topology Explorer");
        response.put("nodeCount", nodes.size());
        response.put("edgeCount", edges.size());
        response.put("executionNodeCount", executionRecords.size());
        response.put("capabilityNodeCount", capabilityCards.size());
        response.put("governanceNodeCount", governanceRecords.size());
        response.put("previewNodeCount", previewRecords.size());
        response.put("nodes", nodes);
        response.put("edges", edges);
        return response;
    }

    public Map<String, Object> executions() {
        List<Map<String, Object>> runtimeFlows = runtimeFlows();
        List<Map<String, Object>> draftFlows = draftFlows();
        List<Map<String, Object>> executionRecords = recentDirectExecutions();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Runtime Topology Explorer");
        response.put("runtimeFlowCount", runtimeFlows.size());
        response.put("draftFlowCount", draftFlows.size());
        response.put("executionCount", executionRecords.size());
        response.put("runtimeFlows", runtimeFlows);
        response.put("draftFlows", draftFlows);
        response.put("executions", executionRecords);
        return response;
    }

    public Map<String, Object> capabilities() {
        List<Map<String, Object>> capabilityCards = capabilityCards();
        List<Map<String, Object>> capabilityLinks = new ArrayList<>();

        for (Map<String, Object> capability : capabilityCards) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("capabilityName", capability.get("capabilityName"));
            link.put("bindingAdapter", capability.get("bindingAdapter"));
            link.put("readinessStatus", capability.get("readinessStatus"));
            link.put("runtimeFlows", capability.get("runtimeFlows"));
            link.put("draftFlows", capability.get("draftFlows"));
            link.put("linkedFlows", capability.get("linkedFlows"));
            capabilityLinks.add(link);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Runtime Topology Explorer");
        response.put("capabilityCount", capabilityLinks.size());
        response.put("capabilities", capabilityLinks);
        return response;
    }

    public Map<String, Object> links() {
        List<Map<String, Object>> links = new ArrayList<>();
        links.addAll(executionLinks());
        links.addAll(previewLinks());
        links.addAll(governanceLinks());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("surfaceName", "Runtime Topology Explorer");
        response.put("linkCount", links.size());
        response.put("links", links);
        return response;
    }

    private List<Map<String, Object>> executionLinks() {
        List<Map<String, Object>> links = new ArrayList<>();
        for (Map<String, Object> execution : recentDirectExecutions()) {
            String executionRef = stringValue(execution.get("directExecutionReference"));
            String flowName = stringValue(execution.get("flowName"));
            if (!executionRef.isBlank() && !flowName.isBlank()) {
                links.add(link("execution", executionRef, "runtime-flow", flowName, "invokes"));
            }
            for (Map<String, Object> route : listOfMaps(nestedValue(execution, "capabilityRoute", "capabilities"))) {
                links.add(link(
                        "execution",
                        executionRef,
                        "capability",
                        stringValue(route.get("capabilityName")),
                        "uses-capability"
                ));
            }
            String governanceReference = stringValue(nestedValue(execution, "governanceRecord", "governanceReference"));
            if (!governanceReference.isBlank()) {
                links.add(link("execution", executionRef, "governance", governanceReference, "governed-by"));
            }
        }
        return links;
    }

    private List<Map<String, Object>> previewLinks() {
        List<Map<String, Object>> links = new ArrayList<>();
        for (Map<String, Object> preview : recentPreviewRecords()) {
            String diffReference = stringValue(preview.get("diffReference"));
            String graphReference = stringValue(preview.get("graphReference"));
            String classificationReference = stringValue(preview.get("classificationReference"));
            String previewReference = stringValue(preview.get("previewReference"));
            if (!diffReference.isBlank() && !graphReference.isBlank()) {
                links.add(link("diff", diffReference, "graph", graphReference, "feeds"));
            }
            if (!graphReference.isBlank() && !classificationReference.isBlank()) {
                links.add(link("graph", graphReference, "classification", classificationReference, "supports"));
            }
            if (!classificationReference.isBlank() && !previewReference.isBlank()) {
                links.add(link("classification", classificationReference, "preview", previewReference, "materializes"));
            }
        }
        return links;
    }

    private List<Map<String, Object>> governanceLinks() {
        List<Map<String, Object>> links = new ArrayList<>();
        for (Map<String, Object> item : governanceWorkspaceService.history().containsKey("items")
                ? listOfMaps(governanceWorkspaceService.history().get("items"))
                : List.<Map<String, Object>>of()) {
            String reference = stringValue(item.get("reference"));
            for (String related : strings(item.get("relatedArtifacts"))) {
                if (!reference.isBlank() && !related.isBlank()) {
                    links.add(link("governance", reference, "artifact", related, "linked-artifact"));
                }
            }
        }
        return links;
    }

    private void addCapabilityEdges(List<Map<String, Object>> edges, List<Map<String, Object>> capabilityCards) {
        for (Map<String, Object> capability : capabilityCards) {
            String capabilityName = stringValue(capability.get("capabilityName"));
            for (String flowName : strings(capability.get("runtimeFlows"))) {
                addEdge(edges, "runtime-flow:" + flowName, "capability:" + capabilityName, "runtime-uses");
            }
            for (String flowName : strings(capability.get("draftFlows"))) {
                addEdge(edges, "draft-flow:" + flowName, "capability:" + capabilityName, "draft-uses");
            }
        }
    }

    private void addPreviewEdges(List<Map<String, Object>> edges, Map<String, Object> preview) {
        String previewReference = stringValue(preview.get("previewReference"));
        String classificationReference = stringValue(preview.get("classificationReference"));
        String graphReference = stringValue(preview.get("graphReference"));
        String diffReference = stringValue(preview.get("diffReference"));
        if (!previewReference.isBlank() && !classificationReference.isBlank()) {
            addEdge(edges, "preview:" + previewReference, "preview:" + classificationReference, "classified-by");
        }
        if (!previewReference.isBlank() && !graphReference.isBlank()) {
            addEdge(edges, "preview:" + previewReference, "preview:" + graphReference, "depends-on-graph");
        }
        if (!previewReference.isBlank() && !diffReference.isBlank()) {
            addEdge(edges, "preview:" + previewReference, "preview:" + diffReference, "depends-on-diff");
        }
    }

    private List<Map<String, Object>> runtimeFlows() {
        return kernelFacade.listFlowDefinitions().stream()
                .map(flow -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("flowName", flow.name());
                    item.put("concept", flow.concept());
                    item.put("inputSchema", flow.inputSchema());
                    item.put("outputSchema", flow.outputSchema());
                    return item;
                })
                .toList();
    }

    private List<Map<String, Object>> draftFlows() {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> draft : listOfMaps(flowBuilderService.listDrafts().get("items"))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("flowName", stringValue(draft.get("flowName")));
            item.put("displayName", firstNonBlank(draft.get("displayName"), draft.get("flowName")));
            item.put("builderStatus", stringValue(draft.get("builderStatus")));
            item.put("stepCount", intValue(draft.get("stepCount")));
            item.put("tenantId", stringValue(draft.get("tenantId")));
            items.add(item);
        }
        return items;
    }

    private List<Map<String, Object>> capabilityCards() {
        return listOfMaps(capabilityIntegrationPanelService.catalog().get("capabilities"));
    }

    private List<Map<String, Object>> recentDirectExecutions() {
        return limit(referenceResolver.readRecords(DIRECT_EXECUTION_ROOT), 8);
    }

    private List<Map<String, Object>> recentPreviewRecords() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(limit(referenceResolver.readRecords(GENERATED_PREVIEW_ROOT), 4));
        items.addAll(limit(referenceResolver.readRecords(COMPILER_IMPACT_ROOT), 4));
        items.sort(Comparator.comparing(this::timestamp, Comparator.reverseOrder()));
        return items;
    }

    private List<Map<String, Object>> recentGovernanceRecords() {
        List<Map<String, Object>> items = new ArrayList<>();
        items.addAll(limit(referenceResolver.readRecords(CROSS_TENANT_GOVERNANCE_ROOT), 4));
        items.addAll(limit(referenceResolver.readRecords(WORKSPACE_DECISION_ROOT), 4));
        items.sort(Comparator.comparing(this::timestamp, Comparator.reverseOrder()));
        return items;
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> items, int max) {
        if (items.size() <= max) {
            return items;
        }
        return new ArrayList<>(items.subList(0, max));
    }

    private Map<String, Object> node(String id, String type, String label, Map<String, Object> details) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("label", label);
        node.put("details", details);
        return node;
    }

    private void addNode(List<Map<String, Object>> nodes, Set<String> seenNodes, Map<String, Object> node) {
        String id = stringValue(node.get("id"));
        if (seenNodes.add(id)) {
            nodes.add(node);
        }
    }

    private void addEdge(List<Map<String, Object>> edges, String from, String to, String relation) {
        if (from.isBlank() || to.isBlank()) {
            return;
        }
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("from", from);
        edge.put("to", to);
        edge.put("relation", relation);
        edges.add(edge);
    }

    private Map<String, Object> link(String fromType, String fromRef, String toType, String toRef, String relation) {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("fromType", fromType);
        link.put("fromReference", fromRef);
        link.put("toType", toType);
        link.put("toReference", toRef);
        link.put("relation", relation);
        return link;
    }

    private String governanceStatus(Map<String, Object> governance) {
        return firstNonBlank(
                governance.get("decision"),
                governance.get("status"),
                nestedValue(governance, "governanceSummary", "governanceDecisionStatus")
        );
    }

    private String previewImpactClass(Map<String, Object> preview) {
        return firstNonBlank(preview.get("impactClass"), preview.get("graphWeightedImpactBand"), preview.get("status"));
    }

    private Object nestedValue(Map<String, Object> record, String nestedKey, String field) {
        Object nested = record.get(nestedKey);
        if (nested instanceof Map<?, ?> rawMap) {
            return rawMap.get(field);
        }
        return "";
    }

    private List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> rawMap) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    rawMap.forEach((key, itemValue) -> normalized.put(String.valueOf(key), itemValue));
                    items.add(normalized);
                }
            }
        }
        return items;
    }

    private List<String> strings(Object value) {
        List<String> items = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                String normalized = stringValue(item);
                if (!normalized.isBlank()) {
                    items.add(normalized);
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

    private String timestamp(Map<String, Object> record) {
        return firstNonBlank(
                record.get("requestedAt"),
                record.get("previewedAt"),
                record.get("classifiedAt"),
                record.get("evaluatedAt"),
                record.get("decidedAt")
        );
    }

    private String firstNonBlank(Object... values) {
        for (Object value : values) {
            String normalized = stringValue(value);
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
