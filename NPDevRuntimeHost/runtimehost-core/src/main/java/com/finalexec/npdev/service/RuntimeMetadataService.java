package com.finalexec.npdev.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.kernel.i18n.LabelResolver;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

@Service
public class RuntimeMetadataService {

    private static final String ENDPOINT_VERSION = "1.0.0";
    private static final String COMPILED_METADATA_CLASSPATH = "npdev/compiled-metadata.json";
    private static final String METADATA_INDEX_CLASSPATH = "npdev/metadata/index.json";
    // F2.2: the same file SchemaLifecycleExecutor/SchemaManifestLoader read to get schemaFingerprint --
    // reused verbatim as the UI-contract bundle's modelHash rather than minting a second hash (the
    // plan's own explicit instruction). Note this fingerprint covers table/column/type/required/unique
    // shape only (see UserDatabaseDefinitionLoader#fingerprintInputs) -- it will NOT change for a
    // panel-action/permission-hint/flow/lifecycle-only edit, only for a schema-shaped one (e.g. a field
    // rename). Accepted boundary, not a bug: F4's drift detection is precise for renames, the category
    // this catalog exists to protect against.
    private static final String SCHEMA_REALIZATION_MANIFEST_CLASSPATH = "npdev/db/schema-realization-manifest.json";
    private static final Map<String, String> CATALOG_ALIASES = Map.ofEntries(
            Map.entry("concept", "concepts"),
            Map.entry("concepts", "concepts"),
            Map.entry("procedure", "procedures"),
            Map.entry("procedures", "procedures"),
            Map.entry("panel", "panels"),
            Map.entry("panels", "panels"),
            Map.entry("field", "fields"),
            Map.entry("fields", "fields"),
            Map.entry("enum", "enums"),
            Map.entry("enums", "enums"),
            Map.entry("reference", "references"),
            Map.entry("references", "references"),
            Map.entry("action", "actions"),
            Map.entry("actions", "actions"),
            Map.entry("transition", "transitions"),
            Map.entry("transitions", "transitions"),
            Map.entry("layout", "layout"),
            Map.entry("layouts", "layout"),
            Map.entry("validation", "validationHints"),
            Map.entry("validation-hints", "validationHints"),
            Map.entry("validationhints", "validationHints"),
            Map.entry("invocation", "invocations"),
            Map.entry("invocations", "invocations")
    );

    // REG-103 (Move 12 P2.1): LC-C2's premise is writing metadata into a RUNNING app, but this
    // service loaded npdev/compiled-metadata.json and npdev/metadata/index.json via ClassPathResource
    // ONLY -- baked into the jar at build time, so a label/panel edit could never become visible
    // without a rebuild. Mirrors NPDevModelProvider's own external-path-with-classpath-fallback
    // convention exactly (same relative-path shape, same "external wins if present" order) rather
    // than inventing a second configuration convention.
    private static final String COMPILED_METADATA_PATH_DEFAULT =
            "npdev-generated/src/main/resources/npdev/compiled-metadata.json";
    private static final String METADATA_INDEX_PATH_DEFAULT =
            "npdev-generated/src/main/resources/npdev/metadata/index.json";
    // REG-103 (Move 13 P5.1): the same "npdev-generated/src/main/resources/" prefix
    // COMPILED_METADATA_PATH_DEFAULT/METADATA_INDEX_PATH_DEFAULT already hard-code, factored out so
    // any OTHER classpath location under npdev/... (a per-catalog manifest, the schema-realization
    // manifest) can derive its own external override the same way, without a named @Value property
    // per catalog -- there is no fixed set of those, unlike the two named catalogs above.
    private static final String GENERATED_RESOURCES_ROOT_DEFAULT = "npdev-generated/src/main/resources";

    private final ObjectMapper objectMapper;
    private final Path externalCompiledMetadataPath;
    private final Path externalMetadataIndexPath;
    private final Path externalGeneratedResourcesRoot;

    // R1.7 (roadmap Wave 1, "hot metadata swap"): guards every read method above against
    // applyMetadataOnlyReload below. Readers take the read lock for their WHOLE call (not per file),
    // so a single overview()/catalog()/... call can never observe some-new/some-old files torn across
    // a reload -- see applyMetadataOnlyReload's own javadoc for the full atomicity argument.
    private final ReentrantReadWriteLock reloadLock = new ReentrantReadWriteLock();
    private final AtomicLong reloadGeneration = new AtomicLong(0);
    private volatile Instant lastReloadAt;
    private volatile List<String> lastReloadReasons = List.of();

    /** Byte-identical to the pre-REG-103 behaviour: no external path configured, classpath only. */
    public RuntimeMetadataService(ObjectMapper objectMapper) {
        this(objectMapper, COMPILED_METADATA_PATH_DEFAULT, METADATA_INDEX_PATH_DEFAULT);
    }

    /** Kept for the two-catalog-only override shape (REG-103's original test/call sites); delegates
     * to the four-arg constructor with the generated-resources root defaulted. */
    public RuntimeMetadataService(ObjectMapper objectMapper, String compiledMetadataPath, String metadataIndexPath) {
        this(objectMapper, compiledMetadataPath, metadataIndexPath, GENERATED_RESOURCES_ROOT_DEFAULT);
    }

