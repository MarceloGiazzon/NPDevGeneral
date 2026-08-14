package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.pack.MinimalVersionSelector;
import com.npdev.dsl.v1.pack.NetworkPolicy;
import com.npdev.dsl.v1.pack.PackCache;
import com.npdev.dsl.v1.pack.PackCoordinate;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.pack.PackMigrationChain;
import com.npdev.dsl.v1.pack.PackMigrationChainSynthesizer;
import com.npdev.dsl.v1.pack.PackMigrationComposer;
import com.npdev.dsl.v1.pack.PackVersion;
import com.npdev.dsl.v1.pack.PackVersionConstraint;
import com.npdev.dsl.v1.pack.RemotePackFetcher;
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
    private final List<ModelSourceResolver.PackRequirementEntry> packRequirements = new ArrayList<>();
    /** PK-5: real packId -> the exact {@code packs[].from} coordinate it was resolved from, for
     *  every DIRECT import that used {@code from} instead of {@code $ref}. Empty for every packId
     *  resolved the pre-PK-5 way (local {@code $ref}, including every transitive dependency --
     *  {@code from} is direct-app-import-only in this slice; see {@link #defaultPackFile}'s own
     *  doc). Drives {@link #toLockEntries} / {@link #checkLock}'s sourcePath computation (a remote
     *  pack's file lives in the shared {@link PackCache}, which can be on a different filesystem
     *  root than the app -- {@link Path#relativize} across drive roots throws on Windows). */
    private final Map<String, String> fromByPackId = new LinkedHashMap<>();
    private boolean anyRemoteDirectImport = false;
    private final NetworkPolicy networkPolicy;

    private PackDependencyGraphWalker(
            ModelSourceResolver resolver,
            ModelSourceResolver.ResolutionState state,
            String rootDslVersion,
            NetworkPolicy networkPolicy
    ) {
        this.resolver = resolver;
        this.state = state;
        this.rootDirectory = state.rootDirectory;
        this.rootDslVersion = rootDslVersion;
        this.networkPolicy = networkPolicy;
    }

    static List<ModelSourceResolver.PackRequirementEntry> resolve(
            ModelSourceResolver resolver,
            ArrayNode packsNode,
            ObjectNode resolved,
            Path modelFile,
            ModelSourceResolver.ResolutionState state
    ) throws IOException {
        return resolve(resolver, packsNode, resolved, modelFile, state, true);
    }

    /**
     * @param enforceLock false for the CLI's own pack add/update/why commands, which must be able
     *                    to run discovery+MVS BEFORE a lock exists (that's the whole point of
     *                    `add`/`update` -- they're what WRITES the lock in the first place) or
     *                    without caring about lock staleness at all (`why` is always a fresh live
     *                    computation, per the card's own spec). Every other caller (real model
     *                    resolution -- generation, validation) always enforces it.
     */
    static List<ModelSourceResolver.PackRequirementEntry> resolve(
            ModelSourceResolver resolver,
            ArrayNode packsNode,
            ObjectNode resolved,
            Path modelFile,
            ModelSourceResolver.ResolutionState state,
            boolean enforceLock
    ) throws IOException {
        String rootDslVersion = ModelSourceResolver.textOrBlank(resolved.get("dslVersion"));
        // PK-5 step 2 ("Distribution", PACK-ROADMAP.md card PK-5): this is the ONLY call site
        // ModelSourceResolver.resolvePacks uses (the real generate/validate path), and
        // NetworkPolicy.DENIED is hardcoded here -- not threaded in from any caller -- so a future
        // edit cannot quietly reintroduce a fetch on this path without also having to change this
        // literal line. See NetworkPolicy's own doc for the full guarantee.
        PackDependencyGraphWalker walker = new PackDependencyGraphWalker(resolver, state, rootDslVersion, NetworkPolicy.DENIED);
        walker.run(packsNode, resolved, modelFile, enforceLock);
        return walker.packRequirements;
    }

    /** CLI-only entry point (pack add/update/list/why): returns the walker instance itself, so the
     *  caller can pull lock entries / why-requirements / resolved packIds off it -- never enforces
     *  the lock (see {@link #resolve(ModelSourceResolver, ArrayNode, ObjectNode, Path, ModelSourceResolver.ResolutionState, boolean)}).
     *  {@code networkPolicy} is the caller's choice, unlike the real-resolution overload above --
     *  {@code pack add}/{@code update} pass {@link NetworkPolicy#ALLOWED} (they are the one phase
     *  that may fetch); {@code pack list}/{@code why} pass {@link NetworkPolicy#DENIED} (neither
     *  ever fetches -- they only read an existing lock or dry-run the local-file graph). */
    static PackDependencyGraphWalker resolveForCli(
            ModelSourceResolver resolver,
            ArrayNode packsNode,
            ObjectNode resolved,
            Path modelFile,
            ModelSourceResolver.ResolutionState state,
            NetworkPolicy networkPolicy
    ) throws IOException {
        String rootDslVersion = ModelSourceResolver.textOrBlank(resolved.get("dslVersion"));
        PackDependencyGraphWalker walker = new PackDependencyGraphWalker(resolver, state, rootDslVersion, networkPolicy);
        walker.run(packsNode, resolved, modelFile, false);
        return walker;
    }

    /** CLI-only accessor (pack add/update/list/why): the graph {@link #run} just resolved, keyed
     *  by packId. Only meaningful after {@code run} has completed. */
    Map<String, PackLockFile.LockedPack> toLockEntries() throws IOException {
        Map<String, PackLockFile.LockedPack> entries = new LinkedHashMap<>();
        for (String packId : packNodeById.keySet()) {
            Path packFile = packFileById.get(packId);
            String version = ModelSourceResolver.textOrBlank(packNodeById.get(packId).get("version"));
            String from = fromByPackId.getOrDefault(packId, "");
            entries.put(packId, new PackLockFile.LockedPack(
                    version, digestFor(packFile, from), sourcePathFor(packFile, from), "", from));
        }
        return entries;
    }

    /** PK-5: a LOCAL pack's sourcePath is (as before) the app-relative path to its {@code $ref}
     *  file. A REMOTE pack's file lives in the shared, machine-wide {@link PackCache} -- which can
     *  sit on an entirely different filesystem root than the app (a different drive letter on
     *  Windows, in particular) -- so {@code rootDirectory.relativize(packFile)} would throw
     *  {@code IllegalArgumentException} there; record the cache path's own absolute form instead,
     *  informational only (the digest, not sourcePath, is what generate actually re-verifies). */
    private String sourcePathFor(Path packFile, String from) {
        return from.isEmpty()
                ? rootDirectory.relativize(packFile).toString().replace('\\', '/')
                : packFile.toAbsolutePath().toString();
    }

    /**
     * PK-5 (post-review fix): a LOCAL pack's digest is (as before) {@link PackLockFile#sha256} over
     * its own {@code pack.json} bytes only. A REMOTE pack's digest MUST instead be the exact string
     * {@link PackCache} used as that entry's directory name -- which, since {@link PackCache#store}
     * now hashes the WHOLE fetched tree (post-review fix, not just {@code pack.json}), is no longer
     * the same value {@code PackLockFile.sha256(packFile)} would compute over the single cached
     * {@code pack.json} file. Recomputing a second, different hash here and writing THAT into
     * {@code npdev.lock} would desynchronize the lock's {@code digest} field from the cache
     * directory it is supposed to key into -- the DENIED-path lookup in {@link
     * #resolveRemotePackFile} would then try to {@code PackCache.read} a digest that names no real
     * cache entry. The one value guaranteed to always agree with the cache directory's own name,
     * with zero risk of drift and no extra tree walk, is the directory name itself:
     * {@code packFile}'s parent IS {@code <cacheRoot>/sha256/<digest>}, by construction of {@link
     * PackCache#entryDir}.
     */
    private String digestFor(Path packFile, String from) throws IOException {
        if (from.isEmpty()) {
            return PackLockFile.sha256(packFile);
        }
        Path entryDir = packFile.getParent();
        String hex = entryDir == null || entryDir.getFileName() == null ? "" : entryDir.getFileName().toString();
        return "sha256:" + hex;
    }

    /** CLI-only accessor ({@code npdev pack why}): every constraint any pack in the graph placed
     *  on {@code packId}, plus which one determined the final selection (the highest minimum). */
    List<MinimalVersionSelector.Requirement> requirementsFor(String packId) {
        return requirementsByPackId.getOrDefault(packId, List.of());
    }

    /** CLI-only accessor: every packId the graph resolved (for {@code npdev pack list}'s live
     *  dry-run when no lock exists yet). */
    Set<String> resolvedPackIds() {
        return packNodeById.keySet();
    }

    private record DirectImport(
            String path, Path packFile, ObjectNode packNode, String realPackId, String qualifier,
            boolean allowSideBySide, boolean hasAlias, String from) {
    }

    private void run(ArrayNode packsNode, ObjectNode resolved, Path modelFile, boolean enforceLock) throws IOException {
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
            JsonNode fromNode = packRef.get("from");
            boolean hasRef = refNode != null && refNode.isTextual() && !refNode.asText("").isBlank();
            boolean hasFrom = fromNode != null && fromNode.isTextual() && !fromNode.asText("").isBlank();
            if (hasRef == hasFrom) {
                throw ModelSourceResolver.error(modelFile, path, "Pack import must declare exactly one of "
                        + "'$ref' (a local file) or 'from' (a remote coordinate, PK-5) -- never both, never neither");
            }

            Path packFile;
            String fromCoordinate = "";
            if (hasRef) {
                packFile = ModelSourceResolver.resolvePackPath(refNode.asText(), modelFile, rootDirectory);
            } else {
                fromCoordinate = fromNode.asText().trim();
                packFile = resolveRemotePackFile(fromCoordinate, path, modelFile);
                anyRemoteDirectImport = true;
            }
            ObjectNode packNode = loadAndResolvePack(packFile, 1);
            String realPackId = ModelSourceResolver.textOrBlank(packNode.get("pack"));
            if (!fromCoordinate.isEmpty()) {
                fromByPackId.put(realPackId, fromCoordinate);
            }

            String qualifier = ModelSourceResolver.resolvePackNamespace(packRef, packNode, modelFile, packFile, path);
            String namespaceKey = qualifier.toLowerCase(Locale.ROOT);
            if (!usedNamespaces.add(namespaceKey)) {
                throw ModelSourceResolver.error(modelFile, path + "/as", "Duplicate pack namespace alias: " + qualifier);
            }
            boolean allowSideBySide = packRef.has("allowSideBySide") && packRef.get("allowSideBySide").asBoolean(false);
            directImports.add(new DirectImport(
                    path, packFile, packNode, realPackId, qualifier, allowSideBySide, packRef.has("as"), fromCoordinate));
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
        if (enforceLock) {
            checkLock(modelFile);
            applyMigrationChains(modelFile);
        }

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
     * PK-5 steps 1+2+4: resolves a DIRECT import's {@code from} coordinate to a local file WITHOUT
     * ever touching the network on the generate/validate path -- this is the whole two-phase
     * design ("Distribution", {@code PACK-ROADMAP.md} card PK-5 step 2).
     *
     * <p>When {@link #networkPolicy} is {@link NetworkPolicy#ALLOWED} (the CLI's {@code pack add}/
     * {@code update} only), actually fetches via {@link RemotePackFetcher} into the shared {@link
     * PackCache} and returns the freshly cached, digest-verified {@code pack.json}.
     *
     * <p>When DENIED (every other caller, including every real {@code npdev generate}/{@code
     * validate}), never calls {@link RemotePackFetcher} at all -- instead looks up {@code
     * npdev.lock} for an entry whose OWN recorded {@link PackLockFile.LockedPack#from} string
     * exactly matches this coordinate. (The packId isn't known yet at this point -- it lives
     * inside the very file this method is trying to locate -- so the coordinate string itself,
     * not the packId, has to be the lookup key.) Reads the match through {@link PackCache#read},
     * which re-verifies the cached bytes' digest on every call: a hard refusal, never a fetch, if
     * the lock has no matching entry or the cache entry is missing/corrupt.
     */
    private Path resolveRemotePackFile(String fromCoordinate, String path, Path modelFile) throws IOException {
        PackCoordinate coordinate;
        try {
            coordinate = PackCoordinate.parse(fromCoordinate);
        } catch (IllegalArgumentException malformed) {
            throw ModelSourceResolver.error(modelFile, path + "/from", malformed.getMessage());
        }

        PackCache cache = PackCache.atDefaultRoot();
        if (networkPolicy.isAllowed()) {
            RemotePackFetcher.FetchResult fetched = RemotePackFetcher.fetch(coordinate, networkPolicy, cache);
            return fetched.packJson();
        }

        if (!PackLockFile.exists(rootDirectory)) {
            throw ModelSourceResolver.error(modelFile, path + "/from", "pack 'from: " + fromCoordinate
                    + "' has no " + PackLockFile.FILE_NAME + " entry (no lock file exists at all) -- run "
                    + "'npdev pack add' first; npdev generate never touches the network");
        }
        PackLockFile lock = PackLockFile.read(rootDirectory);
        for (PackLockFile.LockedPack locked : lock.packs().values()) {
            if (fromCoordinate.equals(locked.from())) {
                String digest = locked.digest();
                String digestHex = digest.startsWith("sha256:") ? digest.substring("sha256:".length()) : digest;
                return cache.read(digestHex);
            }
        }
        throw ModelSourceResolver.error(modelFile, path + "/from", "pack 'from: " + fromCoordinate
                + "' is not in " + PackLockFile.FILE_NAME + " -- run 'npdev pack add' first; npdev generate "
                + "never touches the network");
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
        JsonNode requires = packNode.get("requires");
        if (requires != null && requires.isObject() && !requires.isEmpty()) {
            packRequirements.add(new ModelSourceResolver.PackRequirementEntry(packId, pathToThisPack, requires));
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

    /**
     * PK-3: "generation reads the lock, not the constraints". Only enforced when the resolved
     * graph actually has a transitive dependency (at least one pack declares its own {@code
     * packs[]}) -- every existing pack/app in this repo today has none, so this is a no-op for
     * all of them, matching the card's own "still local files only" v1 scope. When enforced: the
     * live discovery/MVS pass above (never the lock) is what actually picks versions and merges
     * content -- this method ONLY compares that live result against what's committed, refusing on
     * any drift rather than silently accepting a stale lock.
     */
    private void checkLock(Path modelFile) throws IOException {
        boolean anyTransitiveDependency = packNodeById.values().stream()
                .anyMatch(node -> node.get("packs") != null && node.get("packs").isArray() && !node.get("packs").isEmpty());
        // PK-5: a from-based direct import ALREADY required (inside resolveRemotePackFile, above,
        // called from run()'s per-packRef loop, well before this method) that npdev.lock exist and
        // contain a matching entry -- so by the time this runs, the lock is known to exist. This
        // extra pass adds defense-in-depth (the packId-SET-equality check below, resolvedVersion
        // drift) that resolveRemotePackFile's own narrower per-coordinate check doesn't cover.
        if (!anyTransitiveDependency && !anyRemoteDirectImport) {
            return;
        }
        if (!PackLockFile.exists(rootDirectory)) {
            throw ModelSourceResolver.error(modelFile, "/packs", "this model's pack graph has transitive "
                    + "dependencies but no " + PackLockFile.FILE_NAME + " exists -- run 'npdev pack add' or "
                    + "'npdev pack update'");
        }
        PackLockFile lock = PackLockFile.read(rootDirectory);
        if (!lock.packs().keySet().equals(packNodeById.keySet())) {
            throw ModelSourceResolver.error(modelFile, "/packs", PackLockFile.FILE_NAME + " is stale: its resolved "
                    + "pack set (" + lock.packs().keySet() + ") does not match the live graph ("
                    + packNodeById.keySet() + ") -- run 'npdev pack update'");
        }
        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, PackLockFile.LockedPack> entry : lock.packs().entrySet()) {
            String packId = entry.getKey();
            PackLockFile.LockedPack locked = entry.getValue();
            ObjectNode packNode = packNodeById.get(packId);
            Path packFile = packFileById.get(packId);
            String liveVersion = ModelSourceResolver.textOrBlank(packNode.get("version"));
            String from = fromByPackId.getOrDefault(packId, "");
            String liveSourcePath = sourcePathFor(packFile, from);
            String liveDigest;
            try {
                liveDigest = digestFor(packFile, from);
            } catch (IOException unreadable) {
                stale.add(packId + " (its locked sourcePath " + locked.sourcePath() + " could not be read: "
                        + unreadable.getMessage() + ")");
                continue;
            }
            if (!locked.resolvedVersion().equals(liveVersion)
                    || !locked.sourcePath().equals(liveSourcePath)
                    || !locked.digest().equals(liveDigest)) {
                stale.add(packId + " (locked " + locked.resolvedVersion() + " @ " + locked.sourcePath()
                        + ", live " + liveVersion + " @ " + liveSourcePath + ")");
            }
        }
        if (!stale.isEmpty()) {
            throw ModelSourceResolver.error(modelFile, "/packs", PackLockFile.FILE_NAME + " is stale for: "
                    + String.join("; ", stale) + " -- run 'npdev pack update'");
        }
    }

    /**
     * PK-4 Stage D: for every resolved pack that declares a non-empty {@code migrations} chain,
     * composes whatever hops separate its last-generated version ({@code npdev.lock}'s
     * {@code migratedVersion}, or the pack's own current version if never generated before -- an
     * empty, correct no-op range) from its current version, and replaces {@link #packNodeById}'s
     * entry with a copy carrying the synthesized {@code renamedFrom} markers -- BEFORE the merge
     * loop in {@link #run} reads it. A pack with no migration chain at all (every pack in this repo
     * today) is completely untouched: {@code chain.hops()} is empty and this method skips it
     * entirely, matching PK-3's own "no packs[] = untouched" no-op regression discipline.
     *
     * <p>Only ever called with {@code enforceLock=true} (the real generate path) -- {@code
     * resolveForCli}'s own merge output is never consumed by its callers, so there is no reason to
     * risk a migration-chain refusal aborting a {@code pack add/update/why} invocation that never
     * needed the merged model in the first place.
     */
    private void applyMigrationChains(Path modelFile) throws IOException {
        PackLockFile existingLock = PackLockFile.exists(rootDirectory) ? PackLockFile.read(rootDirectory) : null;
        Map<String, PackLockFile.LockedPack> freshEntries = null;

        for (String packId : packNodeById.keySet()) {
            ObjectNode packNode = packNodeById.get(packId);
            PackMigrationChain chain = PackMigrationChain.parse(packNode.get("migrations"));
            if (chain.hops().isEmpty()) {
                continue;
            }

            PackVersion toVersion;
            try {
                toVersion = PackVersion.parse(ModelSourceResolver.textOrBlank(packNode.get("version")));
            } catch (IllegalArgumentException malformed) {
                throw ModelSourceResolver.error(modelFile, "/packs", "Pack '" + packId + "': " + malformed.getMessage());
            }
            String migratedVersionRaw = existingLock != null && existingLock.packs().containsKey(packId)
                    ? existingLock.packs().get(packId).migratedVersion()
                    : "";
            // Untracked (never generated before, or generated back when this pack had no migrations
            // key yet) does NOT mean "already current" -- that would silently skip replaying real
            // history for a pre-existing database sitting at the pack's original version, the exact
            // failure this card exists to prevent. The only version an untracked database could
            // possibly be at is the chain's own provably-first-ever version (see
            // PackMigrationChain.earliestFromVersion's own doc for why this is safe for a fresh
            // install too).
            PackVersion fromVersion = migratedVersionRaw.isBlank()
                    ? chain.earliestFromVersion()
                    : PackVersion.parse(migratedVersionRaw);

            PackMigrationComposer.Result result = PackMigrationComposer.compose(packId, chain, fromVersion, toVersion);
            if (result instanceof PackMigrationComposer.Refused refused) {
                throw ModelSourceResolver.error(modelFile, "/packs", refused.message());
            }
            PackMigrationComposer.ComposedRenames composed = ((PackMigrationComposer.Composed) result).renames();
            // PK-2 bakes a pack's own major version into every one of its concepts' physical table
            // names (recordPhysicalQualifiers, below) -- and since a rename is BREAKING (Stage A) and
            // BREAKING requires at least a major bump (Stage B), every rename-bearing hop ALSO crosses
            // a major-version boundary, which by itself changes the physical table name regardless of
            // whether any field/concept was renamed. A field-level renamedFrom alone is invisible to
            // the schema engine when the TABLE it lives on looks like an entirely different table.
            String oldPhysicalQualifier = fromVersion.major() != toVersion.major()
                    ? packId + "_v" + fromVersion.major()
                    : "";
            if (!composed.isEmpty() || !oldPhysicalQualifier.isBlank()) {
                packNodeById.put(packId,
                        PackMigrationChainSynthesizer.applyComposedRenames(packNode, composed, oldPhysicalQualifier));
            }

            // Kept current regardless of whether this run's composed range was empty -- a pack with
            // a chain but nothing new to replay (e.g. a same-version regenerate) still needs its
            // lock entry present so GeneratorMain's post-generate write has something to update.
            if (freshEntries == null) {
                freshEntries = toLockEntries();
            }
            state.migrationTrackedPacks.put(packId, freshEntries.get(packId));
        }
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
