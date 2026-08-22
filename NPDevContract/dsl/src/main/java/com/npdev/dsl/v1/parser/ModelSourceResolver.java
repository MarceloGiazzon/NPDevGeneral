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
            "properties",
            // PACK-11 (2026-08-17): the same omission as the three above, four more keys later.
            // Measured by machine-diffing this set against the real top-level schema keys:
            // aggregates/autoPanels/documents/selectors were missing from BOTH this set and
            // pack.schema.json, so a pack declaring one was refused outright; `guidePages` was the
            // asymmetric case -- already merged here, still rejected by the schema, which is the
            // shape that tells you the two lists were never checked against each other.
            //
            // Added HERE FIRST and to the schema second, deliberately. Relaxing the schema alone
            // would accept the file and drop its content in silence, which is REG-108's X0 shape
            // and strictly worse than the refusal it replaces.
            "aggregates",
            "autoPanels",
            "documents",
            "selectors",
            // R6.2 (2026-08-19): webhooks[] threaded exactly like the PACK-11 four keys above --
            // composer support here first, pack.schema.json permission second (relaxing the schema
            // alone would accept a pack's webhooks[] file and drop its content in silence, REG-108's
            // X0 shape). Its `eventName` field also needs a rewriteKnownMemberReferenceFields entry
            // (the "PACK-11 fifth place" for any member kind carrying a reference of its own) --
            // see that method's own "webhooks" branch.
            "webhooks",
            // R5.3 (2026-08-19): sequences[] threaded the same way. Unlike every other kind in this
            // set, its own `name` field is deliberately EXCLUDED from mergeQualifiedNonConceptArrays'
            // generic pack-qualification (see that method's own "sequences" check) -- a sequence is
            // referenced by nextNumber('name') as an opaque literal argument embedded inside a
            // field's defaultExpression TEXT, which rewriteKnownMemberReferenceFields cannot reach
            // (it rewrites discrete JSON fields, never substrings inside another field's expression
            // string). Qualifying the declaration but never the reference would silently break every
            // pack-declared sequence -- the same reasoning webhooks[].source already established for
            // a wire-visible identity. SequenceValidation instead requires global name uniqueness
            // across the fully-resolved model, closing the loop WebhookValidation closes for source.
            "sequences",
            // R8.8 (Roadmap Wave 2, 2026-08-19): seeds[] threaded the same way, but with the
            // OPPOSITE reference-rewriting posture from every kind above: a seed's `concept` field
            // is REWRITTEN to pack-qualified form when pack/context-declared, but deliberately NOT
            // via the shared rewriteKnownMemberReferenceFields/resolveUnqualifiedReferences path
            // every other kind uses -- that path's later global pass would silently resolve an
            // unowned bare concept name to some OTHER pack's same-named concept the moment it
            // happens to be globally unique, which is exactly wrong here: inserting rows into a
            // concept another pack owns, unattended, at that pack's own first boot, is a materially
            // different hazard than merely reading/joining it. mergeQualifiedNonConceptArrays' own
            // "seeds" branch resolves `concept` ONLY against the SAME pack/context's own local
            // concept map and throws immediately if it does not resolve there -- a compile error,
            // not a silent cross-pack fix. A seed record also has no `name` field, so it never
            // participates in the generic name-qualification/duplicate-name-within-a-pack check
            // every sibling kind above gets for free.
            "seeds"
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
            "description",
            // REG-151: same "schema-validates then silently vanishes" hazard resolvePackRoot's own
            // packs[]/requires/migrations comment already documents (REG-108's shape) -- a plain
            // scalar string, so PACK_ROOT_SCALAR_KEYS (not a dedicated passthrough line) is the
            // right fix, unlike migrations' keyed-object shape below.
            "firstPublishedVersion"
    );
    private static final Set<String> PACK_FRAGMENT_FORBIDDEN_KEYS = Set.of(
            "$schema",
            "dslVersion",
            "pack",
            "namespace",
            "version",
            "description",
            // PK-3: identity-level declarations exactly like the six above -- a fragment declaring
            // its own transitive dependencies or its own requires would be the same authoring
            // mistake as a fragment declaring its own pack id.
            "packs",
            "requires"
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
                    state.warnings,
                    state.physicalQualifierByConceptName,
                    state.migrationTrackedPacks,
                    state.originByQualifiedMemberName
            );
        } catch (UncheckedModelSourceException exception) {
            throw exception.getCause();
        }
    }

    /** PK-3 CLI-only entry point ({@code npdev pack add|update|list|why}): runs the same
     *  discovery+MVS pass {@link #resolve} does, but never enforces {@code npdev.lock} (add/update
     *  are what WRITE it; why is always a fresh live computation) and never merges pack content
     *  into a model -- just returns the resolved graph. A model with no {@code packs[]} at all
     *  resolves to an empty result.
     *
     *  @param networkPolicy PK-5: {@link com.npdev.dsl.v1.pack.NetworkPolicy#ALLOWED} for {@code
     *                       pack add}/{@code update} (the only two commands allowed to fetch a
     *                       {@code from}-based remote pack); {@link
     *                       com.npdev.dsl.v1.pack.NetworkPolicy#DENIED} for {@code pack list}/
     *                       {@code why}, which never fetch. */
    public PackCliResolution resolvePackGraphForCli(Path modelJsonPath, com.npdev.dsl.v1.pack.NetworkPolicy networkPolicy) throws IOException {
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
        JsonNode root = readJson(rootRealPath);
        if (!root.isObject()) {
            throw error(rootRealPath, "$", "Root model must be a JSON object");
        }
        JsonNode packs = root.get("packs");
        if (packs == null || !packs.isArray() || packs.isEmpty()) {
            return new PackCliResolution(Map.of(), Map.of(), rootDirectory);
        }

        ResolutionState state = new ResolutionState(rootRealPath, rootDirectory);
        ObjectNode resolvedShell = JsonNodeFactory.instance.objectNode();
        if (root.has("dslVersion")) {
            resolvedShell.set("dslVersion", root.get("dslVersion").deepCopy());
        }
        PackDependencyGraphWalker walker = PackDependencyGraphWalker.resolveForCli(
                this, (ArrayNode) packs, resolvedShell, rootRealPath, state, networkPolicy);

        Map<String, com.npdev.dsl.v1.pack.PackLockFile.LockedPack> lockEntries = walker.toLockEntries();
        Map<String, List<String>> why = new LinkedHashMap<>();
        for (String packId : walker.resolvedPackIds()) {
            List<String> descriptions = new ArrayList<>();
            for (com.npdev.dsl.v1.pack.MinimalVersionSelector.Requirement requirement : walker.requirementsFor(packId)) {
                descriptions.add(requirement.requirerPackId() + " needs " + requirement.constraint().rawConstraint()
                        + " via " + String.join(" -> ", requirement.path()));
            }
            why.put(packId, descriptions);
        }
        return new PackCliResolution(lockEntries, why, rootDirectory);
    }

    /** PK-3 CLI-only result: {@code lockEntries} is exactly what {@code npdev pack add/update}
     *  should write to {@code npdev.lock}; {@code whyDescriptionsByPackId} is every constraint
     *  that contributed to each packId's selection, for {@code npdev pack why}. */
    public record PackCliResolution(
            Map<String, com.npdev.dsl.v1.pack.PackLockFile.LockedPack> lockEntries,
            Map<String, List<String>> whyDescriptionsByPackId,
            Path rootDirectory
    ) {
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
            List<PackRequirementEntry> requirements =
                    resolvePacks((ArrayNode) packs, resolved, sourceFile, state, root.get("provides"));
            checkPackRequirements(requirements, root.get("provides"), sourceFile);
        }

        JsonNode contexts = root.get("contexts");
        if (contexts != null) {
            if (!contexts.isArray()) {
                throw error(sourceFile, "/contexts", "contexts must be an array of {name, $ref} objects");
            }
            resolveContexts((ArrayNode) contexts, resolved, sourceFile, state);
        }

        resolveUnqualifiedReferences(resolved, sourceFile);

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

            Map<String, Map<String, String>> rewriteMaps = buildRewriteMaps(name, contextContent);
            mergeQualifiedConcepts("Context", name, contextContent, resolved, contextFile, rewriteMaps);

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
            mergeQualifiedNonConceptArrays("Context", name, contextContent, resolved, contextFile, rewriteMaps, Map.of(), gate);
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
                detectCycleFrom(start, importGraph, visited, onStack, pathStack, cyclePath ->
                        error(modelFile, "/contexts", "Import cycle detected: " + String.join(" -> ", cyclePath)
                                + " -- a context's imports[] graph must be acyclic (a cycle means the boundary "
                                + "is not a boundary)"));
            }
        }
    }

    /** PK-3: the generic DFS-with-onStack cycle detector D8 built for the bounded-context
     *  {@code imports[]} graph, extracted so {@link PackDependencyGraphWalker}'s packId-keyed
     *  dependency graph can reuse the SAME algorithm rather than a second hand-rolled one --
     *  only the error message differs between the two callers, supplied via {@code errorReporter}.
     *  Package-private: PackDependencyGraphWalker is a sibling class in this package. */
    static void detectCycleFrom(
            String node,
            Map<String, Set<String>> graph,
            Set<String> visited,
            Set<String> onStack,
            Deque<String> pathStack,
            CycleErrorReporter errorReporter
    ) throws IOException {
        visited.add(node);
        onStack.add(node);
        pathStack.push(node);
        for (String next : graph.getOrDefault(node, Set.of())) {
            if (onStack.contains(next)) {
                List<String> cyclePath = new ArrayList<>(pathStack);
                Collections.reverse(cyclePath);
                int startIndex = cyclePath.indexOf(next);
                List<String> cycle = new ArrayList<>(cyclePath.subList(startIndex, cyclePath.size()));
                cycle.add(next);
                throw errorReporter.describe(cycle);
            }
            if (!visited.contains(next)) {
                detectCycleFrom(next, graph, visited, onStack, pathStack, errorReporter);
            }
        }
        pathStack.pop();
        onStack.remove(node);
    }

    @FunctionalInterface
    interface CycleErrorReporter {
        IOException describe(List<String> cyclePath) throws IOException;
    }

    /**
     * PK-3: delegates to {@link PackDependencyGraphWalker}, which reproduces this method's own
     * pre-PK-3 behavior exactly when no pack in the graph declares its own {@code packs[]} (every
     * existing pack/app in this repo today) and additionally resolves transitive dependencies,
     * cycles, and depth/fan-out DoS caps when one does.
     */
    private List<PackRequirementEntry> resolvePacks(
            ArrayNode packsNode,
            ObjectNode resolved,
            Path modelFile,
            ResolutionState state,
            JsonNode provides
    ) throws IOException {
        return PackDependencyGraphWalker.resolve(this, packsNode, resolved, modelFile, state, provides);
    }

    /** PK-3: one pack's own {@code requires} declaration, plus the path that reached it -- for
     *  naming exactly which pack (and how it was reached) left a requirement unbound. */
    record PackRequirementEntry(String packId, List<String> path, JsonNode requires) {
    }

    /** PACK-2 (ledger; PACK-ROADMAP.md card PK-1 steps 5-7): the pack-attribution facts recorded
     *  for every member a pack contributes, populated by {@link #recordOrigin} and consumed by
     *  {@code JsonModelParser} to attach an {@code OriginAst} to each parsed AST node. Fields mirror
     *  {@code com.npdev.dsl.v1.ast.OriginAst}/{@code com.npdev.dsl.v1.compiled.CompiledOrigin}
     *  exactly; kept as a separate, parser-package-local type (rather than reusing the ast type
     *  directly) so this resolver -- which otherwise works purely at the JSON level, never
     *  constructing an AST node itself -- does not take on an ast-package dependency. */
    record PackOrigin(String packId, String packVersion, String packDigest, boolean sealed) {
    }

    /** PK-3: refuses composition the moment any collected {@code requires.roles}/{@code
     *  capabilities}/{@code network} entry is not present in the app's own root {@code provides}
     *  -- checked here (resolve time, right after resolvePacks returns) to stay consistent with
     *  every other pack-composition invariant this method already enforces at this same point
     *  (dslVersion equality, duplicate-alias/-concept). Only proves presence/binding; rewriting a
     *  pack's own internal role checks to consume the app's concrete role name is PACK-9, still
     *  open. */
    private void checkPackRequirements(List<PackRequirementEntry> requirements, JsonNode provides, Path modelFile) throws IOException {
        if (requirements.isEmpty()) {
            return;
        }
        Set<String> providedRoles = textSetOf(provides, "roles");
        Set<String> providedCapabilities = textSetOf(provides, "capabilities");
        Set<String> providedNetwork = textSetOf(provides, "network");
        for (PackRequirementEntry entry : requirements) {
            checkRequirementKind(entry, "roles", providedRoles, modelFile);
            checkRequirementKind(entry, "capabilities", providedCapabilities, modelFile);
            checkRequirementKind(entry, "network", providedNetwork, modelFile);
        }
    }

    private void checkRequirementKind(PackRequirementEntry entry, String kind, Set<String> provided, Path modelFile) throws IOException {
        JsonNode values = entry.requires().get(kind);
        if (values == null || !values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                continue;
            }
            String required = value.asText();
            if (!provided.contains(required)) {
                throw error(modelFile, "/packs", "pack '" + entry.packId() + "' (via " + String.join(" -> ", entry.path())
                        + ") requires " + kind.substring(0, kind.length() - 1) + " '" + required
                        + "', which the app does not declare in provides." + kind);
            }
        }
    }

    private static Set<String> textSetOf(JsonNode object, String field) {
        if (object == null || !object.isObject()) {
            return Set.of();
        }
        JsonNode array = object.get(field);
        if (array == null || !array.isArray()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode value : array) {
            if (value.isTextual()) {
                out.add(value.asText());
            }
        }
        return out;
    }

    /**
     * PK-2: the physical SQL identity of a pack-derived concept must depend on the pack's own
     * {@code pack} id + major version, never on the importing app's chosen {@code as} alias -- two
     * apps importing the same pack under different aliases must still produce identical physical
     * table names. {@code packId} above (the loop-local qualifier used for logical namespacing,
     * {@code qualifierId::Name}) stays alias-able and untouched; this records a SEPARATE map, keyed
     * by the same qualified concept name {@link #namespacePackConcept} just produced, whose value is
     * derived from the pack's own {@code pack}/{@code version} fields instead of the alias.
     */
    static void recordPhysicalQualifiers(
            String qualifierId,
            ObjectNode packNode,
            Path packFile,
            ResolutionState state
    ) throws IOException {
        String realPackId = textOrBlank(packNode.get("pack"));
        int majorVersion = parsePackMajorVersion(packNode.get("version"), packFile);
        String physicalQualifier = realPackId + "_v" + majorVersion;

        JsonNode conceptsNode = packNode.get("concepts");
        if (conceptsNode == null || !conceptsNode.isArray()) {
            return;
        }
        for (JsonNode concept : conceptsNode) {
            if (concept == null || !concept.isObject() || !concept.has("name")) {
                continue;
            }
            String bareName = textOrBlank(concept.get("name"));
            if (bareName.isBlank()) {
                continue;
            }
            state.physicalQualifierByConceptName.put(qualifierId + "::" + bareName, physicalQualifier);
        }
    }

    /**
     * PACK-2 (ledger; {@code PACK-ROADMAP.md} card PK-1 steps 5-7): records which pack (and which
     * version/digest/sealedness of it) contributed EVERY member this pack merge just added under
     * {@code qualifierId} -- one entry per {@link #MODEL_ARRAY_KEYS} kind the pack actually declares,
     * keyed by the same already-qualified ({@code qualifierId::Name}) name {@link
     * #mergeQualifiedConcepts}/{@link #mergeQualifiedNonConceptArrays} just wrote into the resolved
     * model, so {@code JsonModelParser} can look an origin up by (kind, qualified name) as it parses
     * each member kind's own array. Mirrors {@link #recordPhysicalQualifiers}'s own "derive straight
     * off the pack's own JSON header, never the local alias" rule for {@code packId}.
     *
     * <p>Deliberately walks all 18 {@link #MODEL_ARRAY_KEYS} kinds (via {@link #memberRewriteMap},
     * the same generalized walker PK-1 steps 1-4 already built) even though only 8 of them
     * (concepts, domainTypes, capabilities, customCapabilities, events, flows, queries, roles,
     * panels) currently carry an {@code origin} field on their {@code Compiled*}/{@code *Ast} type
     * -- {@code JsonModelParser} only ever looks up the 8 kinds it knows about, so the extra entries
     * for the other 10 kinds are simply never read. Doing the walk generically here (rather than
     * hand-listing 8 kind strings) means a future kind gaining an {@code origin} field only needs
     * the AST/Compiled/parser/compiler/canonical-JSON wiring, not a change here too.
     *
     * <p>Never called for a context merge (contexts are a physical-isolation mechanism, unrelated to
     * pack provenance) -- only {@link PackDependencyGraphWalker#run} calls this, right alongside
     * {@link #recordPhysicalQualifiers}, so a context-contributed member's origin stays absent from
     * the map and every lookup against it resolves to null (not pack-contributed).
     */
    static void recordOrigin(
            String qualifierId,
            ObjectNode packNode,
            String digest,
            ResolutionState state
    ) {
        String realPackId = textOrBlank(packNode.get("pack"));
        String packVersion = textOrBlank(packNode.get("version"));
        boolean sealed = com.npdev.dsl.v1.pack.PackSealednessAnalyzer.analyze(packNode).sealed();
        PackOrigin origin = new PackOrigin(realPackId, packVersion, digest, sealed);

        for (String kind : MODEL_ARRAY_KEYS) {
            Map<String, String> rewriteMap = memberRewriteMap(qualifierId, packNode, kind);
            if (rewriteMap.isEmpty()) {
                continue;
            }
            Map<String, PackOrigin> byQualifiedName =
                    state.originByQualifiedMemberName.computeIfAbsent(kind, ignored -> new LinkedHashMap<>());
            for (String qualifiedName : rewriteMap.values()) {
                byQualifiedName.put(qualifiedName, origin);
            }
        }
    }

    private static int parsePackMajorVersion(JsonNode versionNode, Path packFile) throws IOException {
        String version = versionNode == null ? "" : textOrBlank(versionNode);
        if (version.isBlank()) {
            throw error(packFile, "/version", "Pack file must declare a non-blank string 'version'");
        }
        int dot = version.indexOf('.');
        String majorText = dot < 0 ? version : version.substring(0, dot);
        try {
            return Integer.parseInt(majorText.trim());
        } catch (NumberFormatException notNumeric) {
            throw error(packFile, "/version",
                    "Pack 'version' must start with an integer major version (e.g. \"1.0.0\"), got: " + version);
        }
    }

    static String resolvePackNamespace(
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
        return memberRewriteMap(packId, packNode, "concepts");
    }

    /** PK-1 (PACK-ROADMAP.md): generalizes {@link #packConceptRewriteMap} to any of the 18
     *  {@link #MODEL_ARRAY_KEYS} kinds -- a local (bare) member name declared under {@code kind} in
     *  this pack/context contribution, mapped to its qualified {@code qualifierId::name} form.
     *
     *  <p>npdev-qualifier-rule -- twin-pair token, see scripts/quality/twin-pair-registry.json.
     *  THIS is the authoritative copy of the qualification rule: a context qualifies by the name
     *  its {@code contexts[]} entry declares, a pack by its {@code as} alias when the import gives
     *  one and by the pack file's own {@code pack} id otherwise. REG-186 (2026-08-17) added a
     *  second, deliberately narrow copy in {@code NPDevCli/npdev_cli.py}, so that the build-free
     *  introspection commands ({@code inspect app}/{@code inspect bonds}/
     *  {@code validate model --structural-only}) can see pack- and context-contributed members
     *  without a Gradle run. Two walks of the same graph is REG-108's exact shape, so the two are
     *  pinned: drop this token from either file and check-twin-pair-consistency.py fails the
     *  ai-knowledge gate. */
    private static Map<String, String> memberRewriteMap(String qualifierId, ObjectNode sourceNode, String kind) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode membersNode = sourceNode.get(kind);
        if (membersNode == null || !membersNode.isArray()) {
            return out;
        }
        for (JsonNode member : membersNode) {
            if (member != null && member.isObject() && member.has("name") && member.get("name").isTextual()) {
                String name = member.get("name").asText();
                out.put(name, qualifierId + "::" + name);
            }
        }
        return out;
    }

    /** PK-1: one rewrite map per {@link #MODEL_ARRAY_KEYS} kind for a single pack/context
     *  contribution, keyed by kind so a reference field (e.g. {@code query}) only ever resolves
     *  against that kind's own map (e.g. {@code queries}), never a same-named member of a
     *  different kind. {@code capabilities} and {@code customCapabilities} share one merged map --
     *  a {@code capability} reference field does not distinguish which of the two declared it. */
    static Map<String, Map<String, String>> buildRewriteMaps(String qualifierId, ObjectNode sourceNode) {
        Map<String, Map<String, String>> maps = new LinkedHashMap<>();
        for (String kind : MODEL_ARRAY_KEYS) {
            maps.put(kind, memberRewriteMap(qualifierId, sourceNode, kind));
        }
        Map<String, String> mergedCapabilities = new LinkedHashMap<>(maps.getOrDefault("capabilities", Map.of()));
        mergedCapabilities.putAll(maps.getOrDefault("customCapabilities", Map.of()));
        maps.put("capabilities", mergedCapabilities);
        return maps;
    }

    /** PK-1 step 4 (PACK-ROADMAP.md card PK-1): runs once, after every pack and context is fully
     *  merged into {@code resolved} -- an unqualified (bare) reference resolves if EXACTLY ONE
     *  composed pack/context provides a member with that bare name for the relevant kind; two or
     *  more candidates is a named, thrown ambiguity error (never a silent pick); zero candidates is
     *  left completely untouched (not a pack reference at all -- resolves normally downstream, or
     *  surfaces as the model's own ordinary "not found" error there, unchanged behavior).
     *
     *  <p>Reuses {@link #rewriteKnownMemberReferenceFields}'s exact field-dispatch table by walking
     *  every {@link #MODEL_ARRAY_KEYS} array in the FULLY RESOLVED model (including {@code concepts}
     *  this time, unlike the per-pack pass, which explicitly skips it) -- this is what lets a
     *  root-model-authored concept's own {@code field.domainType} resolve against a pack-provided
     *  domain type without any pack-specific code path: the same walker just runs one more time. */
    private void resolveUnqualifiedReferences(ObjectNode resolved, Path sourceFile) {
        Map<String, Map<String, Set<String>>> candidatesByKind = new LinkedHashMap<>();
        for (String kind : MODEL_ARRAY_KEYS) {
            JsonNode array = resolved.get(kind);
            if (array == null || !array.isArray()) {
                continue;
            }
            Map<String, Set<String>> candidates = new LinkedHashMap<>();
            for (JsonNode member : array) {
                if (member == null || !member.isObject() || !member.has("name") || !member.get("name").isTextual()) {
                    continue;
                }
                String qualifiedName = member.get("name").asText();
                int separator = qualifiedName.indexOf("::");
                if (separator <= 0) {
                    continue;
                }
                String bareName = qualifiedName.substring(separator + 2);
                candidates.computeIfAbsent(bareName, ignored -> new LinkedHashSet<>()).add(qualifiedName);
            }
            candidatesByKind.put(kind, candidates);
        }

        Map<String, Map<String, String>> globalRewriteMaps = new LinkedHashMap<>();
        Map<String, Map<String, Set<String>>> ambiguousNames = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Set<String>>> kindEntry : candidatesByKind.entrySet()) {
            Map<String, String> unambiguous = new LinkedHashMap<>();
            Map<String, Set<String>> ambiguous = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> nameEntry : kindEntry.getValue().entrySet()) {
                if (nameEntry.getValue().size() == 1) {
                    unambiguous.put(nameEntry.getKey(), nameEntry.getValue().iterator().next());
                } else {
                    ambiguous.put(nameEntry.getKey(), nameEntry.getValue());
                }
            }
            globalRewriteMaps.put(kindEntry.getKey(), unambiguous);
            if (!ambiguous.isEmpty()) {
                ambiguousNames.put(kindEntry.getKey(), ambiguous);
            }
        }
        Map<String, String> mergedCapabilities = new LinkedHashMap<>(globalRewriteMaps.getOrDefault("capabilities", Map.of()));
        mergedCapabilities.putAll(globalRewriteMaps.getOrDefault("customCapabilities", Map.of()));
        globalRewriteMaps.put("capabilities", mergedCapabilities);

        for (String kind : MODEL_ARRAY_KEYS) {
            // R8.8: seeds[] is deliberately excluded from this GLOBAL cross-pack pass -- see
            // rewriteSeedConceptOwnership's javadoc. A pack/context-declared seed's `concept` was
            // already resolved (or rejected) against ONLY its own declaring pack/context's local
            // concepts by mergeQualifiedNonConceptArrays; letting this generic walker visit it again
            // here would (a) risk resolving an otherwise-unowned bare name to some OTHER pack's
            // same-named concept via the unqualified-reference convenience every other kind gets,
            // exactly the hazard seed ownership must never be subject to, and (b) recurse into each
            // record's `data` -- an arbitrary business payload, not DSL structure -- looking for
            // field names like `conceptRef`/`domainType` to rewrite. A root-declared seed needs no
            // pass here either: root concepts are never namespace-qualified, so there is nothing to
            // resolve.
            if ("seeds".equals(kind)) {
                continue;
            }
            JsonNode array = resolved.get(kind);
            if (array == null || !array.isArray()) {
                continue;
            }
            for (JsonNode member : array) {
                rewritePackLocalConceptReferencesInPlace(member, globalRewriteMaps, ambiguousNames, kind, QUALIFIED_REF_NOOP);
            }
        }
    }

    /**
     * Appends a pack's non-concept model arrays (domainTypes, capabilities, events, flows, …)
     * into the resolved model. Only concepts are namespaced ({@code packId::Name}); other members
     * keep their authored names, and any resulting name collisions surface downstream as the
     * normal duplicate-member error. Without this, pack-defined domain types/capabilities that a
     * pack's concepts depend on would be silently dropped.
     */
    static void mergePackNonConceptArrays(
            String packId,
            ObjectNode packNode,
            ObjectNode resolved,
            Path packFile,
            Map<String, Map<String, String>> rewriteMaps
    )
            throws IOException {
        // Local per-pack pass: ambiguity is structurally impossible (one pack's own map has at most
        // one candidate per bare name), so Map.of() -- see resolveUnqualifiedReferences for the
        // GLOBAL pass, the only one that ever passes a non-empty ambiguousNames.
        mergeQualifiedNonConceptArrays("Pack", packId, packNode, resolved, packFile, rewriteMaps, Map.of(), QUALIFIED_REF_NOOP);
    }

    /** PK-1 (PACK-ROADMAP.md card PK-1): every non-concept member kind is now namespaced
     *  {@code qualifierId::Name} exactly like concepts already were (via
     *  {@link #namespacePackConcept}) -- two packs may each declare a {@code domainType} named
     *  {@code Email} and compose without collision. The duplicate check that used to fire on ANY
     *  cross-pack name clash now only fires WITHIN this single pack/context's own contribution
     *  (two items it declares itself with the same local name) -- a genuine authoring error;
     *  cross-pack collisions are structurally impossible once every name carries its own qualifier.
     *
     *  <p>B20 (S2): generalized over {@link #mergePackNonConceptArrays} -- identical field walk and
     *  local-reference rewriting; {@code kindLabel} only changes the duplicate-member error message,
     *  and {@code qualifiedReferenceValidator} is the D3 import-gate hook (a no-op for packs,
     *  unchanged behavior; the real check for contexts, see {@link #resolveContexts}). */
    private static void mergeQualifiedNonConceptArrays(
            String kindLabel,
            String qualifierId,
            ObjectNode sourceNode,
            ObjectNode resolved,
            Path sourceFile,
            Map<String, Map<String, String>> rewriteMaps,
            Map<String, Map<String, Set<String>>> ambiguousNames,
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
            Set<String> localNamesSeen = new LinkedHashSet<>();
            for (JsonNode item : array) {
                JsonNode rewritten = item.deepCopy();
                if (rewritten.isObject() && rewritten.has("name") && rewritten.get("name").isTextual()) {
                    String localName = rewritten.get("name").asText();
                    if (!localNamesSeen.add(localName.toLowerCase(Locale.ROOT))) {
                        throw error(sourceFile, "/" + key,
                                kindLabel + " '" + qualifierId + "' contributes duplicate " + key + " member '" + localName + "'");
                    }
                    // R5.3: sequences[].name is deliberately NOT namespace-qualified here, unlike
                    // every other kind this loop walks -- see MODEL_ARRAY_KEYS' own "sequences"
                    // comment for why (nextNumber('name') references it as an opaque literal inside
                    // a defaultExpression string the reference-rewriting machinery cannot reach).
                    // The duplicate-within-this-pack check above still applies unconditionally.
                    if (!"sequences".equals(key)) {
                        ((ObjectNode) rewritten).put("name", qualifierId + "::" + localName);
                    }
                }
                if ("seeds".equals(key)) {
                    // R8.8: deliberately bypasses rewritePackLocalConceptReferencesInPlace below --
                    // see rewriteSeedConceptOwnership's own javadoc for why.
                    rewriteSeedConceptOwnership(kindLabel, qualifierId, rewritten, mapFor(rewriteMaps, "concepts"), sourceFile, key);
                } else {
                    rewritePackLocalConceptReferencesInPlace(rewritten, rewriteMaps, ambiguousNames, key, qualifiedReferenceValidator);
                }
                target.add(rewritten);
            }
            resolved.set(key, target);
        }
    }

    /**
     * R8.8: a pack/context's own seed may only target a concept IT OWNS -- rewritten to
     * {@code qualifierId::concept} here (using ONLY this pack/context's own local concept map,
     * built by {@link #buildRewriteMaps} from its own raw JSON, never the whole resolved model),
     * and a concept this pack/context does not declare is a compile error, thrown immediately.
     *
     * <p>Deliberately isolated from {@link #rewritePackLocalConceptReferencesInPlace}/
     * {@link #rewriteKnownMemberReferenceFields} for two reasons: (1) that shared walker is reused
     * UNCHANGED by {@link #resolveUnqualifiedReferences}'s later GLOBAL pass, which would silently
     * "fix" an unowned bare reference the moment some OTHER pack happens to declare a same-named
     * concept -- exactly the hazard {@link #MODEL_ARRAY_KEYS}' own "seeds" comment warns about; (2)
     * that walker also recurses into EVERY nested field looking for known reference field names
     * ({@code conceptRef}/{@code domainType}, rewritten unconditionally at any depth), and a
     * seed's {@code data} is an arbitrary business payload mirroring the TARGET concept's own
     * schema -- a business field genuinely named e.g. "domainType" must never be mistaken for a DSL
     * reference and silently rewritten. Skipping the walker entirely for {@code seeds} protects
     * {@code data} from that hazard for free; nothing else in the seed record shape ({@code
     * alias}/{@code id}/{@code repeatOver}/{@code count}) contains a reference to any other member
     * kind.
     *
     * <p>An already-qualified {@code concept} (containing {@code ::}, e.g. an explicit
     * self-reference or an attempt at a cross-pack one) is deliberately NOT specially unwrapped --
     * {@code localConcepts} is keyed by bare local names only, so it never matches and always fails
     * ownership, the same simple, safe default every other MODEL_ARRAY_KEYS kind's OWN-concept-only
     * fields (documents/selectors) leave to plain bare-name authoring.
     */
    private static void rewriteSeedConceptOwnership(
            String kindLabel,
            String qualifierId,
            JsonNode seedRecord,
            Map<String, String> localConcepts,
            Path sourceFile,
            String key
    ) throws IOException {
        if (!seedRecord.isObject() || !seedRecord.has("concept") || !seedRecord.get("concept").isTextual()) {
            return; // malformed -- pack.schema.json's own required:["concept"] already refuses this file.
        }
        String authored = seedRecord.get("concept").asText();
        String qualified = localConcepts.get(authored);
        if (qualified == null) {
            throw error(sourceFile, "/" + key, kindLabel + " '" + qualifierId + "' declares a seed for concept '"
                    + authored + "', which it does not own -- a seed may only target a concept declared by the "
                    + "SAME " + kindLabel.toLowerCase(Locale.ROOT) + " (declare '" + authored + "' in "
                    + qualifierId + "'s own concepts[], or remove this seed)");
        }
        ((ObjectNode) seedRecord).put("concept", qualified);
    }

    /** B20 (S2): same field walk, plus a hook invoked for every reference already qualified
     *  ({@code prefix::Name}) rather than rewritten -- packs pass a no-op (unrestricted cross-pack
     *  reference, existing behavior, unchanged); {@code resolveContexts} passes D3's import-gate
     *  check. */
    private static void rewritePackLocalConceptReferencesInPlace(
            JsonNode node,
            Map<String, Map<String, String>> rewriteMaps,
            Map<String, Map<String, Set<String>>> ambiguousNames,
            String rootKey,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        if (node == null) {
            return;
        }
        rewritePackLocalConceptReferencesInPlace(node, rewriteMaps, ambiguousNames, rootKey, "", qualifiedReferenceValidator);
    }

    private static void rewritePackLocalConceptReferencesInPlace(
            JsonNode node,
            Map<String, Map<String, String>> rewriteMaps,
            Map<String, Map<String, Set<String>>> ambiguousNames,
            String rootKey,
            String parentKey,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            rewriteKnownMemberReferenceFields(object, rewriteMaps, ambiguousNames, rootKey, parentKey, qualifiedReferenceValidator);
            List<String> fieldNames = new ArrayList<>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                rewritePackLocalConceptReferencesInPlace(
                        object.get(fieldName), rewriteMaps, ambiguousNames, rootKey, fieldName, qualifiedReferenceValidator);
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                rewritePackLocalConceptReferencesInPlace(
                        item, rewriteMaps, ambiguousNames, rootKey, parentKey, qualifiedReferenceValidator);
            }
        }
    }

    private static Map<String, String> mapFor(Map<String, Map<String, String>> rewriteMaps, String kind) {
        return rewriteMaps.getOrDefault(kind, Map.of());
    }

    /** PK-1 step 4 (unqualified resolution + named ambiguity): wraps a per-pack direct map as a
     *  {@link MemberNameResolver}. The LOCAL per-pack/context pass (see {@link #resolvePacks},
     *  {@link #resolveContexts}) always passes an empty {@code ambiguousNames} -- one pack's own map
     *  has at most one candidate per bare name, so ambiguity cannot arise there by construction. The
     *  GLOBAL cross-pack pass ({@link #resolveUnqualifiedReferences}) is the only caller that passes
     *  a non-empty one, naming both candidates in a thrown error the moment an ambiguous bare name
     *  is actually referenced (not merely declared twice -- two packs may each harmlessly declare
     *  the same domainType name forever if nothing ever references it unqualified). */
    private static MemberNameResolver resolverFor(
            Map<String, Map<String, String>> rewriteMaps, Map<String, Map<String, Set<String>>> ambiguousNames, String kind) {
        Map<String, String> map = mapFor(rewriteMaps, kind);
        Map<String, Set<String>> ambiguous = ambiguousNames.getOrDefault(kind, Map.of());
        return bareName -> {
            Set<String> candidates = ambiguous.get(bareName);
            if (candidates != null) {
                throwUnchecked(new IOException("Unqualified reference '" + bareName + "' (kind: " + kind
                        + ") is ambiguous -- " + candidates.size() + " packs each declare a " + kind
                        + " member named '" + bareName + "': " + String.join(", ", candidates)
                        + ". Qualify it explicitly, e.g. '" + candidates.iterator().next() + "'."));
            }
            return map.getOrDefault(bareName, bareName);
        };
    }

    /** PK-1 (PACK-ROADMAP.md card PK-1 step 3): every field across the 17 non-concept kinds that
     *  names ANOTHER member by bare string, found by a dedicated read-only pass over
     *  model.schema.json's $defs (not guessed) -- concept references were already handled before
     *  this card; this adds domainType (concept field.domainType), query (panelDataSource.query,
     *  guidePageGadget.query), procedure (panelAction/panelDataSource/flowStep/procedureStep/
     *  autoPanelDataSource.procedure), flow (panelAction.flow), capability
     *  (binding.capability/orchestrationAction.capability/flowStep.capability/
     *  procedureStep.capability -- capabilities and customCapabilities share one lookup, see
     *  {@link #buildRewriteMaps}), event (lifecycleTransition/orchestrationTrigger/
     *  orchestrationAction/flowStep/procedureStep.event), guidePage (panel.guidePage), and
     *  propertyScope (property.settableAt[], the one array-of-references field found). Concept
     *  domainType rewriting on a FIELD lives here too, not only in {@link #namespacePackFieldRefs},
     *  so it also fires for domainTypes referenced from non-concept contexts (there are none found
     *  today, but the dispatch is kind-driven exactly like every other field here, not concept-only). */
    private static void rewriteKnownMemberReferenceFields(
            ObjectNode object,
            Map<String, Map<String, String>> rewriteMaps,
            Map<String, Map<String, Set<String>>> ambiguousNames,
            String rootKey,
            String parentKey,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        MemberNameResolver conceptRewriteMap = resolverFor(rewriteMaps, ambiguousNames, "concepts");
        rewriteTextField(object, "conceptRef", conceptRewriteMap, qualifiedReferenceValidator);
        rewriteTextField(object, "domainType", resolverFor(rewriteMaps, ambiguousNames, "domainTypes"), qualifiedReferenceValidator);
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
            // PK-1: flowStep.capability/procedure/event -- same "safe at any depth" reasoning as
            // scope above, a flow step's own field name, not shared with any other rootKey's shape.
            rewriteTextField(object, "capability", resolverFor(rewriteMaps, ambiguousNames, "capabilities"), qualifiedReferenceValidator);
            rewriteTextField(object, "procedure", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
            rewriteTextField(object, "event", resolverFor(rewriteMaps, ambiguousNames, "events"), qualifiedReferenceValidator);
        } else if ("procedures".equals(rootKey)) {
            rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
            if ("actionDescriptor".equals(parentKey)) {
                rewriteTextField(object, "sideEffectConcept", conceptRewriteMap, qualifiedReferenceValidator);
                rewriteTextArrayField(object, "affectedConcepts", conceptRewriteMap, qualifiedReferenceValidator);
            }
            // PK-1: procedureStep.query/procedure/capability/event -- same reasoning as flowStep.
            rewriteTextField(object, "query", resolverFor(rewriteMaps, ambiguousNames, "queries"), qualifiedReferenceValidator);
            rewriteTextField(object, "procedure", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
            rewriteTextField(object, "capability", resolverFor(rewriteMaps, ambiguousNames, "capabilities"), qualifiedReferenceValidator);
            rewriteTextField(object, "event", resolverFor(rewriteMaps, ambiguousNames, "events"), qualifiedReferenceValidator);
        } else if ("panels".equals(rootKey)) {
            if ("dataSources".equals(parentKey) || "actions".equals(parentKey)) {
                rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
            }
            if ("dataSources".equals(parentKey)) {
                rewriteTextField(object, "query", resolverFor(rewriteMaps, ambiguousNames, "queries"), qualifiedReferenceValidator);
                rewriteTextField(object, "procedure", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
            } else if ("actions".equals(parentKey)) {
                rewriteTextField(object, "procedure", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
                rewriteTextField(object, "flow", resolverFor(rewriteMaps, ambiguousNames, "flows"), qualifiedReferenceValidator);
            } else if (parentKey.isBlank()) {
                // The panel object itself (not a nested dataSource/action) -- guidePage names a
                // guidePages member.
                rewriteTextField(object, "guidePage", resolverFor(rewriteMaps, ambiguousNames, "guidePages"), qualifiedReferenceValidator);
            }
        } else if ("autoPanels".equals(rootKey)) {
            // PACK-11: an autoPanel names its concept or its aggregate at the top level, and its
            // transaction/selection surfaces name procedures through hooks and actions. Before this
            // block a pack-contributed autoPanel composed successfully and then failed validation
            // with "concept not found: Label" -- the concept IS in the model, as `labeling::Label`.
            if (parentKey.isBlank()) {
                rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
                rewriteTextField(object, "aggregate", resolverFor(rewriteMaps, ambiguousNames, "aggregates"), qualifiedReferenceValidator);
            }
            // `procedure` on a surface's dataSource, on each workbench action, and on every
            // transaction hook. Rewritten at any depth for the same reason flowStep.scope is: it is
            // this shape's own field name and means nothing else inside an autoPanel.
            rewriteTextField(object, "procedure", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
            rewriteTextField(object, "onLoad", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
            rewriteTextField(object, "onFieldChange", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
            rewriteTextField(object, "beforeAction", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
            rewriteTextField(object, "onValidate", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
            rewriteTextField(object, "onCommit", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
            rewriteTextField(object, "afterAction", resolverFor(rewriteMaps, ambiguousNames, "procedures"), qualifiedReferenceValidator);
            rewriteTextField(object, "panel", resolverFor(rewriteMaps, ambiguousNames, "panels"), qualifiedReferenceValidator);
        } else if ("guidePages".equals(rootKey)) {
            // PACK-11: every chart/KPI gadget binds to a named query.
            rewriteTextField(object, "query", resolverFor(rewriteMaps, ambiguousNames, "queries"), qualifiedReferenceValidator);
        } else if ("selectors".equals(rootKey) || "documents".equals(rootKey)) {
            // PACK-11: both name exactly one concept, on the member object itself.
            if (parentKey.isBlank()) {
                rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
            }
        } else if ("aggregates".equals(rootKey)) {
            // PACK-11: the aggregate root, plus each collection's own concept (nested arbitrarily
            // deep -- an aggregateCollection may contain further collections).
            if (parentKey.isBlank()) {
                rewriteTextField(object, "root", conceptRewriteMap, qualifiedReferenceValidator);
            }
            rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
        } else if ("orchestrations".equals(rootKey) || "orchestrationRules".equals(rootKey)) {
            if ("action".equals(parentKey) || "actions".equals(parentKey)) {
                rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
                rewriteTextField(object, "targetConcept", conceptRewriteMap, qualifiedReferenceValidator);
                rewriteTextField(object, "capability", resolverFor(rewriteMaps, ambiguousNames, "capabilities"), qualifiedReferenceValidator);
                rewriteTextField(object, "event", resolverFor(rewriteMaps, ambiguousNames, "events"), qualifiedReferenceValidator);
            } else if ("trigger".equals(parentKey)) {
                rewriteTextField(object, "event", resolverFor(rewriteMaps, ambiguousNames, "events"), qualifiedReferenceValidator);
            }
        } else if ("ruleProfiles".equals(rootKey)) {
            rewriteTextOrArrayField(object, "appliesTo", conceptRewriteMap, qualifiedReferenceValidator);
        } else if ("events".equals(rootKey)) {
            rewriteTextField(object, "concept", conceptRewriteMap, qualifiedReferenceValidator);
            rewriteTextField(object, "conceptName", conceptRewriteMap, qualifiedReferenceValidator);
        } else if ("webhooks".equals(rootKey) && parentKey.isBlank()) {
            // R6.2 (the "PACK-11 fifth place"): a webhook names the event it publishes on a
            // verified request. `source` itself is deliberately NOT rewritten here -- unlike every
            // other MODEL_ARRAY_KEYS member, a webhook's identity is a wire path segment a third
            // party posts to; qualifying it would make POST /api/hooks/{source} depend on pack
            // composition order, which the external contract cannot tolerate (see WebhookAst).
            rewriteTextField(object, "eventName", resolverFor(rewriteMaps, ambiguousNames, "events"), qualifiedReferenceValidator);
        } else if ("bindings".equals(rootKey) && parentKey.isBlank()) {
            rewriteTextField(object, "capability", resolverFor(rewriteMaps, ambiguousNames, "capabilities"), qualifiedReferenceValidator);
        } else if ("properties".equals(rootKey) && parentKey.isBlank()) {
            rewriteTextArrayField(object, "settableAt", resolverFor(rewriteMaps, ambiguousNames, "propertyScopes"), qualifiedReferenceValidator);
        } else if ("concepts".equals(rootKey) && "transitions".equals(parentKey)) {
            // concept.lifecycle.transitions[].event -- a concept's own lifecycle can name an event.
            rewriteTextField(object, "event", resolverFor(rewriteMaps, ambiguousNames, "events"), qualifiedReferenceValidator);
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
            MemberNameResolver resolver,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        JsonNode value = object.get(fieldName);
        if (value == null) {
            return;
        }
        if (value.isTextual()) {
            rewriteTextField(object, fieldName, resolver, qualifiedReferenceValidator);
        } else if (value.isArray()) {
            rewriteTextArrayField(object, fieldName, resolver, qualifiedReferenceValidator);
        }
    }

    private static void rewriteTextArrayField(
            ObjectNode object,
            String fieldName,
            MemberNameResolver resolver,
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
                String replacement = rewriteConceptName(item.asText(), resolver, qualifiedReferenceValidator);
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
            MemberNameResolver resolver,
            QualifiedReferenceValidator qualifiedReferenceValidator
    ) {
        JsonNode value = object.get(fieldName);
        if (value == null || !value.isTextual()) {
            return;
        }
        String replacement = rewriteConceptName(value.asText(), resolver, qualifiedReferenceValidator);
        if (!replacement.equals(value.asText())) {
            object.put(fieldName, replacement);
        }
    }

    private static String rewriteConceptName(
            String authored, MemberNameResolver resolver, QualifiedReferenceValidator qualifiedReferenceValidator) {
        if (authored == null) {
            return authored;
        }
        if (authored.contains("::")) {
            qualifiedReferenceValidator.validate(authored);
            return authored;
        }
        return resolver.resolve(authored);
    }

    /** B20 (S2): a no-op qualified-reference check -- packs today have no import restriction, so an
     *  already-qualified reference (cross-pack or, since D1 reuses the same {@code ::} separator,
     *  cross-context) is left completely unvalidated when reached through a pack's own merge path. */
    private static final QualifiedReferenceValidator QUALIFIED_REF_NOOP = qualifiedName -> { };

    @FunctionalInterface
    private interface QualifiedReferenceValidator {
        void validate(String qualifiedName);
    }

    /** PK-1 step 4: resolves a bare (unqualified) member name to its qualified form.
     *  {@link #resolverFor} wraps a single pack/context's own direct map (unambiguous by
     *  construction). {@link #resolveUnqualifiedReferences}'s global pass instead builds a resolver
     *  that can throw a named ambiguity error when two or more packs each provide the same bare
     *  name and neither is locally in scope. */
    @FunctionalInterface
    private interface MemberNameResolver {
        String resolve(String bareName);
    }

    static Path resolvePackPath(String ref, Path modelFile, Path rootDirectory) throws IOException {
        return resolveJsonRefUnderRoot(ref, modelFile, rootDirectory, rootDirectory, "Pack $ref");
    }

    static ObjectNode loadPackJson(Path packFile, ResolutionState state) throws IOException {
        JsonNode node = readJson(packFile);
        if (!node.isObject()) {
            throw error(packFile, "$", "Pack file must be a JSON object");
        }
        PACK_SCHEMA_VALIDATOR.validate(node, packFile.toString());
        state.includedFiles.add(packFile);
        state.seenIncludedFiles.add(packFile);
        return (ObjectNode) node;
    }

    ObjectNode resolvePackRoot(
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
        // PK-3: survive packs[]/requires through verbatim, exactly like metadata above -- neither
        // is in PACK_ROOT_SCALAR_KEYS or MODEL_ARRAY_KEYS, so without this they schema-validate and
        // then silently vanish (the same "twin forgotten" hazard REG-108 already found for
        // roles/propertyScopes/properties). resolvePackRoot itself stays non-recursive: nothing here
        // resolves a dependency's own file -- that recursion is PackDependencyGraphWalker's job, one
        // layer up, which is also where packs[]/requires actually get consumed.
        if (rawPack.has("packs")) {
            resolvedPack.set("packs", rawPack.get("packs").deepCopy());
        }
        if (rawPack.has("requires")) {
            resolvedPack.set("requires", rawPack.get("requires").deepCopy());
        }
        // PK-4 Stage C/D: same hazard, same fix -- migrations is neither a PACK_ROOT_SCALAR_KEYS
        // scalar nor a MODEL_ARRAY_KEYS collection (it is an object keyed by version-range strings,
        // not an array), so without this line it schema-validates and then silently vanishes exactly
        // like packs[]/requires did before the comment above was written. PackDependencyGraphWalker's
        // applyMigrationChains is the actual consumer, one layer up.
        if (rawPack.has("migrations")) {
            resolvedPack.set("migrations", rawPack.get("migrations").deepCopy());
        }
        // PACK-10/R8.11: first-class `extends` keyword -- same "schema-validates then silently
        // vanishes" hazard as packs[]/requires/migrations before them. PackExtensionComposer
        // .readExtensionTarget() is the actual consumer, one layer up.
        if (rawPack.has("extends")) {
            resolvedPack.set("extends", rawPack.get("extends").deepCopy());
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

    static void mergePackConcepts(
            String packId,
            ObjectNode packNode,
            ObjectNode resolved,
            Path packFile,
            Map<String, Map<String, String>> rewriteMaps
    )
            throws IOException {
        mergeQualifiedConcepts("Pack", packId, packNode, resolved, packFile, rewriteMaps);
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
            Map<String, Map<String, String>> rewriteMaps
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
                ObjectNode namespaced = namespacePackConcept(qualifierId, (ObjectNode) concept, rewriteMaps);
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
            Map<String, Map<String, String>> rewriteMaps
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
                        ? namespacePackFieldRefs((ObjectNode) field, rewriteMaps)
                        : field.deepCopy());
            }
            out.set("fields", newFields);
        }
        return out;
    }

    /** PK-1: a concept field's {@code ref}/{@code reference} name another concept (unchanged from
     *  before this card); {@code domainType} names a domainTypes member -- new in this card, the
     *  same "a pack's own custom domain type must resolve after namespacing" gap R6's sibling bugs
     *  came from. */
    private static ObjectNode namespacePackFieldRefs(
            ObjectNode field,
            Map<String, Map<String, String>> rewriteMaps
    ) {
        Map<String, String> conceptRewriteMap = mapFor(rewriteMaps, "concepts");
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
        if (out.has("domainType") && out.get("domainType").isTextual()) {
            String target = out.get("domainType").asText();
            String rewritten = mapFor(rewriteMaps, "domainTypes").get(target);
            if (rewritten != null) {
                out.put("domainType", rewritten);
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
                // an "as" alias ({"$ref": "...", "as": "..."}) and, since PK-3, "allowSideBySide"
                // (ONLY at /packs/N -- see model.schema.json's packRef); a top-level context
                // declaration (B20, S2) additionally carries a required "name"
                // ({"$ref": "...", "name": "..."}) and, since S8 Wave 4 (ADR-0011 D4's v2 opt-in),
                // an optional "physicallyIsolate", but ONLY at /contexts/N -- everywhere else
                // "name"/"physicallyIsolate"/"allowSideBySide" alongside a bare $ref stays
                // malformed, same as any other stray key would.
                boolean isContextDeclaration = path.matches("^\\$/contexts/\\d+$");
                boolean isPackDeclaration = path.matches("^\\$/packs/\\d+$");
                boolean onlyRefAndOptionalAlias = node.size() == 1
                        || (node.size() == 2 && node.has("as"))
                        || (isContextDeclaration && node.size() == 2 && node.has("name"))
                        || (isContextDeclaration && node.size() == 3 && node.has("name")
                                && node.has("physicallyIsolate"))
                        || (isPackDeclaration && node.size() == 2 && node.has("allowSideBySide"))
                        || (isPackDeclaration && node.size() == 3 && node.has("as")
                                && node.has("allowSideBySide"));
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
        // R8.1: a cache-resident (remote) pack's own directory lives entirely OUTSIDE the app's
        // model root by construction -- every fragment inside a multi-file remote pack therefore
        // tripped "escapes the model root" before it could ever be reached, even though it never
        // left the pack's own directory. Detected by asking whether the pack's own containment
        // directory is itself outside the root: if so, this call is resolving a REMOTE pack's
        // internal $ref, and the model-root boundary simply does not apply to it -- the
        // containment-directory check just below is what still stops a fragment reaching outside
        // its own pack (e.g. path traversal into a sibling cache entry). A LOCAL pack's containment
        // directory is always under the model root, so this changes nothing for it.
        boolean containedByAPackOutsideTheRoot = containmentDirectory != null && rootDirectory != null
                && !containmentDirectory.startsWith(rootDirectory);
        if (!containedByAPackOutsideTheRoot && rootDirectory != null && !real.startsWith(rootDirectory)) {
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

    // Package-private: PackDependencyGraphWalker (PK-3) emits its own allowSideBySide warning
    // through the same diagnostic shape every other resolver warning already uses.
    static ValidationDiagnostic diagnostic(
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

    // Package-private: PackDependencyGraphWalker (PK-3) is a sibling class in this package and
    // needs the same error-formatting convention every other resolver error uses.
    static IOException error(Path sourceFile, String path, String message) {
        return new IOException(sourceFile + " " + path + ": " + message);
    }

    private static String escapePointer(String value) {
        return value == null ? "" : value.replace("~", "~0").replace("/", "~1");
    }

    // Package-private: PackDependencyGraphWalker (PK-3) reads pack scalar fields the same way.
    static String textOrBlank(JsonNode node) {
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

    // Package-private (not private): PackDependencyGraphWalker (PK-3) is a sibling class in this
    // package that shares one resolution pass's state (physicalQualifierByConceptName, warnings,
    // rootDirectory) with ModelSourceResolver -- same reasoning as error()/textOrBlank() above.
    static final class ResolutionState {
        final Path rootRealPath;
        final Path rootDirectory;
        final List<Path> includedFiles = new ArrayList<>();
        final Set<Path> seenIncludedFiles = new LinkedHashSet<>();
        final Map<String, Path> provenance = new HashMap<>();
        final List<ValidationDiagnostic> diagnostics = new ArrayList<>();
        final List<ValidationDiagnostic> warnings = new ArrayList<>();
        final Map<String, String> physicalQualifierByConceptName = new LinkedHashMap<>();
        /** PK-4 Stage D: every packId whose OWN pack.json declares a non-empty {@code migrations}
         *  chain, populated by {@link PackDependencyGraphWalker#run} with a freshly-built lock entry
         *  (resolvedVersion/digest/sourcePath already correct for THIS resolve; migratedVersion left
         *  at whatever was already on disk -- the caller, after a full successful generate, is the
         *  one that advances it, see {@code GeneratorMain}). Empty for the overwhelming majority of
         *  models, which never touch a pack with a migration chain -- a real, deliberate scoping
         *  decision, not an oversight: writing npdev.lock for every pack import regardless of whether
         *  it ever bumps past its first version would be a visible behavior change to every existing
         *  app, not just the ones this card's feature actually applies to. */
        final Map<String, com.npdev.dsl.v1.pack.PackLockFile.LockedPack> migrationTrackedPacks = new LinkedHashMap<>();
        /** PACK-2: pack-attribution facts for every pack-contributed member, keyed first by
         *  {@link #MODEL_ARRAY_KEYS} kind (e.g. "concepts", "queries") then by the member's
         *  already-qualified ({@code packId::Name}) name -- populated by {@link #recordOrigin}.
         *  Absent for any root- or context-declared member (never pack-contributed), which is
         *  exactly how {@code JsonModelParser} distinguishes "no origin" from "pack origin". */
        final Map<String, Map<String, PackOrigin>> originByQualifiedMemberName = new LinkedHashMap<>();

        ResolutionState(Path rootRealPath, Path rootDirectory) {
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