    @Autowired
    public RuntimeMetadataService(
            ObjectMapper objectMapper,
            @Value("${npdev.compiled-metadata.path:" + COMPILED_METADATA_PATH_DEFAULT + "}") String compiledMetadataPath,
            @Value("${npdev.metadata-index.path:" + METADATA_INDEX_PATH_DEFAULT + "}") String metadataIndexPath,
            @Value("${npdev.generated-resources.path:" + GENERATED_RESOURCES_ROOT_DEFAULT + "}") String generatedResourcesRootPath
    ) {
        this.objectMapper = objectMapper;
        this.externalCompiledMetadataPath = Paths.get(compiledMetadataPath).toAbsolutePath().normalize();
        this.externalMetadataIndexPath = Paths.get(metadataIndexPath).toAbsolutePath().normalize();
        this.externalGeneratedResourcesRoot = Paths.get(generatedResourcesRootPath).toAbsolutePath().normalize();
    }

    /** Derives a per-catalog/manifest external path from its classpath location, mirroring
     * COMPILED_METADATA_PATH_DEFAULT/METADATA_INDEX_PATH_DEFAULT's own prefix convention -- there is
     * no fixed list of these (a catalog's manifest path comes from the index at runtime), so this is
     * a generic transform rather than a named @Value per file. */
    private Path externalPathFor(String classpathLocation) {
        return externalGeneratedResourcesRoot.resolve(classpathLocation).normalize();
    }

    public Map<String, Object> overview() {
        return withReadLock(() -> {
            Map<String, Object> compiledMetadata = loadJsonMap(COMPILED_METADATA_CLASSPATH, externalCompiledMetadataPath);
            Map<String, Object> index = loadJsonMap(METADATA_INDEX_CLASSPATH, externalMetadataIndexPath);
            List<Map<String, Object>> catalogs = extractCatalogEntries(index);

            Map<String, Object> response = baseResponse();
            response.put("compiledMetadataPath", COMPILED_METADATA_CLASSPATH);
            response.put("metadataIndexPath", METADATA_INDEX_CLASSPATH);
            response.put("namespace", stringValue(compiledMetadata.get("namespace")));
            response.put("dslVersion", stringValue(compiledMetadata.get("dslVersion")));
            response.put("modelVersion", stringValue(compiledMetadata.get("version")));
            response.put("metadataManifestVersion", stringValue(index.get("metadataManifestVersion")));
            response.put("metadataVersion", stringValue(index.get("metadataVersion")));
            response.put("catalogCount", catalogs.size());
            response.put("catalogs", catalogs);
            response.put("compiledCatalogNames", extractCompiledCatalogNames(compiledMetadata));
            response.put("metadataGeneration", reloadGeneration.get());
            return response;
        });
    }

    public Map<String, Object> metadataIndex() {
        return withReadLock(() -> {
            Map<String, Object> index = new LinkedHashMap<>(loadJsonMap(METADATA_INDEX_CLASSPATH, externalMetadataIndexPath));
            index.put("endpointVersion", ENDPOINT_VERSION);
            index.put("catalogCount", extractCatalogEntries(index).size());
            index.put("metadataIndexPath", METADATA_INDEX_CLASSPATH);
            return index;
        });
    }

    public Map<String, Object> concepts(String conceptName) {
        return withReadLock(() -> buildCatalogResponse("concepts", conceptName, null, null));
    }

    public Map<String, Object> concept(String conceptName) {
        return concept(conceptName, null);
    }

    /** R5.6 locale-aware overload -- see {@link #resolveLabels(Map, String)}. {@code requestedLocale}
     * null behaves byte-identical to {@link #concept(String)}. */
    public Map<String, Object> concept(String conceptName, String requestedLocale) {
        return withReadLock(() -> {
            Map<String, Object> response = buildCatalogResponse("concepts", conceptName, null, null, requestedLocale);
            List<Map<String, Object>> items = extractItems(response);
            if (items.isEmpty()) {
                throw new NoSuchElementException("Runtime metadata concept not found: " + conceptName);
            }
            response.put("concept", items.get(0));
            response.put("relatedCatalogCounts", relatedCatalogCounts(conceptName));
            return response;
        });
    }

    public Map<String, Object> procedures(String procedureName) {
        return withReadLock(() -> buildCatalogResponse("procedures", null, procedureName, null));
    }

    public Map<String, Object> panels(String panelName) {
        return withReadLock(() -> buildCatalogResponse("panels", null, panelName, null));
    }

    public Map<String, Object> fields(String conceptName, String fieldPath) {
        return fields(conceptName, fieldPath, null);
    }

    /** R5.6 locale-aware overload -- see {@link #resolveLabels(Map, String)}. */
    public Map<String, Object> fields(String conceptName, String fieldPath, String requestedLocale) {
        return withReadLock(() -> buildCatalogResponse("fields", conceptName, null, fieldPath, requestedLocale));
    }

    public Map<String, Object> enums(String conceptName, String fieldPath) {
        return withReadLock(() -> {
            Map<String, Object> response = buildCatalogResponse("enums", conceptName, null, fieldPath);
            response.put("enumFields", distinctValues(extractItems(response), "fieldPath"));
            return response;
        });
    }

