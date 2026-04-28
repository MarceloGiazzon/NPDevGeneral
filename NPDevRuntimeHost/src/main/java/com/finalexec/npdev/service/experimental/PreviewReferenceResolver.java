package com.finalexec.npdev.service.experimental;

import com.finalexec.npdev.service.*;
import com.finalexec.npdev.service.internal.*;

import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PreviewReferenceResolver {

    private static final Path DIFF_ROOT = Paths.get("runtime-data", "compiler-candidate-diffs");
    private static final Path GRAPH_ROOT = Paths.get("runtime-data", "compiler-dependency-graphs");
    private static final Path CLASSIFICATION_ROOT = Paths.get("runtime-data", "compiler-impact-classifications");

    private final PublicationChainReferenceResolver referenceResolver;

    public PreviewReferenceResolver(PublicationChainReferenceResolver referenceResolver) {
        this.referenceResolver = referenceResolver;
    }

    public Map<String, Object> resolvePreviewChain(
            String tenantId,
            String diffReference,
            String graphReference,
            String classificationReference
    ) {
        Map<String, Object> diff = resolveDiff(tenantId, diffReference);
        Map<String, Object> graph = resolveGraph(tenantId, graphReference);
        Map<String, Object> classification = resolveClassification(tenantId, classificationReference);

        int resolvedCount = 0;
        if (diff != null) {
            resolvedCount++;
        }
        if (graph != null) {
            resolvedCount++;
        }
        if (classification != null) {
            resolvedCount++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("diffReference", normalize(diffReference));
        result.put("graphReference", normalize(graphReference));
        result.put("classificationReference", normalize(classificationReference));
        result.put("resolvedDiffId", diff == null ? "" : referenceResolver.extractFirstString(diff, "compilerCandidateDiffId"));
        result.put("resolvedGraphId", graph == null ? "" : referenceResolver.extractFirstString(graph, "compilerDependencyGraphId"));
        result.put("resolvedClassificationId", classification == null ? "" : referenceResolver.extractFirstString(classification, "compilerImpactClassificationId"));
        result.put("diffReferenceStatus", diff == null ? "UNRESOLVED" : "RESOLVED");
        result.put("graphReferenceStatus", graph == null ? "UNRESOLVED" : "RESOLVED");
        result.put("classificationReferenceStatus", classification == null ? "UNRESOLVED" : "RESOLVED");
        result.put("resolvedReferenceCount", resolvedCount);
        result.put("referenceIntegrityStatus", determineIntegrityStatus(resolvedCount));
        result.put("resolvedArtifactFamilies", inferArtifactFamilies(diff, graph, classification));
        return result;
    }

    public Map<String, Object> resolveDiffGraphAndClassification(
            String tenantId,
            String diffReference,
            String graphReference,
            String classificationReference
    ) {
        return resolvePreviewChain(tenantId, diffReference, graphReference, classificationReference);
    }

    public Map<String, Object> resolveDiffRecord(String tenantId, String diffReference) {
        return resolveDiff(tenantId, diffReference);
    }

    public Map<String, Object> resolveGraphRecord(String tenantId, String graphReference) {
        return resolveGraph(tenantId, graphReference);
    }

    public Map<String, Object> resolveClassificationRecord(String tenantId, String classificationReference) {
        return resolveClassification(tenantId, classificationReference);
    }

    private Map<String, Object> resolveDiff(String tenantId, String diffReference) {
        if (isBlank(diffReference)) {
            return null;
        }
        return referenceResolver.resolveSingle(
                DIFF_ROOT,
                tenantId,
                diffReference.trim(),
                "diffReference",
                "compilerCandidateDiffId"
        );
    }

    private Map<String, Object> resolveGraph(String tenantId, String graphReference) {
        if (isBlank(graphReference)) {
            return null;
        }
        return referenceResolver.resolveSingle(
                GRAPH_ROOT,
                tenantId,
                graphReference.trim(),
                "graphReference",
                "compilerDependencyGraphId"
        );
    }

    private Map<String, Object> resolveClassification(String tenantId, String classificationReference) {
        if (isBlank(classificationReference)) {
            return null;
        }
        return referenceResolver.resolveSingle(
                CLASSIFICATION_ROOT,
                tenantId,
                classificationReference.trim(),
                "classificationReference",
                "compilerImpactClassificationId"
        );
    }

    private List<String> inferArtifactFamilies(
            Map<String, Object> diff,
            Map<String, Object> graph,
            Map<String, Object> classification
    ) {
        java.util.LinkedHashSet<String> families = new java.util.LinkedHashSet<>();

        if (diff != null) {
            for (String unitKind : referenceResolver.extractStringList(diff, "changeKinds")) {
                if ("structural".equalsIgnoreCase(unitKind)) {
                    families.add("GENERATED_DOMAIN_LAYER");
                }
            }
            Object rawUnits = diff.get("changeUnits");
            if (rawUnits instanceof List<?> units) {
                for (Object item : units) {
                    if (item instanceof Map<?, ?> rawMap) {
                        String unitKind = normalize(((Map<?, ?>) rawMap).get("unitKind"));
                        if ("structural".equalsIgnoreCase(unitKind)) {
                            families.add("GENERATED_DOMAIN_LAYER");
                        }
                        if ("semantic".equalsIgnoreCase(unitKind)) {
                            families.add("GENERATED_RUNTIME_BEHAVIOR");
                        }
                        if ("draft-context".equalsIgnoreCase(unitKind)) {
                            families.add("WORKING_DRAFT_WORKFLOW");
                        }
                    }
                }
            }
        }

        if (graph != null) {
            List<String> categories = referenceResolver.extractStringList(graph, "dependencyCategories");
            if (categories.contains("GENERATED_ARTIFACT_DEPENDENCY")) {
                families.add("GENERATED_ARTIFACTS");
            }
            if (categories.contains("PUBLICATION_DEPENDENCY")) {
                families.add("PUBLICATION_PIPELINE");
            }
            if (categories.contains("EXECUTION_DEPENDENCY")) {
                families.add("RUNTIME_EXECUTION");
            }
        }

        if (classification != null) {
            String impactClass = referenceResolver.extractFirstString(classification, "impactClass");
            if ("CROSS_DOMAIN_IMPACT".equals(impactClass) || "REGENERATION_HEAVY_IMPACT".equals(impactClass)) {
                families.add("USER_VISIBLE_SURFACES");
            }
        }

        return List.copyOf(families);
    }

    private String determineIntegrityStatus(int resolvedCount) {
        if (resolvedCount == 3) {
            return "FULLY_LINKED";
        }
        if (resolvedCount >= 1) {
            return "PARTIALLY_LINKED";
        }
        return "UNRESOLVED";
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
