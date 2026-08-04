package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;
import com.npdev.dsl.v1.validation.ValidationLayer;
import com.npdev.dsl.v1.validation.ValidationSeverity;
import com.npdev.dsl.v1.validation.JsonSchemaResourceValidator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Resolves NPDev-local model includes. This is not JSON Schema remote $ref support.
 */
public final class ModelSourceResolver {
    public static final int DEFAULT_MAX_INCLUDE_DEPTH = 32;
    public static final int DEFAULT_MAX_INCLUDED_FILES = 512;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonSchemaResourceValidator PACK_SCHEMA_VALIDATOR =
            new JsonSchemaResourceValidator("/schema/pack.schema.json");
    // Insertion-ordered on purpose: resolveRoot iterates these to build the emitted model.json's
    // key order, and Java's immutable Set.of(...) has a per-JVM-randomized iteration order, so two
    // generation runs (two JVMs) would otherwise reorder the resolved model.json's keys and break
    // the deterministic-generation gate. LinkedHashSet pins the order; containment checks are
    // unaffected.
    private static final Set<String> MODEL_ARRAY_KEYS = orderedSet(
            "concepts",
            "domainTypes",
            "capabilities",
            "customCapabilities",
            "bindings",
            "events",
            "flows",
            "orchestrationRules",
            "orchestrations",
            "queries",
            "ruleProfiles",
            "procedures",
            "conversions",
            "panels",
            "guidePages",
            // Move 13 (REG-108): roles/propertyScopes/properties are top-level arrays exactly like
            // the ones above, but were never added here when they shipped (RC-A1/RC-B1) -- a pack
            // or fragment declaring any of the three had its declaration silently dropped by
            // mergePackNonConceptArrays/appendFragment, which only ever loop this set. Root-level
            // declarations were unaffected (resolveRoot's own unrecognized-key passthrough, below,
            // covers the root only). X0-shaped: an author authoring a pack-level property gets no
            // error, just a property that silently never resolves.
            "roles",
            "propertyScopes",
            "properties"
    );
    private static final Set<String> ROOT_SCALAR_KEYS = orderedSet(
            "$schema",
            "schemaVersion",
            "dslVersion",
            "namespace",
            "model",
            "version"
    );
    private static final Set<String> FRAGMENT_KEYS;
    private static final Set<String> PACK_ROOT_SCALAR_KEYS = Set.of(
            "$schema",
            "dslVersion",
            "pack",
            "namespace",
            "version",
            "description"
    );
    private static final Set<String> PACK_FRAGMENT_FORBIDDEN_KEYS = Set.of(
            "$schema",
            "dslVersion",
            "pack",
            "namespace",
            "version",
            "description"
    );
    private static final Pattern PACK_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_-]*$");

    static {
        Set<String> keys = new LinkedHashSet<>(MODEL_ARRAY_KEYS);
        keys.add("metadata");
        keys.add("fragments");
        FRAGMENT_KEYS = Set.copyOf(keys);
    }

    /** S5 (element-granularity authoring merge, {@code AuthoringMergeGate}): the single source of
     *  truth for "which top-level keys are named-element arrays" -- reused as-is rather than
     *  hand-maintaining a second copy of this list (the exact twin-pair defect class this repo
     *  already tracks mechanically). Order matters to callers that build deterministic output. */
    public static Set<String> modelArrayKeys() {
        return MODEL_ARRAY_KEYS;
    }

    private final int maxIncludeDepth;
    private final int maxIncludedFiles;

    /** Deterministic-iteration immutable set: preserves argument order, unlike {@link Set#of}. */
    private static Set<String> orderedSet(String... keys) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(keys)));
    }

    public ModelSourceResolver() {
        this(DEFAULT_MAX_INCLUDE_DEPTH, DEFAULT_MAX_INCLUDED_FILES);
    }

    public ModelSourceResolver(int maxIncludeDepth, int maxIncludedFiles) {
        this.maxIncludeDepth = maxIncludeDepth;
        this.maxIncludedFiles = maxIncludedFiles;
    }

    public ResolvedModelSource resolve(Path modelJsonPath) throws IOException {
        try {
            if (modelJsonPath == null) {
                throw new IOException("model.json path is required");
            }
            Path rootRealPath = modelJsonPath.toAbsolutePath().normalize().toRealPath();
            if (!Files.isRegularFile(rootRealPath)) {
                throw new IOException("model.json not found: " + modelJsonPath);
            }
            Path rootDirectory = rootRealPath.getParent();
            if (rootDirectory == null) {
                throw new IOException("model.json must have a parent directory: " + rootRealPath);
            }

            ResolutionState state = new ResolutionState(rootRealPath, rootDirectory);
            JsonNode root = readJson(rootRealPath);
            if (!root.isObject()) {
                throw error(rootRealPath, "$", "Root model must be a JSON object");
            }

            validateRootAuthoringObject((ObjectNode) root, rootRealPath);
            ObjectNode resolved = resolveRoot((ObjectNode) root, rootRealPath, state);
            addProvenance(state.provenance, resolved, "", rootRealPath);

            return new ResolvedModelSource(
                    rootRealPath,
                    rootDirectory,
                    resolved,
                    new ArrayList<>(state.includedFiles),
                    state.provenance,
                    state.diagnostics,
                    state.warnings
            );
        } catch (UncheckedModelSourceException exception) {
            throw exception.getCause();
        }
    }

    private ObjectNode resolveRoot(ObjectNode root, Path sourceFile, ResolutionState state) throws IOException {
        ObjectNode resolved = JsonNodeFactory.instance.objectNode();
        for (String key : ROOT_SCALAR_KEYS) {
            if (root.has(key)) {
                resolved.set(key, root.get(key).deepCopy());
            }
        }

        ObjectNode metadata = JsonNodeFactory.instance.objectNode();
        Set<String> rootMetadataKeys = new LinkedHashSet<>();
        JsonNode rootMetadata = root.get("metadata");
        if (rootMetadata != null) {
            if (!rootMetadata.isObject()) {
                throw error(sourceFile, "/metadata", "metadata must be an object");
            }
            rootMetadata.fieldNames().forEachRemaining(rootMetadataKeys::add);
        }
        Map<String, Path> fragmentMetadataOwners = new LinkedHashMap<>();

        for (String key : MODEL_ARRAY_KEYS) {
            JsonNode array = root.get(key);
            if (array != null) {
                resolved.set(key, resolveArray(key, array, sourceFile, state, 0, new ArrayDeque<>()));
            }
        }

        JsonNode fragments = root.get("fragments");
        if (fragments != null) {
            if (!fragments.isArray()) {
                throw error(sourceFile, "/fragments", "fragments must be an array of local $ref objects");
            }
            int index = 0;
            for (JsonNode fragmentRef : fragments) {
                Ref ref = parseRefObject(fragmentRef, sourceFile, "/fragments/" + index);
                ObjectNode fragment = resolveFragment(ref, sourceFile, state, 1, new ArrayDeque<>());
                appendFragment(resolved, metadata, rootMetadataKeys, fragmentMetadataOwners, fragment, state);
                index++;
            }
        }

        JsonNode packs = root.get("packs");
        if (packs != null) {
            if (!packs.isArray()) {
                throw error(sourceFile, "/packs", "packs must be an array of pack import objects");
            }
            resolvePacks((ArrayNode) packs, resolved, sourceFile, state);
        }

        JsonNode contexts = root.get("contexts");
        if (contexts != null) {
            if (!contexts.isArray()) {
                throw error(sourceFile, "/contexts", "contexts must be an array of {name, $ref} objects");
            }
            resolveContexts((ArrayNode) contexts, resolved, sourceFile, state);
        }

        if (!metadata.isEmpty()) {
            resolved.set("metadata", metadata);
        }
        if (rootMetadata != null && rootMetadata.isObject()) {
            ObjectNode effective = resolved.has("metadata") && resolved.get("metadata").isObject()
                    ? (ObjectNode) resolved.get("metadata")
                    : JsonNodeFactory.instance.objectNode();
            rootMetadata.fields().forEachRemaining(entry -> {
                if (effective.has(entry.getKey())) {
                    state.warnings.add(diagnostic(
                            ValidationSeverity.WARNING,
                            "MODEL_INCLUDE_METADATA_ROOT_OVERRIDES_FRAGMENT",
                            "Root metadata key '" + entry.getKey() + "' overrides fragment metadata.",
                            sourceFile,
                            "/metadata/" + escapePointer(entry.getKey())
                    ));
                }
                effective.set(entry.getKey(), entry.getValue().deepCopy());
            });
            resolved.set("metadata", effective);
        }

        // Pass through any unrecognized top-level keys verbatim so downstream JSON Schema
        // validation is the single authority that rejects them (see validateRootAuthoringObject).
        root.fields().forEachRemaining(entry -> {
            if (!isRecognizedRootKey(entry.getKey()) && !resolved.has(entry.getKey())) {
                resolved.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        return resolved;
    }

    private void appendFragment(
            ObjectNode resolved,
            ObjectNode metadata,
            Set<String> rootMetadataKeys,
            Map<String, Path> fragmentMetadataOwners,
            ObjectNode fragment,
            ResolutionState state
    ) throws IOException {
        for (String key : MODEL_ARRAY_KEYS) {
            JsonNode value = fragment.get(key);
            if (value == null) {
                continue;
            }
            if (!value.isArray()) {
                throw new IOException("Fragment key '" + key + "' must be an array");
            }
            ArrayNode target = resolved.has(key) && resolved.get(key).isArray()
                    ? (ArrayNode) resolved.get(key)
                    : JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : value) {
                target.add(item.deepCopy());
            }
            resolved.set(key, target);
        }
        JsonNode fragmentMetadata = fragment.get("metadata");
        if (fragmentMetadata != null) {
            if (!fragmentMetadata.isObject()) {
                throw new IOException("Fragment metadata must be an object");
            }
            fragmentMetadata.fields().forEachRemaining(entry -> {
                String key = entry.getKey();
                Path source = state.provenance.getOrDefault(pointerOf(fragmentMetadata), state.rootRealPath);
                if (fragmentMetadataOwners.containsKey(key)) {
                    throwUnchecked(new IOException("Duplicate fragment metadata key '" + key + "' from "
                            + source + " and " + fragmentMetadataOwners.get(key)));
                }
                fragmentMetadataOwners.put(key, source);
                if (!rootMetadataKeys.contains(key)) {
                    metadata.set(key, entry.getValue().deepCopy());
                }
            });
        }
    }

    /**
     * B20 (S2, {@code __OutsideRepo\s1\b20-design.md} D1-D4 + D8, owner-accepted 2026-08-03): composes
     * each declared bounded context's fragment file -- reusing the pack machinery end to end
     * ({@link #loadPackJson} for schema validation, {@link #resolvePackRoot} for nested-fragment/$ref
     * resolution, {@link #mergeQualifiedConcepts}/{@link #mergeQualifiedNonConceptArrays} for
     * {@code contextName::Member} qualification) rather than a second composition mechanism (D2).
     *
     * <p>Two passes: (1) load every declared context's raw fragment + its own {@code imports[]},
     * validate every import names an ALSO-declared context (D3) and that the import graph is acyclic
     * (D8); (2) resolve each context's full content and merge it in, gate-checking every reference
     * that already carries a {@code ::} qualifier (a self-reference, a declared import, or -- since
     * {@code ::} is also how pack-qualified references already work, unrestricted -- anything that
     * isn't a known context name at all) against that context's own {@code imports[]}. An undeclared
     * cross-context reference is a named, thrown error -- never a silent resolve-to-nothing (X0).
     */
    private void resolveContexts(
            ArrayNode contextsNode,
            ObjectNode resolved,
            Path modelFile,
            ResolutionState state
    ) throws IOException {
        List<String> orderedNames = new ArrayList<>();
        Map<String, Path> contextFiles = new LinkedHashMap<>();
        Map<String, ObjectNode> rawContextNodes = new LinkedHashMap<>();
        Map<String, Set<String>> importGraph = new LinkedHashMap<>();
        Map<String, String> contextRefByName = new LinkedHashMap<>();
        // S8 Wave 4 (ADR-0011 D4's v2 escape): the context DECLARATION's own physicallyIsolate flag
        // -- distinct from anything in the fragment file itself -- must survive this method's
        // rebuild of the contexts[] registry below, or a context declaring it silently loses the
        // flag the moment a model composes it (the REG-108 shape CLAUDE.md's four-place rule exists
        // to prevent).
        Map<String, Boolean> contextPhysicallyIsolateByName = new LinkedHashMap<>();

        int index = 0;
        for (JsonNode contextRefNode : contextsNode) {
            String path = "/contexts/" + index;
            if (!contextRefNode.isObject()) {
                throw error(modelFile, path, "Context declaration must be a JSON object with name and $ref");
            }
            ObjectNode contextRef = (ObjectNode) contextRefNode;
            JsonNode nameNode = contextRef.get("name");
            if (nameNode == null || !nameNode.isTextual() || nameNode.asText("").isBlank()) {
                throw error(modelFile, path + "/name", "Context 'name' must be a non-blank string");
            }
            String name = nameNode.asText().trim();
            JsonNode refNode = contextRef.get("$ref");
            if (refNode == null || !refNode.isTextual() || refNode.asText("").isBlank()) {
                throw error(modelFile, path + "/$ref", "Context '$ref' must be a non-blank string");
            }
            if (contextFiles.containsKey(name)) {
                throw error(modelFile, path + "/name", "Duplicate context name: " + name);
            }

            Path contextFile = resolveContextPath(refNode.asText(), modelFile, state.rootDirectory);
            ObjectNode rawContextNode = loadPackJson(contextFile, state);

            Set<String> imports = new LinkedHashSet<>();
            JsonNode importsNode = rawContextNode.get("imports");
            if (importsNode != null) {
                if (!importsNode.isArray()) {
                    throw error(contextFile, "/imports", "imports must be an array of context name strings");
                }
                for (JsonNode importNode : importsNode) {
                    if (!importNode.isTextual() || importNode.asText("").isBlank()) {
                        throw error(contextFile, "/imports", "Each import must be a non-blank string");
                    }
                    imports.add(importNode.asText().trim());
                }
            }

            orderedNames.add(name);
            contextFiles.put(name, contextFile);
            rawContextNodes.put(name, rawContextNode);
            importGraph.put(name, imports);
            contextRefByName.put(name, refNode.asText());
            JsonNode physicallyIsolateNode = contextRef.get("physicallyIsolate");
            contextPhysicallyIsolateByName.put(
                    name, physicallyIsolateNode != null && physicallyIsolateNode.asBoolean(false));
            index++;
        }

        // D3: every import must name a DECLARED context -- an import of an undeclared context is
        // caught at declaration time rather than waiting for first use.
        for (Map.Entry<String, Set<String>> entry : importGraph.entrySet()) {
            for (String imported : entry.getValue()) {
                if (imported.equals(entry.getKey())) {
                    throw error(contextFiles.get(entry.getKey()), "/imports", "Context '" + entry.getKey()
                            + "' imports itself -- imports[] is for OTHER contexts only");
                }
                if (!importGraph.containsKey(imported)) {
                    throw error(contextFiles.get(entry.getKey()), "/imports", "Context '" + entry.getKey()
                            + "' imports undeclared context '" + imported + "' -- every entry in imports[] "
                            + "must name a context also declared in this model's own contexts[] array");
                }
            }
        }

        // D8 (owner-accepted 2026-08-03): reject import cycles -- a cycle means the boundary is not
        // a boundary.
        detectImportCycle(importGraph, modelFile);

        for (String name : orderedNames) {
            Path contextFile = contextFiles.get(name);
            ObjectNode rawContextNode = rawContextNodes.get(name);
            ObjectNode contextContent = resolvePackRoot(rawContextNode, contextFile, state, 1, new ArrayDeque<>());
            Set<String> allowedImports = importGraph.get(name);

            Map<String, String> conceptRewriteMap = packConceptRewriteMap(name, contextContent);
            mergeQualifiedConcepts("Context", name, contextContent, resolved, contextFile, conceptRewriteMap);

            QualifiedReferenceValidator gate = qualifiedName -> {
                String prefix = qualifiedName.substring(0, qualifiedName.indexOf("::"));
                if (prefix.equals(name) || allowedImports.contains(prefix) || !importGraph.containsKey(prefix)) {
                    // Self-reference, a declared import, or not a known context at all (assumed to be
                    // a pack reference -- packs stay unrestricted, unchanged behavior) -- all fine.
                    return;
                }
                throwUnchecked(new IOException("Context '" + name + "' references '" + qualifiedName
                        + "', which belongs to context '" + prefix + "' -- '" + name + "' does not declare '"
                        + prefix + "' in its own imports[] (D3: an undeclared cross-context reference is a "
                        + "compile error, never silent)"));
            };
            mergeQualifiedNonConceptArrays("Context", name, contextContent, resolved, contextFile, conceptRewriteMap, gate);
        }

        // Preserve the {name, $ref} registry itself for introspection (JsonModelParser reads it back
        // into ContextAst) -- the fragment CONTENT above is already fully composed into
        // concepts/queries/panels/flows.
        ArrayNode contextsOut = JsonNodeFactory.instance.arrayNode();
        for (String name : orderedNames) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("name", name);
            node.put("$ref", contextRefByName.get(name));
            // Only emitted when true -- a model that never declares physicallyIsolate anywhere must
            // produce byte-identical resolved JSON to before Wave 4 (I4's own regression DoD).
            if (Boolean.TRUE.equals(contextPhysicallyIsolateByName.get(name))) {
                node.put("physicallyIsolate", true);
            }
            contextsOut.add(node);
        }
        resolved.set("contexts", contextsOut);
    }

    private static Path resolveContextPath(String ref, Path modelFile, Path rootDirectory) throws IOException {
        return resolveJsonRefUnderRoot(ref, modelFile, rootDirectory, rootDirectory, "Context $ref");
    }

    /** D8: DFS cycle detection over the import graph, iterative-safe recursion depth aside (bounded
     *  by the number of declared contexts, never attacker-controlled recursion). Reports the actual
     *  cycle path, not just "a cycle exists somewhere". */
    private static void detectImportCycle(Map<String, Set<String>> importGraph, Path modelFile) throws IOException {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> onStack = new LinkedHashSet<>();
        Deque<String> pathStack = new ArrayDeque<>();
        for (String start : importGraph.keySet()) {
            if (!visited.contains(start)) {
                detectImportCycleFrom(start, importGraph, visited, onStack, pathStack, modelFile);
            }
        }
    }

    private static void detectImportCycleFrom(
            String node,
            Map<String, Set<String>> importGraph,
            Set<String> visited,
            Set<String> onStack,
            Deque<String> pathStack,
            Path modelFile
    ) throws IOException {
        visited.add(node);
        onStack.add(node);
        pathStack.push(node);
        for (String next : importGraph.getOrDefault(node, Set.of())) {
            if (onStack.contains(next)) {
                List<String> cyclePath = new ArrayList<>(pathStack);
                Collections.reverse(cyclePath);
                int startIndex = cyclePath.indexOf(next);
                String description = String.join(" -> ", cyclePath.subList(startIndex, cyclePath.size())) + " -> " + next;
                throw error(modelFile, "/contexts", "Import cycle detected: " + description
                        + " -- a context's imports[] graph must be acyclic (a cycle means the boundary "
                        + "is not a boundary)");
            }
            if (!visited.contains(next)) {
                detectImportCycleFrom(next, importGraph, visited, onStack, pathStack, modelFile);
            }
        }
        pathStack.pop();
        onStack.remove(node);
    }

    private void resolvePacks(
            ArrayNode packsNode,
            ObjectNode resolved,
            Path modelFile,
            ResolutionState state
    ) throws IOException {
        String rootDslVersion = textOrBlank(resolved.get("dslVersion"));
        Set<String> usedNamespaces = new LinkedHashSet<>();
        int index = 0;
        for (JsonNode packRefNode : packsNode) {
            String path = "/packs/" + index;
            if (!packRefNode.isObject()) {
                throw error(modelFile, path, "Pack import must be a JSON object with $ref");
            }
            ObjectNode packRef = (ObjectNode) packRefNode;
            JsonNode refNode = packRef.get("$ref");
            if (refNode == null || !refNode.isTextual() || refNode.asText("").isBlank()) {
                throw error(modelFile, path + "/$ref", "Pack $ref must be a non-blank string");
            }

            Path packFile = resolvePackPath(refNode.asText(), modelFile, state.rootDirectory);
            ObjectNode rawPackNode = loadPackJson(packFile, state);
            String packDslVersion = textOrBlank(rawPackNode.get("dslVersion"));
            if (!rootDslVersion.isBlank() && !rootDslVersion.equals(packDslVersion)) {
                throw error(packFile, "/dslVersion", "Pack DSL version mismatch for " + refNode.asText()
                        + ": root model uses " + rootDslVersion + " but pack uses " + packDslVersion);
            }
            ObjectNode packNode = resolvePackRoot(rawPackNode, packFile, state, 1, new ArrayDeque<>());

            String packId = resolvePackNamespace(packRef, packNode, modelFile, packFile, path);
            String namespaceKey = packId.toLowerCase(Locale.ROOT);
            if (!usedNamespaces.add(namespaceKey)) {
                throw error(modelFile, path + "/as", "Duplicate pack namespace alias: " + packId);
            }
            Map<String, String> conceptRewriteMap = packConceptRewriteMap(packId, packNode);
            mergePackConcepts(packId, packNode, resolved, packFile, conceptRewriteMap);
            mergePackNonConceptArrays(packId, packNode, resolved, packFile, conceptRewriteMap);
            index++;
        }
    }

    private static String resolvePackNamespace(
            ObjectNode packRef,
            ObjectNode packNode,
            Path modelFile,
            Path packFile,
            String path
    ) throws IOException {
        JsonNode packIdNode = packNode.get("pack");
        if (packIdNode == null || !packIdNode.isTextual() || packIdNode.asText("").isBlank()) {
            throw error(packFile, "/pack", "Pack file must declare a non-blank string 'pack' identifier");
        }
        String packId = packIdNode.asText().trim();
        validatePackIdentifier(packId, packFile, "/pack", "Pack identifier");

        if (!packRef.has("as")) {
            return packId;
        }
        JsonNode aliasNode = packRef.get("as");
        if (!aliasNode.isTextual()) {
            throw error(modelFile, path + "/as", "Pack alias 'as' must be a string");
        }
        String alias = aliasNode.asText("").trim();
        if (alias.isBlank()) {
            throw error(modelFile, path + "/as", "Pack alias 'as' must be non-blank");
        }
        validatePackIdentifier(alias, modelFile, path + "/as", "Pack alias");
        return alias;
    }

    private static void validatePackIdentifier(String value, Path sourceFile, String path, String label) throws IOException {
        if (!PACK_ID_PATTERN.matcher(value).matches()) {
            throw error(sourceFile, path, label + " must match " + PACK_ID_PATTERN.pattern() + ": " + value);
        }
    }

    private static Map<String, String> packConceptRewriteMap(String packId, ObjectNode packNode) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode conceptsNode = packNode.get("concepts");
        if (conceptsNode == null || !conceptsNode.isArray()) {
            return out;
        }
        for (JsonNode concept : conceptsNode) {
            if (concept != null && concept.isObject() && concept.has("name") && concept.get("name").isTextual()) {
                String name = concept.get("name").asText();
                out.put(name, packId + "::" + name);
            }
        }
        return out;
    }

    /**
     * Appends a pack's non-concept model arrays (domainTypes, capabilities, events, flows, …)
     * into the resolved model. Only concepts are namespaced ({@code packId::Name}); other members
     * keep their authored names, and any resulting name collisions surface downstream as the
     * normal duplicate-member error. Without this, pack-defined domain types/capabilities that a
     * pack's concepts depend on would be silently dropped.
     */
    private static void mergePackNonConceptArrays(
            String packId,
            ObjectNode packNode,
            ObjectNode resolved,
            Path packFile,
            Map<String, String> conceptRewriteMap
    )
            throws IOException {
        mergeQualifiedNonConceptArrays("Pack", packId, packNode, resolved, packFile, conceptRewriteMap, QUALIFIED_REF_NOOP);
    }

    /** B20 (S2): generalized over {@link #mergePackNonConceptArrays} -- identical field walk and
     *  local-reference rewriting; {@code kindLabel} only changes the duplicate-member error message,
     *  and {@code qualifiedReferenceValidator} is the D3 import-gate hook (a no-op for packs,
     *  unchanged behavior; the real check for contexts, see {@link #mergeContextNonConceptArrays}). */
    private static void mergeQualifiedNonConceptArrays(
            String kindLabel,
            String qualifierId,
            ObjectNode sourceNode,
            ObjectNode resolved,
            Path sourceFile,
            Map<String, String> conceptRewriteMap,
            QualifiedReferenceValidator qualifiedReferenceValidator
    )
            throws IOException {
        for (String key : MODEL_ARRAY_KEYS) {
            if ("concepts".equals(key)) {
                continue;
            }
            JsonNode array = sourceNode.get(key);
            if (array == null || !array.isArray()) {
                continue;
            }
            ArrayNode target = resolved.has(key) && resolved.get(key).isArray()
                    ? (ArrayNode) resolved.get(key)
                    : JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : array) {
                if (item != null && item.isObject() && item.has("name") && item.get("name").isTextual()) {
                    String name = item.get("name").asText();
                    for (JsonNode existing : target) {
                        if (existing != null
                                && existing.isObject()
                                && existing.has("name")
                                && existing.get("name").isTextual()
                                && name.equalsIgnoreCase(existing.get("name").asText())) {
                            throw error(sourceFile, "/" + key,
                                    kindLabel + " '" + qualifierId + "' contributes duplicate " + key + " member '" + name + "'");
                        }
                    }
                }
                JsonNode rewritten = item.deepCopy();
                rewritePackLocalConceptReferencesInPlace(rewritten, conceptRewriteMap, key, qualifiedReferenceValidator);
                target.add(rewritten);
            }
            resolved.set(key, target);
        }
    }

    private static void rewritePackLocalConceptReferencesInPlace(
            JsonNode node,
            Map<String, String> conceptRewriteMap,
            String rootKey
    ) {
        if (node == null || conceptRewriteMap.isEmpty()) {
            return;
        }
        rewritePackLocalConceptReferencesInPlace(node, conceptRewriteMap, rootKey, QUALIFIED_REF_NOOP);
    }

    /** B20 (S2): same field walk, plus a hook invoked for every reference already qualified
     *  ({@code prefix::Name}) rather than rewritten -- packs pass a no-op (unrestricted cross-pack
     *  reference, existing behavior, unchanged); {@code mergeContextNonConceptArrays} passes D3's
     *  import-gate check. Unlike the pack-only overload above, this one does NOT short-circuit on an
     *  empty {@code conceptRewriteMap} -- a context with zero local concepts must still have its
     *  OTHER-context-qualified references gate-checked. */
    private static void rewritePackLocalConceptReferencesInPlace(
            JsonNode node,
            Map<String, String> conceptRewriteMap,
            String rootKey,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        if (node == null) {
            return;
        }
        rewritePackLocalConceptReferencesInPlace(node, conceptRewriteMap, rootKey, "", qualifiedReferenceValidator);
    }

    private static void rewritePackLocalConceptReferencesInPlace(
            JsonNode node,
            Map<String, String> conceptRewriteMap,
            String rootKey,
            String parentKey,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            rewriteKnownConceptFields(object, conceptRewriteMap, rootKey, parentKey, qualifiedReferenceValidator);
            List<String> fieldNames = new ArrayList<>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                rewritePackLocalConceptReferencesInPlace(
                        object.get(fieldName), conceptRewriteMap, rootKey, fieldName, qualifiedReferenceValidator);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                rewritePackLocalConceptReferencesInPlace(
                        item, conceptRewriteMap, rootKey, parentKey, qualifiedReferenceValidator);
            }
        }
    }

    private static void rewriteKnownConceptFields(
            ObjectNode object,
            Map<String, String> conceptRewriteMap,
            String rootKey,
            String parentKey,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        rewriteTextField(object, "conceptRef", conceptRewriteMap, qualifiedReferenceValidator);
        if ("queries".equals(rootKey)) {
            rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
            gateCheckGroupByJoinPaths(object, qualifiedReferenceValidator);
        } else if ("flows".equals(rootKey)) {
            if (parentKey.isBlank() || "input".equals(parentKey)) {
                rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
            }
            // S3 (found via the bounded-contexts codemod trial, pack-sample): invariantCheck /
            // createConcept / updateConcept flow steps all name their target concept via `scope`
            // (FlowValidation.collectConceptMutationScopes reads the same field for all three) --
            // this was never in the rewrite table, so a context-qualified concept's own flow steps
            // stayed unqualified and validation then rejected them for a scope/concept mismatch that
            // qualification itself caused. `scope` only has this meaning on a flowStep object, so
            // rewriting it unconditionally here (not gated to a parentKey, unlike `concept` above,
            // since a step can be nested arbitrarily deep under then/else/steps/onFailure) is safe.
            rewriteTextField(object, "scope", conceptRewriteMap, qualifiedReferenceValidator);
        } else if ("procedures".equals(rootKey)) {
            rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
            if ("actionDescriptor".equals(parentKey)) {
                rewriteTextField(object, "sideEffectConcept", conceptRewriteMap, qualifiedReferenceValidator);
                rewriteTextArrayField(object, "affectedConcepts", conceptRewriteMap, qualifiedReferenceValidator);
            }
        } else if ("panels".equals(rootKey)) {
            if ("dataSources".equals(parentKey) || "actions".equals(parentKey)) {
                rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
            }
        } else if ("orchestrations".equals(rootKey) || "orchestrationRules".equals(rootKey)) {
            if ("action".equals(parentKey) || "actions".equals(parentKey)) {
                rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
                rewriteTextField(object, "targetConcept", conceptRewriteMap, qualifiedReferenceValidator);
            }
        } else if ("ruleProfiles".equals(rootKey)) {
            rewriteTextOrArrayField(object, "appliesTo", conceptRewriteMap, qualifiedReferenceValidator);
        } else if ("events".equals(rootKey)) {
            rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
            rewriteTextField(object, "conceptName", conceptRewriteMap, qualifiedReferenceValidator);
        }
    }

    /** S4 (ADR-0011 D3 extended to groupBy, docs/adr/ADR-0011-bounded-contexts.md's own "No groupBy
     *  join syntax (S4)" non-goal, now built): a {@code groupBy[]} entry may itself EMBED a
     *  qualified reference ({@code "inventory::lote.produtoId"} -- the context prefix names which
     *  context the JOINED concept belongs to, not this whole dotted path). {@code rewriteConceptName}'s
     *  own {@code ::} branch only gate-checks and never rewrites, so this reuses it directly for its
     *  gate-check side effect rather than duplicating D3's import logic -- see
     *  {@code com.npdev.dsl.v1.query.GroupByJoinGrammar} for the authoritative parse of this shape
     *  (this method only needs to find the embedded {@code context::} prefix, not fully parse the
     *  join). Fires for BOTH the bare-string shape ({@code "groupBy": ["inventory::lote.produtoId"]})
     *  and the object shape ({@code {"field": "inventory::lote.produtoId", "bucket": "month"}}) --
     *  the generic recursive walker never visits a scalar array item, so the string shape needs this
     *  explicit scan; the object shape would also be reached by that walker's own recursion, but is
     *  handled here too for a single, easy-to-audit call site. A no-op for packs (which pass
     *  {@link #QUALIFIED_REF_NOOP}), unrestricted exactly like every other pack-side reference. */
    private static void gateCheckGroupByJoinPaths(ObjectNode queryObject, QualifiedReferenceValidator qualifiedReferenceValidator) {
        JsonNode groupBy = queryObject.get("groupBy");
        if (groupBy == null || !groupBy.isArray()) {
            return;
        }
        for (JsonNode item : groupBy) {
            String field = null;
            if (item != null && item.isTextual()) {
                field = item.asText();
            } else if (item != null && item.isObject() && item.get("field") != null && item.get("field").isTextual()) {
                field = item.get("field").asText();
            }
            if (field != null && field.contains("::")) {
                qualifiedReferenceValidator.validate(field);
            }
        }
    }

    private static void rewriteTextOrArrayField(
            ObjectNode object,
            String fieldName,
            Map<String, String> conceptRewriteMap,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        JsonNode value = object.get(fieldName);
        if (value == null) {
            return;
        }
        if (value.isTextual()) {
            rewriteTextField(object, fieldName, conceptRewriteMap, qualifiedReferenceValidator);
        } else if (value.isArray()) {
            rewriteTextArrayField(object, fieldName, conceptRewriteMap, qualifiedReferenceValidator);
        }
    }

    private static void rewriteTextArrayField(
            ObjectNode object,
            String fieldName,
            Map<String, String> conceptRewriteMap,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isArray()) {
            return;
        }
        ArrayNode rewritten = JsonNodeFactory.instance.arrayNode();
        boolean changed = false;
        for (JsonNode item : value) {
            if (item != null && item.isTextual()) {
                String replacement = rewriteConceptName(item.asText(), conceptRewriteMap, qualifiedReferenceValidator);
                rewritten.add(replacement);
                changed = changed || !replacement.equals(item.asText());
            } else {
                rewritten.add(item == null ? JsonNodeFactory.instance.nullNode() : item.deepCopy());
            }
        }
        if (changed) {
            object.set(fieldName, rewritten);
        }
    }

    private static void rewriteTextField(
            ObjectNode object,
            String fieldName,
            Map<String, String> conceptRewriteMap,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isTextual()) {
            return;
        }
        String replacement = rewriteConceptName(value.asText(), conceptRewriteMap, qualifiedReferenceValidator);
        if (!replacement.equals(value.asText())) {
            object.put(fieldName, replacement);
        }
    }

    private static String rewriteConceptName(
            String authored, Map<String, String> conceptRewriteMap, QualifiedReferenceValidator qualifiedReferenceValidator) {
        if (authored == null) {
            return authored;
        }
        if (authored.contains("::")) {
            qualifiedReferenceValidator.validate(authored);
            return authored;
        }
        return conceptRewriteMap.getOrDefault(authored, authored);
    }

    /** B20 (S2): a no-op qualified-reference check -- packs today have no import restriction, so an
     *  already-qualified reference (cross-pack or, since D1 reuses the same {@code ::} separator,
     *  cross-context) is left completely unvalidated when reached through a pack's own merge path. */
    private static final QualifiedReferenceValidator QUALIFIED_REF_NOOP = qualifiedName -> { };

    @FunctionalInterface
    private interface QualifiedReferenceValidator {
        void validate(String qualifiedName);
    }

    private static Path resolvePackPath(String ref, Path modelFile, Path rootDirectory) throws IOException {
        return resolveJsonRefUnderRoot(ref, modelFile, rootDirectory, rootDirectory, "Pack $ref");
    }

    private static ObjectNode loadPackJson(Path packFile, ResolutionState state) throws IOException {
        JsonNode node = readJson(packFile);
        if (!node.isObject()) {
            throw error(packFile, "$", "Pack file must be a JSON object");
        }
        PACK_SCHEMA_VALIDATOR.validate(node, packFile.toString());
        state.includedFiles.add(packFile);
        state.seenIncludedFiles.add(packFile);
        return (ObjectNode) node;
    }

    private ObjectNode resolvePackRoot(
            ObjectNode rawPack,
            Path packFile,
            ResolutionState state,
            int depth,
            ArrayDeque<Path> stack
    ) throws IOException {
        Path packDirectory = packFile.getParent();
        if (packDirectory == null) {
            throw error(packFile, "$", "Pack file must have a parent directory");
        }
        ObjectNode resolvedPack = JsonNodeFactory.instance.objectNode();
        for (String key : PACK_ROOT_SCALAR_KEYS) {
            if (rawPack.has(key)) {
                resolvedPack.set(key, rawPack.get(key).deepCopy());
            }
        }
        for (String key : MODEL_ARRAY_KEYS) {
            JsonNode value = rawPack.get(key);
            if (value != null) {
                resolvedPack.set(key, resolvePackArray(key, value, packFile, packDirectory, state, depth, stack));
            }
        }
        if (rawPack.has("metadata")) {
            resolvedPack.set("metadata", rawPack.get("metadata").deepCopy());
        }
        JsonNode fragments = rawPack.get("fragments");
        if (fragments != null) {
            if (!fragments.isArray()) {
                throw error(packFile, "/fragments", "Pack fragments must be an array of local $ref objects");
            }
            int index = 0;
            for (JsonNode fragmentRef : fragments) {
                Ref ref = parseRefObject(fragmentRef, packFile, "/fragments/" + index);
                ObjectNode fragment = resolvePackFragment(ref, packFile, packDirectory, state, depth + 1, stack);
                appendPackFragment(resolvedPack, fragment);
                index++;
            }
        }
        return resolvedPack;
    }

    private ObjectNode resolvePackFragment(
            Ref ref,
            Path referencingFile,
            Path packDirectory,
            ResolutionState state,
            int depth,
            ArrayDeque<Path> stack
    ) throws IOException {
        Path fragmentFile = resolveJsonRefUnderRoot(
                ref.ref(),
                referencingFile,
                state.rootDirectory,
                packDirectory,
                "Pack fragment $ref"
        );
        JsonNode fragmentNode = resolveIncludedFile(fragmentFile, state, depth, stack);
        if (!fragmentNode.isObject()) {
            throw error(fragmentFile, "$", "Pack fragment file must contain a JSON object");
        }
        ObjectNode fragmentObject = (ObjectNode) fragmentNode;
        validatePackFragmentObject(fragmentObject, fragmentFile);

        ArrayDeque<Path> childStack = new ArrayDeque<>(stack);
        childStack.push(fragmentFile);
        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (String key : MODEL_ARRAY_KEYS) {
            JsonNode value = fragmentObject.get(key);
            if (value != null) {
                out.set(key, resolvePackArray(key, value, fragmentFile, packDirectory, state, depth + 1, childStack));
            }
        }
        if (fragmentObject.has("metadata")) {
            out.set("metadata", fragmentObject.get("metadata").deepCopy());
        }
        JsonNode nestedFragments = fragmentObject.get("fragments");
        if (nestedFragments != null) {
            if (!nestedFragments.isArray()) {
                throw error(fragmentFile, "/fragments", "Pack fragments must be an array of local $ref objects");
            }
            int index = 0;
            for (JsonNode nestedRefNode : nestedFragments) {
                Ref nestedRef = parseRefObject(nestedRefNode, fragmentFile, "/fragments/" + index);
                ObjectNode nested = resolvePackFragment(nestedRef, fragmentFile, packDirectory, state, depth + 1, childStack);
                appendPackFragment(out, nested);
                index++;
            }
        }
        return out;
    }

    private ArrayNode resolvePackArray(
            String key,
            JsonNode node,
            Path sourceFile,
            Path packDirectory,
            ResolutionState state,
            int depth,
            ArrayDeque<Path> stack
    ) throws IOException {
        if (!node.isArray()) {
            throw error(sourceFile, "/" + key, key + " must be an array");
        }
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        int index = 0;
        for (JsonNode item : node) {
            if (isRefObject(item)) {
                Ref ref = parseRefObject(item, sourceFile, "/" + key + "/" + index);
                Path includedFile = resolveJsonRefUnderRoot(
                        ref.ref(),
                        sourceFile,
                        state.rootDirectory,
                        packDirectory,
                        "Pack " + key + " $ref"
                );
                JsonNode included = resolveIncludedFile(includedFile, state, depth + 1, stack);
                if (included.isObject() && included.has(key) && included.get(key).isArray()) {
                    for (JsonNode expanded : included.get(key)) {
                        out.add(expanded.deepCopy());
                    }
                } else {
                    out.add(included.deepCopy());
                }
            } else {
                validateNoMalformedRef(item, sourceFile, "/" + key + "/" + index);
                out.add(item.deepCopy());
            }
            index++;
        }
        return out;
    }

    private static void appendPackFragment(ObjectNode target, ObjectNode fragment) throws IOException {
        for (String key : MODEL_ARRAY_KEYS) {
            JsonNode value = fragment.get(key);
            if (value == null) {
                continue;
            }
            if (!value.isArray()) {
                throw new IOException("Pack fragment key '" + key + "' must be an array");
            }
            ArrayNode targetArray = target.has(key) && target.get(key).isArray()
                    ? (ArrayNode) target.get(key)
                    : JsonNodeFactory.instance.arrayNode();
            for (JsonNode item : value) {
                targetArray.add(item.deepCopy());
            }
            target.set(key, targetArray);
        }
        JsonNode metadata = fragment.get("metadata");
        if (metadata != null) {
            if (!metadata.isObject()) {
                throw new IOException("Pack fragment metadata must be an object");
            }
            ObjectNode targetMetadata = target.has("metadata") && target.get("metadata").isObject()
                    ? (ObjectNode) target.get("metadata")
                    : JsonNodeFactory.instance.objectNode();
            metadata.fields().forEachRemaining(entry -> {
                if (!targetMetadata.has(entry.getKey())) {
                    targetMetadata.set(entry.getKey(), entry.getValue().deepCopy());
                }
            });
            target.set("metadata", targetMetadata);
        }
    }

    private static void mergePackConcepts(
            String packId,
            ObjectNode packNode,
            ObjectNode resolved,
            Path packFile,
            Map<String, String> conceptRewriteMap
    )
            throws IOException {
        mergeQualifiedConcepts("Pack", packId, packNode, resolved, packFile, conceptRewriteMap);
    }

    /** B20 (S2): generalized over {@link #mergePackConcepts} -- identical logic, {@code kindLabel}
     *  ("Pack"/"Context") only changes the duplicate-concept error message so a context author sees
     *  "Context 'inventory' contributes duplicate concept ..." rather than a confusing "Pack ...". */
    private static void mergeQualifiedConcepts(
            String kindLabel,
            String qualifierId,
            ObjectNode sourceNode,
            ObjectNode resolved,
            Path sourceFile,
            Map<String, String> conceptRewriteMap
    )
            throws IOException {
        JsonNode conceptsNode = sourceNode.get("concepts");
        if (conceptsNode == null || !conceptsNode.isArray()) {
            return;
        }
        ArrayNode targetConcepts = resolved.has("concepts") && resolved.get("concepts").isArray()
                ? (ArrayNode) resolved.get("concepts")
                : JsonNodeFactory.instance.arrayNode();
        for (JsonNode concept : conceptsNode) {
            if (concept.isObject()) {
                ObjectNode namespaced = namespacePackConcept(qualifierId, (ObjectNode) concept, conceptRewriteMap);
                String namespacedName = textOrBlank(namespaced.get("name"));
                for (JsonNode existing : targetConcepts) {
                    if (existing != null
                            && existing.isObject()
                            && existing.has("name")
                            && namespacedName.equalsIgnoreCase(existing.get("name").asText())) {
                        throw error(sourceFile, "/concepts",
                                kindLabel + " '" + qualifierId + "' contributes duplicate concept '" + namespacedName + "'");
                    }
                }
                targetConcepts.add(namespaced);
            }
        }
        resolved.set("concepts", targetConcepts);
    }

    private static ObjectNode namespacePackConcept(
            String packId,
            ObjectNode concept,
            Map<String, String> conceptRewriteMap
    ) {
        ObjectNode out = concept.deepCopy();
        if (concept.has("name") && concept.get("name").isTextual()) {
            out.put("name", packId + "::" + concept.get("name").asText());
        }
        JsonNode fields = concept.get("fields");
        if (fields != null && fields.isArray()) {
            ArrayNode newFields = JsonNodeFactory.instance.arrayNode();
            for (JsonNode field : fields) {
                newFields.add(field.isObject()
                        ? namespacePackFieldRefs((ObjectNode) field, conceptRewriteMap)
                        : field.deepCopy());
            }
            out.set("fields", newFields);
        }
        return out;
    }

    private static ObjectNode namespacePackFieldRefs(
            ObjectNode field,
            Map<String, String> conceptRewriteMap
    ) {
        ObjectNode out = field.deepCopy();
        if (out.has("ref") && out.get("ref").isTextual()) {
            String target = out.get("ref").asText();
            String rewritten = conceptRewriteMap.get(target);
            if (rewritten != null) {
                out.put("ref", rewritten);
            }
        }
        if (out.has("reference")) {
            JsonNode ref = out.get("reference");
            if (ref.isTextual() && conceptRewriteMap.containsKey(ref.asText())) {
                out.put("reference", conceptRewriteMap.get(ref.asText()));
            } else if (ref.isObject() && ref.has("target") && ref.get("target").isTextual()) {
                String target = ref.get("target").asText();
                String rewritten = conceptRewriteMap.get(target);
                if (rewritten != null) {
                    ObjectNode refObj = (ObjectNode) ref.deepCopy();
                    refObj.put("target", rewritten);
                    out.set("reference", refObj);
                }
            }
        }
        return out;
    }

    private ObjectNode resolveFragment(
            Ref ref,
            Path referencingFile,
            ResolutionState state,
            int depth,
            ArrayDeque<Path> stack
    ) throws IOException {
        Path includedFile = resolveIncludePath(ref.ref(), referencingFile, state);
        JsonNode fragment = resolveIncludedFile(includedFile, state, depth, stack);
        if (!fragment.isObject()) {
            throw error(includedFile, "$", "Fragment file must contain a JSON object");
        }
        ArrayDeque<Path> childStack = new ArrayDeque<>(stack);
        childStack.push(includedFile);
        ObjectNode fragmentObject = (ObjectNode) fragment;
        validateFragmentObject(fragmentObject, includedFile);

        ObjectNode out = JsonNodeFactory.instance.objectNode();
        for (String key : MODEL_ARRAY_KEYS) {
            JsonNode value = fragmentObject.get(key);
            if (value != null) {
                out.set(key, resolveArray(key, value, includedFile, state, depth + 1, childStack));
            }
        }
        if (fragmentObject.has("metadata")) {
            out.set("metadata", fragmentObject.get("metadata").deepCopy());
        }
        JsonNode nestedFragments = fragmentObject.get("fragments");
        if (nestedFragments != null) {
            if (!nestedFragments.isArray()) {
                throw error(includedFile, "/fragments", "fragments must be an array of local $ref objects");
            }
            ObjectNode nestedMetadata = JsonNodeFactory.instance.objectNode();
            Map<String, Path> nestedMetadataOwners = new LinkedHashMap<>();
            int index = 0;
            for (JsonNode nestedRefNode : nestedFragments) {
                Ref nestedRef = parseRefObject(nestedRefNode, includedFile, "/fragments/" + index);
                ObjectNode nested = resolveFragment(nestedRef, includedFile, state, depth + 1, childStack);
                appendFragment(out, nestedMetadata, Set.of(), nestedMetadataOwners, nested, state);
                index++;
            }
            if (!nestedMetadata.isEmpty()) {
                ObjectNode effective = out.has("metadata") && out.get("metadata").isObject()
                        ? (ObjectNode) out.get("metadata")
                        : JsonNodeFactory.instance.objectNode();
                nestedMetadata.fields().forEachRemaining(entry -> {
                    if (!effective.has(entry.getKey())) {
                        effective.set(entry.getKey(), entry.getValue().deepCopy());
                    }
                });
                out.set("metadata", effective);
            }
        }
        return out;
    }

    private ArrayNode resolveArray(
            String key,
            JsonNode node,
            Path sourceFile,
            ResolutionState state,
            int depth,
            ArrayDeque<Path> stack
    ) throws IOException {
        if (!node.isArray()) {
            throw error(sourceFile, "/" + key, key + " must be an array");
        }
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        int index = 0;
        for (JsonNode item : node) {
            if (isRefObject(item)) {
                Ref ref = parseRefObject(item, sourceFile, "/" + key + "/" + index);
                Path includedFile = resolveIncludePath(ref.ref(), sourceFile, state);
                JsonNode included = resolveIncludedFile(includedFile, state, depth + 1, stack);
                if (included.isObject() && included.has(key) && included.get(key).isArray()) {
                    for (JsonNode expanded : included.get(key)) {
                        out.add(expanded.deepCopy());
                    }
                } else {
                    out.add(included.deepCopy());
                }
            } else {
                validateNoMalformedRef(item, sourceFile, "/" + key + "/" + index);
                out.add(item.deepCopy());
            }
            index++;
        }
        return out;
    }

    private JsonNode resolveIncludedFile(
            Path includedFile,
            ResolutionState state,
            int depth,
            ArrayDeque<Path> stack
    ) throws IOException {
        if (depth > maxIncludeDepth) {
            throw error(includedFile, "$", "Maximum model include depth exceeded: " + maxIncludeDepth);
        }
        if (stack.contains(includedFile)) {
            throw error(includedFile, "$", "Circular model include detected: " + includedFile);
        }
        if (state.seenIncludedFiles.add(includedFile)) {
            if (state.seenIncludedFiles.size() > maxIncludedFiles) {
                throw error(includedFile, "$", "Maximum model include file count exceeded: " + maxIncludedFiles);
            }
            state.includedFiles.add(includedFile);
        }
        stack.push(includedFile);
        JsonNode node = readJson(includedFile);
        addProvenance(state.provenance, node, pointerOf(node), includedFile);
        if (node.isObject()) {
            validateNoMalformedRef(node, includedFile, "$");
        }
        stack.pop();
        return node;
    }

    private Path resolveIncludePath(String ref, Path referencingFile, ResolutionState state) throws IOException {
        if (ref == null || ref.isBlank()) {
            throw error(referencingFile, "$ref", "$ref must be a non-blank relative .json path");
        }
        String normalizedRef = ref.replace('\\', '/');
        String lower = normalizedRef.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("file:")) {
            throw error(referencingFile, "$ref", "Model include ref must be local, not a URL: " + ref);
        }
        if (!lower.endsWith(".json")) {
            throw error(referencingFile, "$ref", "Model include ref must point to a .json file: " + ref);
        }
        Path refPath;
        try {
            refPath = Path.of(ref);
        } catch (InvalidPathException exception) {
            throw error(referencingFile, "$ref", "Invalid model include ref path: " + ref);
        }
        if (refPath.isAbsolute()) {
            throw error(referencingFile, "$ref", "Model include ref must be relative: " + ref);
        }
        Path parent = referencingFile.getParent();
        if (parent == null) {
            throw error(referencingFile, "$ref", "Referencing file must have a parent directory: " + referencingFile);
        }
        Path candidate = parent.resolve(refPath).normalize();
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException exception) {
            throw error(referencingFile, "$ref", "Referenced model fragment not found: " + ref);
        }
        if (!real.startsWith(state.rootDirectory)) {
            throw error(referencingFile, "$ref", "Referenced model fragment escapes the model root: " + ref);
        }
        if (!Files.isRegularFile(real)) {
            throw error(referencingFile, "$ref", "Referenced model fragment is not a file: " + ref);
        }
        return real;
    }

    private static JsonNode readJson(Path path) throws IOException {
        try {
            return MAPPER.readTree(path.toFile());
        } catch (JsonProcessingException exception) {
            throw error(path, "$", "Invalid JSON in model fragment: " + exception.getOriginalMessage());
        }
    }

    private static void validateRootAuthoringObject(ObjectNode root, Path sourceFile) throws IOException {
        // Unrecognized top-level keys are NOT rejected here; they are passed through by
        // resolveRoot so downstream JSON Schema validation (additionalProperties:false) is the
        // single authority that rejects them with a "Model schema validation failed" message.
        validateNoMalformedRef(root, sourceFile, "$");
    }

    private static boolean isRecognizedRootKey(String key) {
        return ROOT_SCALAR_KEYS.contains(key)
                || MODEL_ARRAY_KEYS.contains(key)
                || "metadata".equals(key)
                || "fragments".equals(key)
                || "packs".equals(key)
                || "contexts".equals(key);
    }

    private static void validateFragmentObject(ObjectNode fragment, Path sourceFile) throws IOException {
        fragment.fieldNames().forEachRemaining(key -> {
            if (!FRAGMENT_KEYS.contains(key)) {
                throwUnchecked(new IOException("Unsupported model fragment key '" + key + "' in " + sourceFile));
            }
        });
        validateNoMalformedRef(fragment, sourceFile, "$");
    }

    private static void validatePackFragmentObject(ObjectNode fragment, Path sourceFile) throws IOException {
        fragment.fieldNames().forEachRemaining(key -> {
            if (PACK_FRAGMENT_FORBIDDEN_KEYS.contains(key)) {
                throwUnchecked(new IOException("Unsupported pack fragment identity key '" + key + "' in " + sourceFile));
            }
            if (!FRAGMENT_KEYS.contains(key)) {
                throwUnchecked(new IOException("Unsupported pack fragment key '" + key + "' in " + sourceFile));
            }
        });
        validateNoMalformedRef(fragment, sourceFile, "$");
    }

    private static void validateNoMalformedRef(JsonNode node, Path sourceFile, String path) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null) {
                // A bare include is exactly {"$ref": "..."}; a pack import may additionally carry
                // an "as" alias ({"$ref": "...", "as": "..."}); a top-level context declaration (B20,
                // S2) additionally carries a required "name" ({"$ref": "...", "name": "..."}) and,
                // since S8 Wave 4 (ADR-0011 D4's v2 opt-in), an optional "physicallyIsolate", but
                // ONLY at /contexts/N -- everywhere else "name"/"physicallyIsolate" alongside a bare
                // $ref stays malformed, same as any other stray key would.
                boolean isContextDeclaration = path.matches("^\\$/contexts/\\d+$");
                boolean onlyRefAndOptionalAlias = node.size() == 1
                        || (node.size() == 2 && node.has("as"))
                        || (isContextDeclaration && node.size() == 2 && node.has("name"))
                        || (isContextDeclaration && node.size() == 3 && node.has("name")
                                && node.has("physicallyIsolate"));
                if (!onlyRefAndOptionalAlias) {
                    throwUnchecked(new IOException("Malformed model include at " + sourceFile + " " + path
                            + ": $ref object must not contain extra properties"));
                }
                if (!ref.isTextual() || ref.asText("").isBlank()) {
                    throwUnchecked(new IOException("Malformed model include at " + sourceFile + " " + path
                            + ": $ref must be a non-blank string"));
                }
            }
            node.fields().forEachRemaining(entry ->
                    validateNoMalformedRef(entry.getValue(), sourceFile, path + "/" + escapePointer(entry.getKey())));
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode item : node) {
                validateNoMalformedRef(item, sourceFile, path + "/" + index);
                index++;
            }
        }
    }

    private static boolean isRefObject(JsonNode node) {
        return node != null && node.isObject() && node.has("$ref");
    }

    private static Ref parseRefObject(JsonNode node, Path sourceFile, String path) throws IOException {
        if (!isRefObject(node)) {
            throw error(sourceFile, path, "Expected local model include object with $ref");
        }
        if (node.size() != 1) {
            throw error(sourceFile, path, "$ref object must be exactly { \"$ref\": \"relative/path.json\" }");
        }
        JsonNode ref = node.get("$ref");
        if (!ref.isTextual() || ref.asText("").isBlank()) {
            throw error(sourceFile, path, "$ref must be a non-blank string");
        }
        return new Ref(ref.asText());
    }

    private static Path resolveJsonRefUnderRoot(
            String ref,
            Path referencingFile,
            Path rootDirectory,
            Path containmentDirectory,
            String label
    ) throws IOException {
        if (ref == null || ref.isBlank()) {
            throw error(referencingFile, "$ref", label + " must be a non-blank relative .json path: " + ref);
        }
        String normalizedRef = ref.replace('\\', '/');
        String lower = normalizedRef.toLowerCase(Locale.ROOT);
        if (normalizedRef.matches("^[A-Za-z]:/.*")) {
            throw error(referencingFile, "$ref", label + " must be relative, not a drive path: " + ref);
        }
        if (lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("file:")
                || lower.matches("^[a-z][a-z0-9+.-]*:.*")) {
            throw error(referencingFile, "$ref", label + " must be local, not a URL or URI: " + ref);
        }
        if (normalizedRef.startsWith("//")) {
            throw error(referencingFile, "$ref", label + " must be relative, not a UNC path: " + ref);
        }
        if (!lower.endsWith(".json")) {
            throw error(referencingFile, "$ref", label + " must point to a .json file: " + ref);
        }
        Path refPath;
        try {
            refPath = Path.of(ref);
        } catch (InvalidPathException exception) {
            throw error(referencingFile, "$ref", "Invalid " + label + " path: " + ref);
        }
        if (refPath.isAbsolute()) {
            throw error(referencingFile, "$ref", label + " must be relative: " + ref);
        }
        Path parent = referencingFile.getParent();
        if (parent == null) {
            throw error(referencingFile, "$ref", "Referencing file must have a parent directory: " + referencingFile);
        }
        Path candidate = parent.resolve(refPath).normalize();
        Path real;
        try {
            real = candidate.toRealPath();
        } catch (IOException exception) {
            throw error(referencingFile, "$ref", label + " file not found: " + ref);
        }
        if (!Files.isRegularFile(real)) {
            throw error(referencingFile, "$ref", label + " is not a file: " + ref);
        }
        if (rootDirectory != null && !real.startsWith(rootDirectory)) {
            throw error(referencingFile, "$ref", label + " escapes the model root: " + ref);
        }
        if (containmentDirectory != null && !real.startsWith(containmentDirectory)) {
            throw error(referencingFile, "$ref", label + " escapes the pack directory: " + ref);
        }
        return real;
    }

    private static void addProvenance(Map<String, Path> provenance, JsonNode node, String pointer, Path source) {
        if (node == null) {
            return;
        }
        provenance.put(pointer == null || pointer.isBlank() ? "" : pointer, source);
        if (node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    addProvenance(provenance, entry.getValue(), (pointer == null ? "" : pointer)
                            + "/" + escapePointer(entry.getKey()), source));
        } else if (node.isArray()) {
            int index = 0;
            for (JsonNode item : node) {
                addProvenance(provenance, item, (pointer == null ? "" : pointer) + "/" + index, source);
                index++;
            }
        }
    }

    private static ValidationDiagnostic diagnostic(
            ValidationSeverity severity,
            String code,
            String message,
            Path source,
            String path
    ) {
        return new ValidationDiagnostic(
                ValidationLayer.STRUCTURAL,
                severity,
                code,
                message,
                source == null ? "NPDevContract" : source.toString(),
                path
        );
    }

    private static IOException error(Path sourceFile, String path, String message) {
        return new IOException(sourceFile + " " + path + ": " + message);
    }

    private static String escapePointer(String value) {
        return value == null ? "" : value.replace("~", "~0").replace("/", "~1");
    }

    private static String textOrBlank(JsonNode node) {
        return node != null && node.isTextual() ? node.asText("").trim() : "";
    }

    private static String pointerOf(JsonNode ignored) {
        return "";
    }

    private static void throwUnchecked(IOException exception) {
        throw new UncheckedModelSourceException(exception);
    }

    private record Ref(String ref) {
    }

    private static final class ResolutionState {
        private final Path rootRealPath;
        private final Path rootDirectory;
        private final List<Path> includedFiles = new ArrayList<>();
        private final Set<Path> seenIncludedFiles = new LinkedHashSet<>();
        private final Map<String, Path> provenance = new HashMap<>();
        private final List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        private final List<ValidationDiagnostic> warnings = new ArrayList<>();

        private ResolutionState(Path rootRealPath, Path rootDirectory) {
            this.rootRealPath = rootRealPath;
            this.rootDirectory = rootDirectory;
        }
    }

    private static final class UncheckedModelSourceException extends RuntimeException {
        private final IOException cause;

        private UncheckedModelSourceException(IOException cause) {
            super(cause);
            this.cause = cause;
        }

        @Override
        public synchronized IOException getCause() {
            return cause;
        }
    }
}