    public Map<String, Object> references(String conceptName, String fieldPath) {
        return withReadLock(() -> {
            Map<String, Object> response = buildCatalogResponse("references", conceptName, null, fieldPath);
            response.put("targetConcepts", distinctValues(extractItems(response), "targetConcept"));
            return response;
        });
    }

    public Map<String, Object> actions(String conceptName, String ownerName) {
        return actions(conceptName, ownerName, null);
    }

    /** R5.6 locale-aware overload -- see {@link #resolveLabels(Map, String)}. */
    public Map<String, Object> actions(String conceptName, String ownerName, String requestedLocale) {
        return withReadLock(() -> {
            Map<String, Object> response = buildCatalogResponse("actions", conceptName, ownerName, null, requestedLocale);
            response.put("actionKinds", distinctValues(extractItems(response), "kind"));
            response.put("permissionHints", distinctValues(extractItems(response), "permissionHint"));
            return response;
        });
    }

    public Map<String, Object> layout(String conceptName, String fieldPath) {
        return withReadLock(() -> {
            Map<String, Object> response = buildCatalogResponse("layout", conceptName, null, fieldPath);
            response.put("tabs", distinctValues(extractItems(response), "tab"));
            return response;
        });
    }

    public Map<String, Object> validationSupport(String conceptName, String fieldPath) {
        return withReadLock(() -> {
            Map<String, Object> response = buildCatalogResponse("validationHints", conceptName, null, fieldPath);
            List<Map<String, Object>> items = extractItems(response);
            response.put("hintKinds", distinctValues(items, "kind"));
            response.put("kindCounts", countBy(items, "kind"));
            return response;
        });
    }

    public Map<String, Object> catalog(String catalogName, String conceptName, String ownerName, String fieldPath) {
        return catalog(catalogName, conceptName, ownerName, fieldPath, null);
    }

    /** R5.6 locale-aware overload -- see {@link #resolveLabels(Map, String)}. Generic over
     * {@code catalogName}, so this single overload is what {@code PermissionAwareUiMetadataService
     * .rawCatalogItems} routes every one of the bundle's unfiltered catalogs (layout/enums/
     * references/transitions/validation/invocations) through to localize them too. */
    public Map<String, Object> catalog(
            String catalogName, String conceptName, String ownerName, String fieldPath, String requestedLocale) {
        return withReadLock(() -> buildCatalogResponse(catalogName, conceptName, ownerName, fieldPath, requestedLocale));
    }

    /** F2.2: the UI-contract bundle's {@code modelHash} -- the same fingerprint
     * {@code SchemaLifecycleExecutor}/{@code SchemaManifestLoader} read from this identical classpath
     * resource, reused rather than minting a second hash. Throws {@link IllegalStateException} (like
     * every other catalog read here) if the app has no schema-realization manifest on its classpath --
     * the controller's existing {@code run()} wrapper maps that to 503, same as a missing catalog. */
    public String schemaFingerprint() {
        return withReadLock(() -> {
            Map<String, Object> manifest = loadJsonMap(
                    SCHEMA_REALIZATION_MANIFEST_CLASSPATH, externalPathFor(SCHEMA_REALIZATION_MANIFEST_CLASSPATH));
            return stringValue(manifest.get("schemaFingerprint"));
        });
    }

    public Map<String, Object> previewSupport(String conceptName) {
        return previewSupport(conceptName, null);
    }

    /** R5.6 locale-aware overload -- see {@link #resolveLabels(Map, String)}. Every derived view
     * built below (listColumns/actionSummaries/summaryFields/...) reads {@code "label"} off
     * {@code fieldItems}/{@code actionItems}/{@code layoutItems}, already resolved by
     * {@link #filteredItems(String, String, String, String, String)} at this point -- so those
     * derived builders need no locale awareness of their own. */
    public Map<String, Object> previewSupport(String conceptName, String requestedLocale) {
        return withReadLock(() -> {
            Map<String, Object> conceptResponse = concept(conceptName, requestedLocale);
            Map<String, Object> concept = castMap(conceptResponse.get("concept"));
            List<Map<String, Object>> fieldItems = filteredItems("fields", conceptName, null, null, requestedLocale);
            List<Map<String, Object>> enumItems = filteredItems("enums", conceptName, null, null, requestedLocale);
            List<Map<String, Object>> referenceItems = filteredItems("references", conceptName, null, null, requestedLocale);
            List<Map<String, Object>> actionItems = filteredItems("actions", conceptName, null, null, requestedLocale);
            List<Map<String, Object>> layoutItems = filteredItems("layout", conceptName, null, null, requestedLocale);
            List<Map<String, Object>> validationItems = filteredItems("validationHints", conceptName, null, null, requestedLocale);

            Map<String, Object> response = baseResponse();
            response.put("concept", concept);
            response.put("relatedCatalogCounts", relatedCatalogCounts(conceptName));
            response.put("fields", fieldItems);
            response.put("enums", enumItems);
            response.put("references", referenceItems);
            response.put("actions", actionItems);
            response.put("layout", layoutItems);
            response.put("validationHints", validationItems);

            Map<String, Object> previewSupport = new LinkedHashMap<>();
            previewSupport.put("tabs", distinctValues(layoutItems, "tab"));
            previewSupport.put("summaryFields", summaryFields(layoutItems));
            previewSupport.put("listColumns", listColumns(layoutItems));
            previewSupport.put("referencePickers", referencePickers(referenceItems));
            previewSupport.put("actionLabels", actionSummaries(actionItems));
            previewSupport.put("validationKinds", distinctValues(validationItems, "kind"));
            previewSupport.put("defaultSort", stringValue(concept.get("defaultSort")));
            previewSupport.put("defaultGroup", stringValue(concept.get("defaultGroup")));
            previewSupport.put("displayMode", stringValue(concept.get("displayMode")));
            response.put("previewSupport", previewSupport);
            return response;
        });
    }

