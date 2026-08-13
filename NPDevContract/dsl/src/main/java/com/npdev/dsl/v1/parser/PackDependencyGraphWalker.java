package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.MinimalVersionSelector;
import com.npdev.dsl.v1.pack.PackVersion;
import com.npdev.dsl.v1.pack.PackVersionConstraint;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;
import com.npdev.dsl.v1.validation.ValidationSeverity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PK-3: transitive pack dependency resolution -- a pack may itself declare {@code packs[]} (its
 * own dependencies), discovered recursively with packId-keyed cycle detection (a diamond, e.g.
 * {@code app->crm->user}, {@code app->billing->user}, is NOT a cycle -- only a back-edge to a
 * packId already on the CURRENT DFS path is) and DoS caps on both graph depth and total resolved
 * pack count.
 *
 * <p>Qualifier assignment (which namespace prefix a pack's own content is merged under) follows
 * one rule: a packId's qualifier is the app's own direct {@code as} alias iff it is a direct
 * app import; otherwise it is the pack's own real id. This mirrors PK-2's {@code
 * recordPhysicalQualifiers} precedent of deriving identity from a pack's own id, never an alias,
 * and is why a dependency's own hardcoded {@code realPackId::Name} cross-pack reference resolves
 * correctly with zero rewriting in the common (no-alias-collision) case: the dependency is always
 * merged under its own real id unless the app itself also directly, separately aliases it (the
 * {@code allowSideBySide} case, handled separately).
 *
 * <p>Sole call site: {@link ModelSourceResolver#resolvePacks}.
 */
final class PackDependencyGraphWalker {

    /** DoS guard (PK-3 "Breaks"): a pack dependency chain deeper than this is refused by name. */
    static final int MAX_PACK_DEPTH = 8;
    /** DoS guard (PK-3 "Breaks"): a graph resolving more distinct packs than this is refused. */
    static final int MAX_RESOLVED_PACKS = 200;

    private final ModelSourceResolver resolver;
    private final ModelSourceResolver.ResolutionState state;
    private final Path rootDirectory;
    private final String rootDslVersion;

    private final Map<String, ObjectNode> packNodeById = new LinkedHashMap<>();
    private final Map<String, Path> packFileById = new LinkedHashMap<>();
    private final Map<String, Set<String>> dependencyGraph = new LinkedHashMap<>();
    private final Map<String, String> qualifierById = new LinkedHashMap<>();
    private final Map<String, List<MinimalVersionSelector.Requirement>> requirementsByPackId = new LinkedHashMap<>();

    private PackDependencyGraphWalker(ModelSourceResolver resolver, ModelSourceResolver.ResolutionState state, String rootDslVersion) {
        this.resolver = resolver;
        this.state = state;
        this.rootDirectory = state.rootDirectory;
        this.rootDslVersion = rootDslVersion;
    }

    static void resolve(
            ModelSourceResolver resolver,
            ArrayNode packsNode,
            ObjectNode resolved,
            Path modelFile,
            ModelSourceResolver.ResolutionState state
    ) throws IOException {
        String rootDslVersion = ModelSourceResolver.textOrBlank(resolved.get("dslVersion"));
        new PackDependencyGraphWalker(resolver, state, rootDslVersion).run(packsNode, resolved, modelFile);
    }

    private record DirectImport(
            String path, Path packFile, ObjectNode packNode, String realPackId, String qualifier, boolean allowSideBySide, boolean hasAlias) {
    }

    private void run(ArrayNode packsNode, ObjectNode resolved, Path modelFile) throws IOException {
        Set<String> usedNamespaces = new LinkedHashSet<>();
        List<DirectImport> directImports = new ArrayList<>();

        int index = 0;
        for (JsonNode packRefNode : packsNode) {
            String path = "/packs/" + index;
            if (!packRefNode.isObject()) {
                throw ModelSourceResolver.error(modelFile, path, "Pack import must be a JSON object with $ref");
            }
            ObjectNode packRef = (ObjectNode) packRefNode;
            JsonNode refNode = packRef.get("$ref");
            if (refNode == null || !refNode.isTextual() || refNode.asText("").isBlank()) {
                throw ModelSourceResolver.error(modelFile, path + "/$ref", "Pack $ref must be a non-blank string");
            }

            Path packFile = ModelSourceResolver.resolvePackPath(refNode.asText(), modelFile, rootDirectory);
            ObjectNode packNode = loadAndResolvePack(packFile, 1);
            String realPackId = ModelSourceResolver.textOrBlank(packNode.get("pack"));

            String qualifier = ModelSourceResolver.resolvePackNamespace(packRef, packNode, modelFile, packFile, path);
            String namespaceKey = qualifier.toLowerCase(Locale.ROOT);
            if (!usedNamespaces.add(namespaceKey)) {
                throw ModelSourceResolver.error(modelFile, path + "/as", "Duplicate pack namespace alias: " + qualifier);
            }
            boolean allowSideBySide = packRef.has("allowSideBySide") && packRef.get("allowSideBySide").asBoolean(false);
            directImports.add(new DirectImport(path, packFile, packNode, realPackId, qualifier, allowSideBySide, packRef.has("as")));
            index++;
        }

        // Two passes on purpose: every direct import's own file/realPackId is resolved above
        // BEFORE any discovery runs, so the collision check below (an earlier direct import's own
        // transitive walk already having claimed this same real packId under a DIFFERENT file) is
        // decided the same way regardless of how deep that earlier walk went before reaching here.
        for (DirectImport direct : directImports) {
            if (packNodeById.containsKey(direct.realPackId())) {
                Path existingFile = packFileById.get(direct.realPackId());
                if (existingFile.equals(direct.packFile())) {
                    // Same physical file already resolved transitively -- a harmless direct
                    // re-declaration of what would have been pulled in anyway.
                    qualifierById.put(direct.realPackId(), direct.qualifier());
                    continue;
                }
                if (!direct.allowSideBySide()) {
                    throw ModelSourceResolver.error(modelFile, direct.path(), "Pack '" + direct.realPackId()
                            + "' is imported directly here (" + direct.packFile() + ") but a different file for "
                            + "the same pack id is already resolved elsewhere in this model's dependency graph ("
                            + existingFile + ") -- set allowSideBySide:true (with an explicit 'as' alias) on this "
                            + "import to tolerate this, or point both at the same file/version");
                }
                if (!direct.hasAlias()) {
                    throw ModelSourceResolver.error(modelFile, direct.path(), "allowSideBySide requires an "
                            + "explicit 'as' alias so this direct import stays distinguishable from the pack of "
                            + "the same id resolved elsewhere in the graph");
                }
                // A genuinely separate node: discover() is graph-key-agnostic (nothing inside it
                // re-derives its key from packNode's own "pack" field), so the alias can stand in
                // as the key with no further changes -- it can never collide with a REAL packId key
                // since dependency edges (packs[].pack) are always real ids, never aliases.
                discover(direct.qualifier(), direct.packNode(), direct.packFile(), 1, List.of("app", direct.qualifier()));
                qualifierById.put(direct.qualifier(), direct.qualifier());
                state.warnings.add(sideBySideWarning(modelFile, direct, existingFile));
                continue;
            }
            discover(direct.realPackId(), direct.packNode(), direct.packFile(), 1, List.of("app", direct.realPackId()));
            // D6: a direct app import's qualifier always wins, whether this pack was already
            // discovered transitively (via an earlier direct import's own dependency graph) or not.
            qualifierById.put(direct.realPackId(), direct.qualifier());
        }

        detectPackCycle(modelFile);
        selectVersions(modelFile);

        for (String packId : topologicalOrder()) {
            String qualifier = qualifierById.computeIfAbsent(packId, id -> id);
            ObjectNode packNode = packNodeById.get(packId);
            Path packFile = packFileById.get(packId);
            Map<String, Map<String, String>> rewriteMaps = ModelSourceResolver.buildRewriteMaps(qualifier, packNode);
            ModelSourceResolver.mergePackConcepts(qualifier, packNode, resolved, packFile, rewriteMaps);
            ModelSourceResolver.mergePackNonConceptArrays(qualifier, packNode, resolved, packFile, rewriteMaps);
            ModelSourceResolver.recordPhysicalQualifiers(qualifier, packNode, packFile, state);
        }
    }

    private ObjectNode loadAndResolvePack(Path packFile, int depth) throws IOException {
        ObjectNode rawPackNode = ModelSourceResolver.loadPackJson(packFile, state);
        String packDslVersion = ModelSourceResolver.textOrBlank(rawPackNode.get("dslVersion"));
        if (!rootDslVersion.isBlank() && !rootDslVersion.equals(packDslVersion)) {
            throw ModelSourceResolver.error(packFile, "/dslVersion", "Pack DSL version mismatch: root model uses "
                    + rootDslVersion + " but pack at " + packFile + " uses " + packDslVersion
                    + " -- checked transitively, not just for direct imports");
        }
        return resolver.resolvePackRoot(rawPackNode, packFile, state, depth, new ArrayDeque<>());
    }

    /**
     * Default file-discovery convention for a transitive dependency (PK-3: "still local files
     * only", no registry yet): {@code <rootDirectory>/packs/<packId>/pack.json}. Safe to build
     * directly (no root-containment check needed the way {@code resolvePackPath} does for an
     * authored {@code $ref} string) because {@code packId} is already regex-validated
     * ({@code ^[a-z][a-z0-9_-]*$}, both by {@code pack.schema.json}'s own {@code packs[].pack}
     * pattern and by the dependency pack's own {@code pack} field) -- it cannot contain {@code /}
     * or {@code ..} path-traversal segments.
     */
    private Path defaultPackFile(String packId) {
        return rootDirectory.resolve("packs").resolve(packId).resolve("pack.json");
    }

    private void discover(String packId, ObjectNode packNode, Path packFile, int depth, List<String> pathToThisPack) throws IOException {
        if (packNodeById.containsKey(packId)) {
            return; // already discovered via another path -- a diamond, not a cycle by itself
        }
        if (depth > MAX_PACK_DEPTH) {
            throw ModelSourceResolver.error(packFile, "/packs", "Pack dependency graph exceeds the maximum depth ("
                    + MAX_PACK_DEPTH + ") at pack '" + packId + "' -- this is a denial-of-service guard, not a real "
                    + "limitation; flatten the dependency chain if you hit it legitimately");
        }
        packNodeById.put(packId, packNode);
        packFileById.put(packId, packFile);
        if (packNodeById.size() > MAX_RESOLVED_PACKS) {
            throw ModelSourceResolver.error(packFile, "/packs", "Pack dependency graph resolves more than "
                    + MAX_RESOLVED_PACKS + " distinct packs -- this is a denial-of-service guard, not a real limitation");
        }

        Set<String> children = new LinkedHashSet<>();
        JsonNode packsNode = packNode.get("packs");
        if (packsNode != null && packsNode.isArray()) {
            for (JsonNode dependency : packsNode) {
                if (dependency == null || !dependency.isObject()) {
                    continue; // schema already enforces shape; defensive only
                }
                String childPackId = ModelSourceResolver.textOrBlank(dependency.get("pack"));
                String constraintText = ModelSourceResolver.textOrBlank(dependency.get("version"));
                if (childPackId.isBlank()) {
                    continue;
                }
                children.add(childPackId);
                if (!constraintText.isBlank()) {
                    PackVersionConstraint constraint;
                    try {
                        constraint = PackVersionConstraint.parse(constraintText);
                    } catch (IllegalArgumentException malformed) {
                        throw ModelSourceResolver.error(packFile, "/packs", malformed.getMessage());
                    }
                    requirementsByPackId.computeIfAbsent(childPackId, id -> new ArrayList<>())
                            .add(new MinimalVersionSelector.Requirement(packId, pathToThisPack, constraint));
                }
                if (!packNodeById.containsKey(childPackId)) {
                    Path childFile = defaultPackFile(childPackId);
                    ObjectNode childNode = loadAndResolvePack(childFile, depth + 1);
                    List<String> childPath = new ArrayList<>(pathToThisPack);
                    childPath.add(childPackId);
                    discover(childPackId, childNode, childFile, depth + 1, childPath);
                }
            }
        }
        dependencyGraph.put(packId, children);
    }

    /** PK-3 MVS: for every packId at least one pack in the graph placed a constraint on, verify the
     *  one locally-available copy satisfies every such constraint (or refuse, naming every
     *  contributor). packIds reached only as someone's direct app import (no transitive
     *  requirement ever named them) have no requirements to check -- trivially fine. */
    private void selectVersions(Path modelFile) throws IOException {
        for (Map.Entry<String, List<MinimalVersionSelector.Requirement>> entry : requirementsByPackId.entrySet()) {
            String packId = entry.getKey();
            ObjectNode packNode = packNodeById.get(packId);
            if (packNode == null) {
                continue; // depth/fan-out cap already refused before this pack was ever recorded
            }
            PackVersion localVersion = PackVersion.parse(ModelSourceResolver.textOrBlank(packNode.get("version")));
            MinimalVersionSelector.Result result = MinimalVersionSelector.select(packId, entry.getValue(), localVersion);
            if (result instanceof MinimalVersionSelector.Refused refused) {
                throw ModelSourceResolver.error(modelFile, "/packs", refused.message());
            }
        }
    }

    private static ValidationDiagnostic sideBySideWarning(Path modelFile, DirectImport direct, Path existingFile) {
        return ModelSourceResolver.diagnostic(
                ValidationSeverity.WARNING,
                "PACK_SIDE_BY_SIDE_MAJOR_VERSIONS",
                "Pack '" + direct.realPackId() + "' resolves to two different files side by side: the direct "
                        + "import at " + direct.packFile() + " (aliased '" + direct.qualifier() + "') and "
                        + existingFile + " (resolved elsewhere in the graph, physical identity unaffected by the "
                        + "alias) -- confirm this is intentional.",
                modelFile,
                direct.path()
        );
    }

    private void detectPackCycle(Path modelFile) throws IOException {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> onStack = new LinkedHashSet<>();
        ArrayDeque<String> pathStack = new ArrayDeque<>();
        for (String start : dependencyGraph.keySet()) {
            if (!visited.contains(start)) {
                ModelSourceResolver.detectCycleFrom(start, dependencyGraph, visited, onStack, pathStack, cyclePath ->
                        ModelSourceResolver.error(modelFile, "/packs", "Pack dependency cycle detected: "
                                + String.join(" -> ", cyclePath)
                                + " -- a pack cannot (transitively) depend on itself"));
            }
        }
    }

    /** Dependency-first (children before their dependents) -- classic post-order DFS. The graph is
     *  already proven acyclic by {@link #detectPackCycle} before this ever runs. */
    private List<String> topologicalOrder() {
        List<String> order = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String packId : packNodeById.keySet()) {
            topoVisit(packId, visited, order);
        }
        return order;
    }

    private void topoVisit(String packId, Set<String> visited, List<String> order) {
        if (!visited.add(packId)) {
            return;
        }
        for (String child : dependencyGraph.getOrDefault(packId, Set.of())) {
            topoVisit(child, visited, order);
        }
        order.add(packId);
    }
}