    /**
     * R1.7 (roadmap Wave 1, "hot metadata swap: METADATA_ONLY edits into the running JVM"). Atomically
     * swaps the descriptive metadata catalogs this service serves (compiled-metadata.json,
     * metadata/index.json, and every metadata/*.manifest.json catalog file the new index references)
     * for a new set staged on disk at {@code sourceMetadataRoot}, without restarting the JVM.
     *
     * <p><b>{@code sourceMetadataRoot} shape.</b> Exactly the {@code <dir>} produced by
     * {@code ModelChangeClassifierMain --emitMetadataTo <dir>} (i.e. {@code Update-AppMetadata.ps1}'s
     * own scratch directory before it copies files onto the app's tree by hand) --
     * {@code <dir>/src/main/resources/npdev/compiled-metadata.json} and
     * {@code <dir>/src/main/resources/npdev/metadata/{index.json,*.manifest.json}}. Reusing that exact
     * layout means the caller can point this method at output the existing, already-tested classifier
     * task already produces, rather than this method inventing a second directory convention.
     *
     * <p><b>Refuses (throws {@link MetadataChangeRefusedException}, touches nothing) unless
     * {@code classification} is exactly {@code "METADATA_ONLY"}.</b> This method has no in-process
     * model-diff engine of its own -- {@code ModelChangeClassifier}/{@code MigrationPlanEmitter} live
     * in the generator module, a build-time tool this runtime module does not and should not depend
     * on -- so {@code classification} is trusted from the caller, exactly as
     * {@code Update-AppMetadata.ps1} already trusts the classifier task's own report today. The blast
     * radius of a wrong/lied-about classification is deliberately capped: this method NEVER writes
     * {@code compiled-model.json} or anything DB-schema-shaped, only the purely descriptive catalogs
     * {@link #overview()} and friends already serve read-only. Every consumer that executes real
     * behaviour against the model (PanelRuntime's query building, ConceptGateway, KernelRunner,
     * CelInvariantEngine, ...) is wired from the separate, Spring-singleton {@code CompiledModel} bean
     * ({@code NPDevModelProvider}), which this method does not touch -- so even a misclassified reload
     * cannot change what SQL a panel runs or what an invariant enforces, only what label/hint/layout
     * text is shown for it. See this class's own PR/roadmap notes (R1.7) for why a full swap of the
     * execution-path CompiledModel bean is a separate, harder, cross-module problem this method does
     * not attempt.
     *
     * <p><b>Atomicity.</b> Every read method above takes {@code reloadLock.readLock()} for its WHOLE
     * call (not per individual file), and this method takes {@code reloadLock.writeLock()} for the
     * whole swap -- so a concurrent reader either finishes entirely against the OLD catalog set or
     * blocks and then proceeds entirely against the NEW one; no caller can ever observe some catalogs
     * updated and others not (a torn cross-file read). Each individual file write is itself
     * write-to-a-sibling-temp-file-then-{@link StandardCopyOption#ATOMIC_MOVE}, so a crash mid-swap
     * cannot leave a half-written file on disk either. Every source file is parsed as JSON BEFORE any
     * destination file is touched (a pre-flight validation pass) -- a malformed source directory
     * refuses cleanly with nothing written, rather than applying some catalogs and not others.
     *
     * @throws MetadataChangeRefusedException if {@code classification} is not {@code METADATA_ONLY}
     * @throws IllegalArgumentException if {@code sourceMetadataRoot} is missing a required file, or a
     *         file the new index references does not exist under it
     * @throws IOException if a source/destination file cannot be read or written
     */
    public MetadataReloadResult applyMetadataOnlyReload(
            String classification, List<String> classificationReasons, Path sourceMetadataRoot) throws IOException {
        if (!"METADATA_ONLY".equals(classification)) {
            throw new MetadataChangeRefusedException(classification, classificationReasons);
        }

        // NOTE: a catalog entry's own "path" field (e.g. "npdev/metadata/concepts.manifest.json") is
        // already classpath-rooted -- the SAME string COMPILED_METADATA_CLASSPATH/
        // METADATA_INDEX_CLASSPATH are, and the same string externalPathFor(path) resolves against
        // externalGeneratedResourcesRoot for the destination side. So the source side resolves every
        // path against "src/main/resources" (one level ABOVE "npdev"), never against a "npdev"-suffixed
        // root -- resolving against the latter would double the "npdev" segment for every catalog file
        // while accidentally still working for compiled-metadata.json/index.json (whose classpath
        // constants are used directly below instead of a bare relative literal, for the same reason).
        Path sourceResourcesRoot = sourceMetadataRoot.resolve("src/main/resources");
        Path sourceCompiledMetadata = sourceResourcesRoot.resolve(COMPILED_METADATA_CLASSPATH);
        Path sourceIndex = sourceResourcesRoot.resolve(METADATA_INDEX_CLASSPATH);
        requireRegularFile(sourceCompiledMetadata);
        requireRegularFile(sourceIndex);

        // Pre-flight: parse every referenced file before writing anything.
        parseJsonFile(sourceCompiledMetadata);
        Map<String, Object> newIndex = parseJsonFile(sourceIndex);
        List<Map<String, Object>> catalogEntries = extractCatalogEntries(newIndex);

        Map<Path, Path> plannedCopies = new LinkedHashMap<>();
        plannedCopies.put(sourceCompiledMetadata, externalCompiledMetadataPath);
        plannedCopies.put(sourceIndex, externalMetadataIndexPath);
        for (Map<String, Object> catalogEntry : catalogEntries) {
            String path = stringValue(catalogEntry.get("path"));
            if (path.isBlank()) {
                throw new IllegalArgumentException(
                        "metadata/index.json catalog entry missing 'path': " + catalogEntry);
            }
            Path sourceCatalogFile = sourceResourcesRoot.resolve(path);
            requireRegularFile(sourceCatalogFile);
            parseJsonFile(sourceCatalogFile);
            plannedCopies.putIfAbsent(sourceCatalogFile, externalPathFor(path));
        }

        reloadLock.writeLock().lock();
        try {
            List<String> updated = new ArrayList<>();
            for (Map.Entry<Path, Path> copy : plannedCopies.entrySet()) {
                atomicCopy(copy.getKey(), copy.getValue());
                updated.add(copy.getValue().toString());
            }
            long newGeneration = reloadGeneration.incrementAndGet();
            Instant appliedAt = Instant.now();
            List<String> reasons = classificationReasons == null ? List.of() : List.copyOf(classificationReasons);
            this.lastReloadAt = appliedAt;
            this.lastReloadReasons = reasons;
            return new MetadataReloadResult(newGeneration, appliedAt, List.copyOf(updated), reasons);
        } finally {
            reloadLock.writeLock().unlock();
        }
    }

    /** Current hot-swap state, for an operator/agent to confirm a reload actually happened without
     * re-reading a whole catalog. Read-locked like every other query above, for the same "never a
     * torn view mid-reload" reason. */
    public Map<String, Object> reloadStatus() {
        return withReadLock(() -> {
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("metadataGeneration", reloadGeneration.get());
            status.put("lastAppliedAt", lastReloadAt == null ? null : lastReloadAt.toString());
            status.put("lastAppliedReasons", lastReloadReasons);
            return status;
        });
    }

    private static void requireRegularFile(Path path) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Missing required metadata source file: " + path);
        }
    }

    private Map<String, Object> parseJsonFile(Path path) throws IOException {
        try (InputStream inputStream = Files.newInputStream(path)) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // MUST be caught before the plain IOException branch below: Jackson's
            // JsonProcessingException (JsonParseException/MismatchedInputException/...) IS-A
            // IOException, so with the branches in the other order this clause is unreachable and
            // every malformed-JSON source file was rethrown as a raw IOException instead of the
            // IllegalArgumentException applyMetadataOnlyReload's own javadoc documents -- found live
            // by RuntimeMetadataServiceHotSwapTest#refusesOnAMalformedSourceDirectoryWithoutTouchingAnyDestinationFile
            // the first time these tests actually ran.
            throw new IllegalArgumentException("Failed to parse JSON metadata source file: " + path + " (" + e.getMessage() + ")", e);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSON metadata source file: " + path + " (" + e.getMessage() + ")", e);
        }
    }

    /** Write-temp-then-{@link StandardCopyOption#ATOMIC_MOVE}, temp file created as a SIBLING of
     * {@code destination} so the move is same-filesystem (a cross-filesystem move cannot be atomic --
     * {@code Files.move} would throw {@code AtomicMoveNotSupportedException} instead of silently
     * degrading to a non-atomic copy). */
    private static void atomicCopy(Path source, Path destination) throws IOException {
        Path destinationDir = destination.toAbsolutePath().normalize().getParent();
        if (destinationDir != null) {
            Files.createDirectories(destinationDir);
        }
        Path temp = Files.createTempFile(destinationDir, "reload-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private <T> T withReadLock(Supplier<T> operation) {
        reloadLock.readLock().lock();
        try {
            return operation.get();
        } finally {
            reloadLock.readLock().unlock();
        }
    }

    /** Thrown by {@link #applyMetadataOnlyReload} when the caller-declared classification is anything
     * other than {@code METADATA_ONLY} -- the refusal contract {@code ModelChangeClassifierMain}'s own
     * {@code --emitMetadataTo}/{@code --emitCompiledModelTo} flags already enforce offline, mirrored
     * here for the live-JVM path. */
    public static final class MetadataChangeRefusedException extends RuntimeException {
        private final String classification;
        private final List<String> reasons;

        public MetadataChangeRefusedException(String classification, List<String> reasons) {
            super("Refused: metadata hot-swap requires classification METADATA_ONLY, got "
                    + classification + " -- " + (reasons == null ? List.of() : reasons));
            this.classification = classification;
            this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }

        public String classification() {
            return classification;
        }

        public List<String> reasons() {
            return reasons;
        }
    }

    /** Result of a successful {@link #applyMetadataOnlyReload} call. */
    public record MetadataReloadResult(
            long generation, Instant appliedAt, List<String> catalogsUpdated, List<String> classificationReasons) {
    }

    private Map<String, Object> buildCatalogResponse(
            String requestedCatalog,
            String conceptName,
            String ownerName,
            String fieldPath
    ) {
        return buildCatalogResponse(requestedCatalog, conceptName, ownerName, fieldPath, null);
    }

    private Map<String, Object> buildCatalogResponse(
            String requestedCatalog,
            String conceptName,
            String ownerName,
            String fieldPath,
            String requestedLocale
    ) {
        String resolvedCatalog = normalizeCatalogName(requestedCatalog);
        Map<String, Object> manifest = new LinkedHashMap<>(loadManifest(resolvedCatalog));
        List<Map<String, Object>> filteredItems =
                filteredItems(resolvedCatalog, conceptName, ownerName, fieldPath, requestedLocale);

        manifest.put("endpointVersion", ENDPOINT_VERSION);
        manifest.put("requestedCatalog", requestedCatalog);
        manifest.put("resolvedCatalog", resolvedCatalog);
        manifest.put("filters", buildFilters(conceptName, ownerName, fieldPath));
        manifest.put("filteredCount", filteredItems.size());
        manifest.put("items", filteredItems);
        return manifest;
    }

    private Map<String, Object> buildFilters(String conceptName, String ownerName, String fieldPath) {
        Map<String, Object> filters = new LinkedHashMap<>();
        if (!normalize(conceptName).isBlank()) {
            filters.put("concept", conceptName.trim());
        }
        if (!normalize(ownerName).isBlank()) {
            filters.put("ownerName", ownerName.trim());
        }
        if (!normalize(fieldPath).isBlank()) {
            filters.put("fieldPath", fieldPath.trim());
        }
        return filters;
    }

    private Map<String, Object> relatedCatalogCounts(String conceptName) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("fields", filteredItems("fields", conceptName, null, null).size());
        counts.put("enums", filteredItems("enums", conceptName, null, null).size());
        counts.put("references", filteredItems("references", conceptName, null, null).size());
        counts.put("actions", filteredItems("actions", conceptName, null, null).size());
        counts.put("layout", filteredItems("layout", conceptName, null, null).size());
        counts.put("validationHints", filteredItems("validationHints", conceptName, null, null).size());
        return counts;
    }

    private List<Map<String, Object>> filteredItems(
            String catalogName,
            String conceptName,
            String ownerName,
            String fieldPath
    ) {
        return filteredItems(catalogName, conceptName, ownerName, fieldPath, null);
    }

    /**
     * R5.6: locale-aware sibling of the 4-arg overload above. Every catalog manifest item this
     * loads already carries {@code labelLocales}/{@code shortLabelLocales} (EDIT-13,
     * {@code CompiledMetadataCanonicalJson}) alongside its plain {@code label}/{@code shortLabel} --
     * resolved HERE, at the point items first enter the RuntimeHost service layer, so every
     * downstream consumer (previewSupport's listColumns/actionSummaries, the admin catalog
     * endpoints, PermissionAwareUiMetadataService's enrichment) sees an already-correct plain string
     * and none of them need their own locale awareness. {@code requestedLocale == null} (every
     * existing 4-arg call site, and the ADMIN {@code RuntimeMetadataController}, which never reads a
     * caller locale) is byte-identical to the pre-R5.6 shape: {@link #resolveLabels} is a no-op for
     * a null locale, so a plain-string model -- and every caller that never threads a locale -- is
     * unaffected by this change.
     */
    private List<Map<String, Object>> filteredItems(
            String catalogName,
            String conceptName,
            String ownerName,
            String fieldPath,
            String requestedLocale
    ) {
        List<Map<String, Object>> sourceItems = extractItems(loadManifest(normalizeCatalogName(catalogName)));
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> item : sourceItems) {
            if (!matchesConcept(catalogName, item, conceptName)) {
                continue;
            }
            if (!matchesFieldPath(item, fieldPath)) {
                continue;
            }
            if (!matchesOwnerName(item, ownerName)) {
                continue;
            }
            filtered.add(resolveLabels(item, requestedLocale));
        }
        return filtered;
    }

    /**
     * R5.6: resolves every {@code "<x>"}/{@code "<x>Locales"} pair this service's catalog items
     * carry ({@code label}/{@code labelLocales}, {@code shortLabel}/{@code shortLabelLocales}) to
     * plain text for {@code requestedLocale}, via the kernel's {@link LabelResolver} -- the same
     * fallback rule (exact tag -> same language -> default) every other R5.6 caller uses. The raw
     * {@code "<x>Locales"} map is dropped from the returned item once resolved: the point of doing
     * this server-side is that a client receives a ready-to-render label, never a map it has to
     * interpret itself.
     *
     * <p>{@code requestedLocale == null} returns {@code item} completely unchanged (not even a copy)
     * -- the exact byte-identical behavior every pre-R5.6 caller already had, and the guarantee that
     * a plain-string-labeled model (whose {@code labelLocales} is always an empty map either way) is
     * never affected by this method existing.
     */
    private static Map<String, Object> resolveLabels(Map<String, Object> item, String requestedLocale) {
        if (requestedLocale == null || requestedLocale.isBlank()) {
            return item;
        }
        Map<String, Object> resolved = new LinkedHashMap<>(item);
        resolveLabelPair(resolved, "label", "labelLocales", requestedLocale);
        resolveLabelPair(resolved, "shortLabel", "shortLabelLocales", requestedLocale);
        return resolved;
    }

    private static void resolveLabelPair(
            Map<String, Object> item, String textKey, String localesKey, String requestedLocale) {
        if (!item.containsKey(textKey)) {
            return;
        }
        Object localesRaw = item.remove(localesKey);
        if (!(localesRaw instanceof Map<?, ?> localesMap) || localesMap.isEmpty()) {
            return;
        }
        Object textRaw = item.get(textKey);
        String defaultText = textRaw == null ? "" : String.valueOf(textRaw);
        Map<String, String> locales = new LinkedHashMap<>();
        localesMap.forEach((key, value) -> locales.put(String.valueOf(key), value == null ? "" : String.valueOf(value)));
        item.put(textKey, LabelResolver.resolve(defaultText, locales, requestedLocale));
    }

    private boolean matchesConcept(String catalogName, Map<String, Object> item, String conceptName) {
        String normalizedConcept = normalize(conceptName);
        if (normalizedConcept.isBlank()) {
            return true;
        }
        String property = "concepts".equals(normalizeCatalogName(catalogName)) ? "name" : "concept";
        return normalize(item.get(property)).equalsIgnoreCase(normalizedConcept);
    }

    private boolean matchesFieldPath(Map<String, Object> item, String fieldPath) {
        String normalizedFieldPath = normalize(fieldPath);
        if (normalizedFieldPath.isBlank()) {
            return true;
        }
        return normalize(item.get("fieldPath")).equalsIgnoreCase(normalizedFieldPath);
    }

    private boolean matchesOwnerName(Map<String, Object> item, String ownerName) {
        String normalizedOwnerName = normalize(ownerName);
        if (normalizedOwnerName.isBlank()) {
            return true;
        }
        return normalize(item.get("ownerName")).equalsIgnoreCase(normalizedOwnerName);
    }

    private String normalizeCatalogName(String catalogName) {
        String normalized = normalize(catalogName).toLowerCase().replace("_", "-");
        String resolved = CATALOG_ALIASES.get(normalized);
        if (resolved == null) {
            throw new IllegalArgumentException("Unsupported runtime metadata catalog: " + catalogName);
        }
        return resolved;
    }

    private Map<String, Object> loadManifest(String catalogName) {
        Map<String, Object> index = loadJsonMap(METADATA_INDEX_CLASSPATH, externalMetadataIndexPath);
        List<Map<String, Object>> catalogs = extractCatalogEntries(index);
        for (Map<String, Object> catalog : catalogs) {
            if (normalize(catalog.get("name")).equalsIgnoreCase(catalogName)) {
                String path = stringValue(catalog.get("path"));
                if (path.isBlank()) {
                    throw new IllegalStateException("Runtime metadata catalog path is blank for catalog: " + catalogName);
                }
                return loadJsonMap(path, externalPathFor(path));
            }
        }
        throw new IllegalStateException("Runtime metadata index does not expose catalog: " + catalogName);
    }

    private List<Map<String, Object>> extractCatalogEntries(Map<String, Object> index) {
        List<Map<String, Object>> catalogs = new ArrayList<>();
        Object raw = index.get("catalogs");
        if (raw instanceof Collection<?> collection) {
            for (Object entry : collection) {
                catalogs.add(castMap(entry));
            }
        }
        return catalogs;
    }

    private Set<String> extractCompiledCatalogNames(Map<String, Object> compiledMetadata) {
        Set<String> names = new LinkedHashSet<>();
        Object raw = compiledMetadata.get("catalogs");
        if (raw instanceof Map<?, ?> catalogMap) {
            for (Object key : catalogMap.keySet()) {
                names.add(String.valueOf(key));
            }
        }
        return names;
    }

    private List<Map<String, Object>> extractItems(Map<String, Object> manifest) {
        List<Map<String, Object>> items = new ArrayList<>();
        Object rawItems = manifest.get("items");
        if (rawItems instanceof Collection<?> collection) {
            for (Object item : collection) {
                items.add(castMap(item));
            }
        }
        return items;
    }

    private List<String> distinctValues(List<Map<String, Object>> items, String key) {
        Set<String> values = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            String value = stringValue(item.get(key));
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return new ArrayList<>(values);
    }

    private Map<String, Integer> countBy(List<Map<String, Object>> items, String key) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> item : items) {
            String value = stringValue(item.get(key));
            if (value.isBlank()) {
                value = "<blank>";
            }
            counts.put(value, counts.getOrDefault(value, 0) + 1);
        }
        return counts;
    }

    private List<String> summaryFields(List<Map<String, Object>> layoutItems) {
        List<String> fields = new ArrayList<>();
        for (Map<String, Object> item : layoutItems) {
            Object summaryCard = item.get("summaryCard");
            if (Boolean.TRUE.equals(summaryCard)) {
                String fieldPath = stringValue(item.get("fieldPath"));
                if (!fieldPath.isBlank()) {
                    fields.add(fieldPath);
                }
            }
        }
        return fields;
    }

    private List<Map<String, Object>> listColumns(List<Map<String, Object>> layoutItems) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (Map<String, Object> item : layoutItems) {
            if (!Boolean.TRUE.equals(item.get("listColumn"))) {
                continue;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("fieldPath", stringValue(item.get("fieldPath")));
            summary.put("label", stringValue(item.get("label")));
            summary.put("listColumnOrder", item.get("listColumnOrder"));
            columns.add(summary);
        }
        columns.sort((left, right) -> Integer.compare(orderValue(left.get("listColumnOrder")), orderValue(right.get("listColumnOrder"))));
        return columns;
    }

    private List<Map<String, Object>> referencePickers(List<Map<String, Object>> referenceItems) {
        List<Map<String, Object>> pickers = new ArrayList<>();
        for (Map<String, Object> item : referenceItems) {
            Map<String, Object> picker = new LinkedHashMap<>();
            picker.put("fieldPath", stringValue(item.get("fieldPath")));
            picker.put("targetConcept", stringValue(item.get("targetConcept")));
            picker.put("displayTemplate", stringValue(item.get("displayTemplate")));
            picker.put("pickerColumns", item.getOrDefault("pickerColumns", List.of()));
            picker.put("previewCardTemplate", stringValue(item.get("previewCardTemplate")));
            picker.put("defaultFilter", stringValue(item.get("defaultFilter")));
            picker.put("inlineCreate", stringValue(item.get("inlineCreate")));
            pickers.add(picker);
        }
        return pickers;
    }

    private List<Map<String, Object>> actionSummaries(List<Map<String, Object>> actionItems) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (Map<String, Object> item : actionItems) {
            String label = stringValue(item.get("label"));
            if (label.isBlank()) {
                continue;
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", stringValue(item.get("name")));
            summary.put("kind", stringValue(item.get("kind")));
            summary.put("label", label);
            summary.put("permissionHint", stringValue(item.get("permissionHint")));
            summary.put("dangerLevel", stringValue(item.get("dangerLevel")));
            summary.put("inputFormHint", stringValue(item.get("inputFormHint")));
            summaries.add(summary);
        }
        return summaries;
    }

    private int orderValue(Object rawValue) {
        if (rawValue instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(rawValue));
        } catch (Exception ignored) {
            return Integer.MAX_VALUE;
        }
    }

    private Map<String, Object> baseResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("endpointVersion", ENDPOINT_VERSION);
        response.put("sourceType", "generated-runtime-metadata");
        response.put("sourceRoot", "classpath:/npdev");
        response.put("generatedFrom", COMPILED_METADATA_CLASSPATH);
        return response;
    }

    private Map<String, Object> loadJsonMap(String classpathLocation) {
        try (InputStream inputStream = new ClassPathResource(classpathLocation).getInputStream()) {
            return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load runtime metadata classpath resource: " + classpathLocation, e);
        }
    }

    /**
     * REG-103: the same {@code classpathLocation} fallback as {@link #loadJsonMap(String)}, but an
     * {@code externalPath} that exists on disk wins -- same precedence order as
     * {@code NPDevModelProvider}. Move 12 P2.1 covered only {@code compiled-metadata.json} and
     * {@code metadata/index.json}; Move 13 P5.1 widened this to every caller, including
     * {@link #loadManifest}'s per-catalog manifest files and {@code schema-realization-manifest.json},
     * via {@link #externalPathFor(String)}'s generic prefix derivation -- there being no fixed list of
     * per-catalog manifest files (unlike the two named catalogs) is exactly why that helper exists
     * instead of another named {@code @Value}.
     */
    private Map<String, Object> loadJsonMap(String classpathLocation, Path externalPath) {
        if (externalPath != null && Files.exists(externalPath)) {
            try (InputStream inputStream = Files.newInputStream(externalPath)) {
                return objectMapper.readValue(inputStream, new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (Exception e) {
                throw new IllegalStateException("Failed to load runtime metadata external file: " + externalPath, e);
            }
        }
        return loadJsonMap(classpathLocation);
    }

    private Map<String, Object> castMap(Object value) {
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            rawMap.forEach((key, item) -> normalized.put(String.valueOf(key), item));
            return normalized;
        }
        return new LinkedHashMap<>();
    }

    private String stringValue(Object value) {
        return normalize(value);
    }

    private String normalize(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
