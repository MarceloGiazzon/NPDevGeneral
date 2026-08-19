package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.paths.CanonicalModelPaths;
import com.npdev.dsl.v1.pack.PackLockFile;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.ResolvedSetting;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import com.npdev.generator.assembly.FinalAppAssembler;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.emitters.AppDependenciesEmitter;
import com.npdev.generator.packs.BuiltinPackComposer;
import com.npdev.generator.packs.PackExtensionComposer;
import com.npdev.generator.settings.ConfigSettingsReader;
import com.npdev.generator.dbconfig.DockerDeploymentEmitter;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.OperationalRunbookEmitter;
import com.npdev.generator.dbconfig.UserDatabaseDefinitionLoader;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.provenance.BuildInfoEmitter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GeneratorMain {

    static final String CONFIG_MIGRATIONS_DISABLED = "CONFIG_MIGRATIONS_DISABLED";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);

        JsonNode config = readConfig(a.configPath);
        // storage/FULL_SUPPORT_PLAN.md W6.1. Every config.json's own $schema property has always
        // claimed this contract; nothing had ever checked it, and the first check found 93 violations
        // across 27 corpus configs -- the shipped T1 canary among them. Runs FIRST, before anything
        // reads a field out of the config, so a violation is reported as a contract error rather than
        // discovered later as a missing default.
        ConfigSchemaValidator.verify(config, a.configPath);
        rejectUnsupportedMigrationManagement(config);

        // Resolution pipeline: cascade platform defaults <- config defaults <- config overrides.
        SettingResolver settingResolver = new SettingResolver(new ConfigSettingsReader().read(config));
        ResolvedSetting<Boolean> generateBusinessUi =
                settingResolver.resolve(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app());
        System.out.println("Setting " + NpdevSettings.UI_GENERATE_BUSINESS_UI.id()
                + " = " + generateBusinessUi.value()
                + " (source: " + generateBusinessUi.sourceSelector() + ")");

        Path modelPath = resolveModelPath(a, config);
        Path outRoot = resolveOutputRoot(a, config, modelPath);
        boolean cleanOut = resolveCleanOutput(a, config);

        // Clean only the disposable output folder (generated Java/resources).
        if (cleanOut) {
            cleanOutputRoot(outRoot);
        }

        Path schemaRealizationDir = resolveSchemaRealizationDir(a.schemaRealizationDir, outRoot);

        ResolvedModelSource resolvedModelSource = new ModelSourceResolver().resolve(modelPath);
        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(resolvedModelSource);

        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        if (!validation.getWarnings().isEmpty()) {
            for (String warning : validation.getWarnings()) {
                System.err.println("Validation warning: " + warning);
            }
        }
        if (validation.hasErrors()) {
            System.err.println("Semantic validation failed:");
            for (String e : validation.getErrors()) {
                System.err.println(" - " + e);
            }
            System.exit(2);
        }

        CompiledModel compiled = new ModelCompiler().compile(ast);

        // Compose the built-in NPDev internal tables (identity + workspace packs) when enabled.
        // basePackJsonByAlias accumulates every base pack's own raw pack.json, keyed by the alias it
        // was composed under -- GAP-1 needs this below to check an extension pack's TARGET for
        // sealedness, whether that target is a built-in pack (identity/workspace) or a plain
        // installed one.
        Map<String, JsonNode> basePackJsonByAlias = new LinkedHashMap<>();
        if (settingResolver.value(NpdevSettings.INTERNAL_TABLES, SettingTarget.app())) {
            ComposedBuiltinPacks builtin = composeBuiltinInternalTables(compiled, a.runtimeHostRoot);
            compiled = builtin.model();
            basePackJsonByAlias.putAll(builtin.packJsonByAlias());
        }

        // Compose any explicitly-installed third-party packs (config.json's packs.included list --
        // distinct from internal.tables/BUILTIN_PACK_ALIASES, which are platform packs surfaced
        // admin-only). An installed pack's concepts are ordinary business concepts, not admin-gated:
        // they are deliberately NOT added to BuiltinPackComposer.BUILTIN_PACK_ALIASES, which is what
        // every admin-only check (BusinessUiEmitter/RuntimeApiEmitter/BoxManifestEmitter) keys on.
        // GAP-1: an installed pack whose OWN pack.json declares `metadata.extends` (the convention
        // com.npdev.generator.packs.PackExtensionComposer already established and fully tested, but
        // that nothing in this real pipeline ever called) is instead additively patched onto its
        // declared target concept via PackExtensionComposer -- never added as an ordinary standalone
        // concept. See composeInstalledPacksAndExtensions for the split.
        List<String> installedPackAliases = readInstalledPackAliases(config);
        Map<String, Map<String, String>> extensionFieldOrigins = Map.of();
        if (!installedPackAliases.isEmpty()) {
            Path packsDir = locatePlatformPacksDir("packs.included is non-empty", a.runtimeHostRoot);
            ComposedInstalledPacks installed = composeInstalledPacksAndExtensions(
                    compiled, installedPackAliases, packsDir, basePackJsonByAlias, modelPath);
            compiled = installed.model();
            extensionFieldOrigins = installed.extensionFieldOrigins();
        }

        GeneratedDatabasePlan databasePlan = new UserDatabaseDefinitionLoader()
                .load(Path.of(a.dbDefinitionPath), compiled);
        System.out.println("DB definition: " + databasePlan.definitionPath());
        System.out.println("DB engine: " + databasePlan.engine().externalName());
        System.out.println("Storage mode: " + databasePlan.storageMode());
        System.out.println("Schema fingerprint: " + databasePlan.schemaFingerprint());

        // S3 (storage/PLAN.md §4): refuse a model the chosen engine cannot honour, HERE -- before a
        // single file is emitted. Refusing at boot, or at the first query that happens to take the
        // unsupported path, is the same defect as not refusing: the app looks healthy and loses
        // behaviour silently. With one SQL engine this never fires, which is exactly why it is built
        // now rather than alongside the second engine that would otherwise discover the need.
        com.npdev.generator.dbconfig.StorageCapabilityGate.verify(compiled, databasePlan);

        // LNCH-1 P6 (task 6.1): optional migration-plan computation, a thin adapter over
        // MigrationPlanEmitter's own pure logic -- this block does no diffing itself. Both flags
        // are optional and independent of each other: --previousCompiledModel alone (no
        // --schemaMigrationPlanOut) computes nothing; --schemaMigrationPlanOut alone (no previous
        // model) computes a "fresh install" plan. Absent both flags -- the ordinary case for every
        // existing caller -- this block is a no-op and behavior is unchanged.
        List<String> migrationPlanDestructiveItemStableStrings = List.of();
        if (normalize(a.migrationPlanOutPath) != null) {
            com.npdev.dsl.v1.compiled.CompiledModel previousModel = null;
            if (normalize(a.previousCompiledModelPath) != null) {
                Path previousModelPath = Path.of(a.previousCompiledModelPath).toAbsolutePath().normalize();
                previousModel = com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader.read(previousModelPath);
                System.out.println("Migration plan: previous compiled model read from " + previousModelPath);
            } else if (a.requirePreviousCompiledModel) {
                // LNCH-1 closeout C4 (finding C-B2 / LNCH-1-B8): refuse, don't degrade. The caller
                // asserted this app was previously deployed, so "no previous model" cannot honestly
                // be reported as a fresh install -- that emits an empty plan and a zero exit code,
                // which is the "safe to proceed" signal, for a database that may need a destructive
                // change. The generator has no database connection by design (it previews; the
                // executor decides), so it cannot check the truth itself -- which is exactly why it
                // must not guess.
                throw new IllegalStateException(
                        "--requirePreviousCompiledModel was given, but no --previousCompiledModel is available. "
                                + "The caller asserted this app has a prior deployment, so a plan computed now "
                                + "would report a FRESH INSTALL and exit successfully for a database that may "
                                + "well need a destructive change -- a wrong plan presented as a valid one "
                                + "(LNCH-1-B8). This usually means a previous generation run failed AFTER the "
                                + "output directory was wiped, destroying the compiled model the diff needs. "
                                + "Rebuild the app successfully once to restore a real starting point, or drop "
                                + "--requirePreviousCompiledModel if this genuinely is a first generation.");
            } else {
                System.out.println("Migration plan: no --previousCompiledModel given -- computing a fresh-install plan.");
            }
            com.npdev.generator.schemaevolution.MigrationPlan migrationPlan =
                    com.npdev.generator.schemaevolution.MigrationPlanEmitter.compute(compiled, previousModel, databasePlan);
            Path migrationPlanOutPath = Path.of(a.migrationPlanOutPath).toAbsolutePath().normalize();
            com.npdev.generator.schemaevolution.MigrationPlan.write(migrationPlanOutPath, migrationPlan);
            System.out.println("Migration plan written: " + migrationPlanOutPath
                    + " (freshInstall=" + migrationPlan.freshInstall()
                    + ", items=" + migrationPlan.items().size()
                    + ", destructiveAckToken=" + (migrationPlan.destructiveAckToken() == null ? "none" : "present") + ")");
            migrationPlanDestructiveItemStableStrings = migrationPlan.destructiveItemStableStrings();
        }

        TemplateEngine templates = new TemplateEngine("npdev-templates/");

        GeneratedSourceWriter writer =
                new GeneratedSourceWriter(outRoot, new RegenerationPolicy());

        new GeneratorFacade(templates, writer, settingResolver, installedPackAliases).generate(
                compiled,
                outRoot,
                schemaRealizationDir,
                resolvedModelSource,
                databasePlan,
                migrationPlanDestructiveItemStableStrings,
                normalize(a.destructiveAcknowledgmentToken),
                List.of(),
                extensionFieldOrigins
        );

        writer.flushSummary();

        System.out.println("Generation OK. Output: " + outRoot);
        System.out.println("Schema realization: " + schemaRealizationDir);

        // PK-4 Stage D: only after generation has actually succeeded end to end -- advancing this
        // any earlier (e.g. right after resolve()) would let a later, unrelated failure in this same
        // run (compile, emit, disk) leave the lock claiming a version's renamedFrom markers were
        // composed into a real generated app when no such output exists, which a later regenerate
        // would then wrongly treat as an already-replayed hop.
        if (!resolvedModelSource.migrationTrackedPacks().isEmpty()) {
            advanceMigrationTrackedLock(resolvedModelSource);
        }

        int javaVersion = resolveJavaVersion(config);
        System.out.println("Setting build.javaVersion = " + javaVersion);

        FinalAppAssemblyRequest assemblyRequest = resolveFinalAppAssemblyRequest(a, config, outRoot, schemaRealizationDir);
        if (assemblyRequest.shouldAssemble()) {
            FinalAppAssembler.AssemblyResult assemblyResult = new FinalAppAssembler().assemble(
                    new FinalAppAssembler.Options(
                            assemblyRequest.runtimeHostRoot(),
                            outRoot,
                            assemblyRequest.finalAppRoot(),
                            schemaRealizationDir,
                            assemblyRequest.generatedFolderName(),
                            assemblyRequest.metaFolderName(),
                            assemblyRequest.deleteBeforeMount(),
                            javaVersion,
                            assemblyRequest.webAssetsRoot()
                    )
            );

            System.out.println("Final app assembly OK. Root: " + assemblyResult.finalAppRoot());
            System.out.println("Generated mount: " + assemblyResult.generatedMount());
            System.out.println("Schema realization manifest: " + assemblyRequest.finalAppRoot()
                    .resolve("npdev-generated")
                    .resolve("src")
                    .resolve("main")
                    .resolve("resources")
                    .resolve("npdev")
                    .resolve("db")
                    .resolve("schema-realization-manifest.json")
                    .toAbsolutePath()
                    .normalize());
            System.out.println("RuntimeHost files copied: " + assemblyResult.runtimeHostFilesCopied());
            System.out.println("Generated files copied: " + assemblyResult.generatedFilesCopied());
            System.out.println("Schema realization artifacts copied: " + assemblyResult.schemaRealizationArtifactsCopied());
            System.out.println("Web assets copied: " + assemblyResult.webAssetsFilesCopied());
            Path opsRoot = new OperationalRunbookEmitter().emit(
                    compiled,
                    config,
                    assemblyResult.finalAppRoot(),
                    databasePlan
            );
            System.out.println("Generated operations runbook: " + opsRoot);

            new BuildInfoEmitter().emit(
                    compiled,
                    assemblyResult.finalAppRoot(),
                    modelPath,
                    a.configPath == null || a.configPath.isBlank() ? null : Path.of(a.configPath)
            );
            System.out.println("Generated build-info: " + assemblyResult.finalAppRoot()
                    .resolve(BuildInfoEmitter.RELATIVE_PATH).toAbsolutePath().normalize());

            new DockerDeploymentEmitter().emit(config, assemblyResult.finalAppRoot(), databasePlan);
            System.out.println("Generated Docker deployment: "
                    + assemblyResult.finalAppRoot().resolve("docker-compose.yml").toAbsolutePath().normalize());

            AppDependenciesEmitter dependenciesEmitter = new AppDependenciesEmitter();
            AppDependenciesEmitter.EmitResult dependenciesResult = dependenciesEmitter.emit(
                    config, assemblyResult.finalAppRoot(), assemblyResult.finalAppRoot().resolve("build.gradle"));
            if (dependenciesResult.wroteFile()) {
                System.out.println("Generated app dependencies: "
                        + assemblyResult.finalAppRoot().resolve(AppDependenciesEmitter.RELATIVE_PATH)
                                .toAbsolutePath().normalize());
                for (String warning : dependenciesResult.collisionWarnings()) {
                    System.out.println("WARNING (build.dependencies): " + warning);
                }
            }
            List<String> copiedLocalJars = dependenciesEmitter.copyLocalJars(modelPath, assemblyResult.finalAppRoot());
            if (!copiedLocalJars.isEmpty()) {
                System.out.println("Copied local jars into npdev-app-libs/: " + copiedLocalJars);
            }
        }
    }

    /** {@code packJsonByAlias}: the composed built-in packs' own raw pack.json, keyed by alias --
     *  GAP-1 needs these to check an extension pack's declared target for sealedness when that target
     *  is a built-in pack (identity/workspace). */
    private record ComposedBuiltinPacks(CompiledModel model, Map<String, JsonNode> packJsonByAlias) {
    }

    private static ComposedBuiltinPacks composeBuiltinInternalTables(CompiledModel app, String runtimeHostRoot) throws IOException {
        Path packsDir = locatePlatformPacksDir("internal.tables is enabled", runtimeHostRoot);
        BuiltinPackComposer composer = new BuiltinPackComposer();
        List<CompiledConcept> builtin = new java.util.ArrayList<>();
        Map<String, JsonNode> packJsonByAlias = new LinkedHashMap<>();
        for (String alias : BuiltinPackComposer.BUILTIN_PACK_ALIASES) {
            Path packFile = packsDir.resolve(alias).resolve("pack.json");
            builtin.addAll(composer.loadPackConcepts(packFile, alias));
            packJsonByAlias.put(alias, readJson(packFile));
        }
        System.out.println("Composed built-in internal tables (" + builtin.size() + " concepts) from " + packsDir);
        return new ComposedBuiltinPacks(composer.merge(app, builtin), packJsonByAlias);
    }

    /** {@code extensionFieldOrigins}: {@code
     *  com.npdev.generator.packs.PackExtensionComposer.ExtensionComposition#extensionFieldOrigins()} --
     *  empty when {@code aliases} declares no extension pack. Package-visible (not {@code private}) so
     *  {@code GeneratorMainPackExtensionCompositionTest} can assert on it directly -- same reason
     *  {@link #composeInstalledPacksAndExtensions} itself is package-visible. */
    record ComposedInstalledPacks(CompiledModel model, Map<String, Map<String, String>> extensionFieldOrigins) {
    }

    /**
     * Composes every pack explicitly named in config.json's {@code packs.included} list -- the
     * "install a pack" half of the author-ecosystem ask. Reuses {@code BuiltinPackComposer}'s
     * already-generic {@code loadPackConcepts}/{@code merge} (it never assumed identity/workspace
     * specifically; only the BUILTIN_PACK_ALIASES *list* it's normally driven by was hardcoded) for
     * every alias whose OWN pack.json declares no {@code metadata.extends} target -- those concepts
     * render as ordinary business concepts, not admin-gated internal tables, same as before.
     *
     * <p><b>GAP-1.</b> An alias whose pack.json DOES declare {@code metadata.extends} (the convention
     * {@code com.npdev.generator.packs.PackExtensionComposer} already established and fully unit-
     * tested, but that no real generation path ever invoked) is routed to {@link
     * PackExtensionComposer#composeExtensionsWithOrdering} instead -- additively patched onto its
     * declared target concept rather than added as a second standalone concept of its own. Any
     * refusal {@code composeExtensionsWithOrdering}/{@code applyExtension} raises (a field-shape
     * collision, a new required field, or a sealed target) propagates out of this method and fails
     * generation before anything is emitted, same as every other pre-emission gate in this class
     * (e.g. {@code StorageCapabilityGate.verify}).
     *
     * <p>{@code packsDir} is already resolved (by {@link #locatePlatformPacksDir}) rather than a
     * {@code runtimeHostRoot} string this method would resolve itself -- deliberately, so a test can
     * point it at a synthetic fixture directory without needing a real pack under the platform's own
     * {@code NPDevContract/packs} (every real pack in this repo is sealed -- see {@code
     * PackExtensionComposerTest}'s own class doc -- so no real pack could ever prove the SUCCESS path
     * here anyway; only the natural sealed-refusal is provable against a real one).
     */
    static ComposedInstalledPacks composeInstalledPacksAndExtensions(
            CompiledModel app,
            List<String> aliases,
            Path packsDir,
            Map<String, JsonNode> basePackJsonByAlias,
            Path modelPath
    ) throws IOException {
        BuiltinPackComposer builtinComposer = new BuiltinPackComposer();
        PackExtensionComposer extensionComposer = new PackExtensionComposer();

        List<CompiledConcept> ordinaryConcepts = new java.util.ArrayList<>();
        List<PackExtensionComposer.ExtensionSource> extensionSources = new java.util.ArrayList<>();
        Map<String, JsonNode> packJsonByAlias = new LinkedHashMap<>(basePackJsonByAlias);

        for (String alias : aliases) {
            Path packFile = packsDir.resolve(alias).resolve("pack.json");
            if (!Files.isRegularFile(packFile)) {
                throw new IllegalStateException(
                        "config.json's packs.included names \"" + alias + "\", but no pack was found at " + packFile);
            }
            JsonNode packJson = readJson(packFile);
            packJsonByAlias.put(alias, packJson);
            List<CompiledConcept> concepts = builtinComposer.loadPackConcepts(packFile, alias);

            PackExtensionComposer.ExtensionTarget target = extensionComposer.readExtensionTarget(packJson);
            if (target == null) {
                ordinaryConcepts.addAll(concepts);
                continue;
            }

            String qualifiedExtConceptName = alias + "::" + target.conceptName();
            CompiledConcept extensionConcept = concepts.stream()
                    .filter(c -> qualifiedExtConceptName.equals(c.getName()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "config.json's packs.included names extension pack \"" + alias
                                    + "\", whose metadata.extends targets '" + target.qualifiedName()
                                    + "' -- but that pack compiles no concept named '" + qualifiedExtConceptName
                                    + "' to carry the patch"));
            extensionSources.add(new PackExtensionComposer.ExtensionSource(alias, packJson, extensionConcept));
        }

        System.out.println("Composed installed packs " + aliases + " (" + ordinaryConcepts.size()
                + " ordinary concept(s), " + extensionSources.size() + " extension pack(s)) from " + packsDir);

        CompiledModel merged = builtinComposer.merge(app, ordinaryConcepts);

        if (extensionSources.isEmpty()) {
            return new ComposedInstalledPacks(merged, Map.of());
        }

        JsonNode appModelJson = readJson(modelPath);
        PackExtensionComposer.ExtensionComposition composition = extensionComposer.composeExtensionsWithOrdering(
                merged, packJsonByAlias, extensionSources, appModelJson);
        System.out.println("Composed pack extension(s) "
                + extensionSources.stream().map(PackExtensionComposer.ExtensionSource::extensionAlias).toList()
                + " -- " + composition.extensionFieldOrigins().values().stream().mapToInt(Map::size).sum()
                + " field(s) additively patched in");
        return new ComposedInstalledPacks(composition.model(), composition.extensionFieldOrigins());
    }

    private static JsonNode readJson(Path file) throws IOException {
        return OBJECT_MAPPER.readTree(file.toFile());
    }

    /**
     * PK-4 Stage D: advances {@code npdev.lock}'s {@code migratedVersion} bookkeeping for every
     * packId that declares a migration chain, now that this generate has produced real output for
     * it. Merges with any existing lock rather than overwriting it: a packId PK-3's own transitive-
     * dependency tracking already owns (has a real resolvedVersion/digest/sourcePath from {@code pack
     * add}/{@code update}) keeps those fields untouched here -- only {@code migratedVersion} is this
     * method's business. A packId with no prior lock entry at all (a direct import with no
     * transitive dependency of its own, the common case for a pack that merely uses a migration
     * chain) gets a fresh entry, creating {@code npdev.lock} for the first time if none existed --
     * scoped deliberately narrow: only when {@link ResolvedModelSource#migrationTrackedPacks()} is
     * non-empty, so an app that never touches a pack with a migration chain never gets a lock file
     * it didn't have before this card.
     */
    static void advanceMigrationTrackedLock(ResolvedModelSource resolvedModelSource) throws IOException {
        Path rootDirectory = resolvedModelSource.canonicalRootDirectory();
        Map<String, PackLockFile.LockedPack> merged = new LinkedHashMap<>();
        if (PackLockFile.exists(rootDirectory)) {
            merged.putAll(PackLockFile.read(rootDirectory).packs());
        }
        for (Map.Entry<String, PackLockFile.LockedPack> entry : resolvedModelSource.migrationTrackedPacks().entrySet()) {
            String packId = entry.getKey();
            PackLockFile.LockedPack fresh = entry.getValue();
            PackLockFile.LockedPack existing = merged.get(packId);
            // PK-5: must use the 5-arg constructor and thread `from` through, or a from-based
            // remote pack's coordinate is silently zeroed here on every generate that advances its
            // migratedVersion -- the very next generate/validate then fails the DENIED-path lock
            // lookup in PackDependencyGraphWalker.resolveRemotePackFile ("is not in npdev.lock"),
            // breaking PK-5's own core promise (fetch once via pack add, generate/validate offline
            // forever) for any from-based pack that also declares a migration chain. existing.from()
            // is authoritative when an existing entry is present (same precedent as
            // resolvedVersion/digest/sourcePath, all taken from existing below) -- it is also
            // guaranteed equal to fresh.from() whenever this pack came from `from` at all, since the
            // DENIED-path resolution that produced `fresh` this generate could only have succeeded by
            // matching the model's own `from` string against this exact existing lock entry.
            PackLockFile.LockedPack updated = existing != null
                    ? new PackLockFile.LockedPack(
                            existing.resolvedVersion(), existing.digest(), existing.sourcePath(),
                            fresh.resolvedVersion(), existing.from())
                    : new PackLockFile.LockedPack(
                            fresh.resolvedVersion(), fresh.digest(), fresh.sourcePath(),
                            fresh.resolvedVersion(), fresh.from());
            merged.put(packId, updated);
        }
        PackLockFile.of(merged).write(rootDirectory);
        System.out.println("Updated " + PackLockFile.FILE_NAME + " migratedVersion for: "
                + resolvedModelSource.migrationTrackedPacks().keySet());
    }

    /** Reads config.json's optional {@code packs.included} string array (defaults to none). */
    private static List<String> readInstalledPackAliases(JsonNode config) {
        if (config == null) {
            return List.of();
        }
        JsonNode included = config.path("packs").path("included");
        if (!included.isArray()) {
            return List.of();
        }
        List<String> aliases = new java.util.ArrayList<>();
        for (JsonNode alias : included) {
            if (alias.isTextual() && !alias.asText().isBlank()) {
                aliases.add(alias.asText().trim());
            }
        }
        return List.copyOf(aliases);
    }

    private static Path locatePlatformPacksDir(String reason, String runtimeHostRoot) {
        Path start = Path.of("").toAbsolutePath().normalize();
        Path workspaceRoot = resolveSplitWorkspaceRoot(start);
        if (workspaceRoot == null && runtimeHostRoot != null && !runtimeHostRoot.isBlank()) {
            // The CWD walk above assumes this process was launched from somewhere inside the
            // platform checkout. AppGen's "direct Java" fast generation path (Build-NpdevApp.ps1)
            // instead launches from the cached generator-runtime distribution under
            // AppGen/generator-runtime, which lives OUTSIDE any repo checkout by design (that is
            // what makes the cache portable) -- so the CWD walk can never succeed from there, and
            // internal.tables/packs.included failed unconditionally for every app built that way.
            // --runtimeHostTemplate already names NPDevRuntimeHost inside the real checkout (every
            // caller passes it, to stage the FinalApp's own template), so walking up from IT finds
            // the same workspace root the CWD walk was trying to find -- reusing the identical
            // content-based predicate (resolveSplitWorkspaceRoot), not a second resolution strategy.
            Path fromTemplate = Path.of(runtimeHostRoot).toAbsolutePath().normalize();
            workspaceRoot = resolveSplitWorkspaceRoot(fromTemplate);
        }
        if (workspaceRoot == null) {
            throw new IllegalStateException(
                    reason + " but the NPDev workspace root could not be located from "
                            + start + " or from --runtimeHostTemplate ('" + runtimeHostRoot
                            + "') -- needed to find NPDevContract/packs.");
        }
        Path packsDir = workspaceRoot.resolve("NPDevContract").resolve("packs");
        if (!Files.isDirectory(packsDir)) {
            throw new IllegalStateException(
                    reason + " but the platform packs directory was not found: " + packsDir);
        }
        return packsDir;
    }

    private static JsonNode readConfig(String configPath) throws IOException {
        if (configPath == null || configPath.isBlank()) {
            return null;
        }
        Path path = Path.of(configPath);
        if (!Files.isRegularFile(path)) {
            throw new IOException("Config file not found: " + path.toAbsolutePath().normalize());
        }
        return OBJECT_MAPPER.readTree(path.toFile());
    }

    private static Path resolveModelPath(Args args, JsonNode config) {
        if (args.modelPath != null && !args.modelPath.isBlank()) {
            return Path.of(args.modelPath).toAbsolutePath().normalize();
        }

        if (args.configPath != null && !args.configPath.isBlank()) {
            Path configSiblingModel = Path.of(args.configPath).toAbsolutePath().normalize().getParent().resolve("model.json");
            if (Files.exists(configSiblingModel)) {
                return configSiblingModel;
            }
        }

        return CanonicalModelPaths.defaultModelPath().toAbsolutePath().normalize();
    }

    private static Path resolveOutputRoot(Args args, JsonNode config, Path modelPath) {
        String explicit = normalize(args.outPath);
        if (explicit != null) {
            return Path.of(explicit).toAbsolutePath().normalize();
        }

        Path artifactRoot = resolveConfiguredPath(args.configPath, config, "artifact", "root");
        if (artifactRoot != null) {
            return artifactRoot;
        }

        Path scenarioOutputRoot = resolveConfiguredPath(args.configPath, config, "scenario", "outputRoot");
        if (scenarioOutputRoot != null) {
            return scenarioOutputRoot;
        }

        Path sampleOutputRoot = resolveSampleOutputRoot(modelPath);
        if (sampleOutputRoot != null) {
            return sampleOutputRoot.resolve("ArtifactNP").toAbsolutePath().normalize();
        }

        Path workspaceRoot = resolveSplitWorkspaceRoot(Path.of("").toAbsolutePath().normalize());
        if (workspaceRoot != null) {
            return workspaceRoot.resolve("NPDevSamples")
                    .resolve("canonical-demo")
                    .resolve("Output")
                    .resolve("ArtifactNP")
                    .toAbsolutePath()
                    .normalize();
        }

        return Path.of("out").resolve("ArtifactNP").toAbsolutePath().normalize();
    }

    private static boolean resolveCleanOutput(Args args, JsonNode config) {
        if (args.cleanOutExplicit) {
            return args.cleanOut;
        }
        return readBoolean(config, false, "generator", "cleanOutputBeforeGenerate");
    }

    /**
     * deps-and-java/PLAN.md W1.3, widened by ROUND2_PLAN.md R1c: config.json's optional
     * build.javaVersion, validated BEFORE any assembly/build work starts -- a request below the
     * platform's floor fails HERE, with a named reason, rather than surfacing four minutes later as
     * a bare Gradle toolchain-resolution stack trace. There is deliberately no upper bound: NPDev
     * does not maintain its own allowlist of "known good" Java versions that would need updating
     * every time a new JDK ships. Whether a given value ABOVE the floor actually resolves depends on
     * the generated app's own bundled Gradle wrapper/foojay resolver (NPDevRuntimeHost/gradle/wrapper)
     * -- that is Gradle's failure to report, honestly, not this method's to predict.
     */
    private static final int MINIMUM_APP_JAVA_VERSION = 17;

    static int resolveJavaVersion(JsonNode config) {
        if (config == null) {
            return 17;
        }
        JsonNode node = config.path("build").path("javaVersion");
        if (node.isMissingNode() || node.isNull()) {
            return 17;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException(
                    "config.json's build.javaVersion must be an integer (>= " + MINIMUM_APP_JAVA_VERSION
                            + "), found: " + node);
        }
        int requested = node.asInt();
        if (requested < MINIMUM_APP_JAVA_VERSION) {
            throw new IllegalArgumentException(
                    "config.json's build.javaVersion=" + requested + " is below the platform floor of "
                            + MINIMUM_APP_JAVA_VERSION + " -- NPDevRuntimeHost's own template/adapters are not "
                            + "verified below Java " + MINIMUM_APP_JAVA_VERSION + ". Any value >= "
                            + MINIMUM_APP_JAVA_VERSION + " is accepted here; whether it actually builds depends on "
                            + "what the generated app's own Gradle wrapper can resolve a JDK for.");
        }
        return requested;
    }

    static void rejectUnsupportedMigrationManagement(JsonNode config) {
        if (config == null || config.isNull() || config.isMissingNode()) {
            return;
        }

        for (String key : List.of("migrationManagement", "migrations", "schemaEvolution")) {
            JsonNode node = config.get(key);
            if (node == null || node.isNull() || node.isMissingNode()) {
                continue;
            }
            if (node.isBoolean() && node.asBoolean(false)) {
                throw migrationsDisabled(key);
            }
            if (node.isTextual()) {
                String value = node.asText("");
                if (!value.isBlank()
                        && !"disabled".equalsIgnoreCase(value)
                        && !"off".equalsIgnoreCase(value)
                        && !"false".equalsIgnoreCase(value)) {
                    throw migrationsDisabled(key);
                }
            }
            if (node.isObject()) {
                JsonNode enabled = node.get("enabled");
                if (enabled != null && enabled.isBoolean() && enabled.asBoolean(false)) {
                    throw migrationsDisabled(key + ".enabled");
                }
                JsonNode mode = node.get("mode");
                if (mode != null && mode.isTextual()) {
                    String value = mode.asText("");
                    if (!value.isBlank()
                            && !"disabled".equalsIgnoreCase(value)
                            && !"off".equalsIgnoreCase(value)) {
                        throw migrationsDisabled(key + ".mode");
                    }
                }
            }
        }
    }

    private static IllegalArgumentException migrationsDisabled(String source) {
        return new IllegalArgumentException(CONFIG_MIGRATIONS_DISABLED
                + ": stateful upgrade management is not supported by this generation path (source: "
                + source
                + "). Use recreate-style generation and schema realization instead.");
    }

    private static FinalAppAssemblyRequest resolveFinalAppAssemblyRequest(
            Args args,
            JsonNode config,
            Path outRoot,
            Path migrationsDir
    ) {
        Path runtimeHostRoot = firstNonBlank(args.runtimeHostRoot) != null
                ? resolveConfiguredPath(null, args.runtimeHostRoot)
                : resolveConfiguredPath(args.configPath, config, "bootstrap", "root");
        Path finalAppRoot = firstNonBlank(args.finalAppRoot) != null
                ? resolveConfiguredPath(null, args.finalAppRoot)
                : resolveConfiguredPath(args.configPath, config, "finalExec", "root");
        boolean pathsAvailable = runtimeHostRoot != null && finalAppRoot != null;
        boolean shouldAssemble = args.assembleFinalAppExplicit
                ? args.assembleFinalApp
                : pathsAvailable && (args.configPath != null || args.runtimeHostRoot != null || args.finalAppRoot != null);
        String generatedFolderName = firstNonBlank(
                args.generatedFolderName,
                readText(config, "artifact", "generatedFolderName"),
                "npdev-generated"
        );
        String metaFolderName = firstNonBlank(
                args.metaFolderName,
                readText(config, "artifact", "metaFolderName"),
                "npdev-meta"
        );
        boolean deleteBeforeMount = args.cleanFinalAppExplicit
                ? args.cleanFinalApp
                : readBoolean(config, false, "finalExec", "deleteBeforeMount");
        // R10 (EXT-1, "custom-screen mount"): explicit-only, no filesystem-convention guessing --
        // matches every other path option here (--runtimeHostTemplate, --finalAppOut, ...), which
        // are all caller-resolved rather than derived from --model's location. Each caller (
        // Build-NpdevApp.ps1, Build-ClaudeApp.ps1, `npdev generate app`) computes this path using
        // its OWN app-layout convention and passes it explicitly; null means "no companion web
        // assets for this app" -- zero behavior change for every caller that omits the flag.
        Path webAssetsRoot = firstNonBlank(args.webAssetsRoot) != null
                ? resolveConfiguredPath(null, args.webAssetsRoot)
                : null;

        return new FinalAppAssemblyRequest(
                shouldAssemble,
                runtimeHostRoot,
                finalAppRoot,
                outRoot,
                migrationsDir,
                generatedFolderName,
                metaFolderName,
                deleteBeforeMount,
                webAssetsRoot
        );
    }

    private static Path resolveSchemaRealizationDir(String schemaRealizationDirArg, Path outRoot) throws IOException {
        Path schemaRealizationDir;
        if (schemaRealizationDirArg != null && !schemaRealizationDirArg.isBlank()) {
            schemaRealizationDir = Path.of(schemaRealizationDirArg);
        } else {
            schemaRealizationDir = outRoot.resolve("src").resolve("main").resolve("resources")
                    .resolve("db").resolve("schema-realization");
        }

        Files.createDirectories(schemaRealizationDir);
        return schemaRealizationDir.toAbsolutePath().normalize();
    }

    private static Path resolveSampleOutputRoot(Path modelPath) {
        if (modelPath == null) {
            return null;
        }

        Path normalizedModelPath = modelPath.toAbsolutePath().normalize();
        Path inputRoot = normalizedModelPath.getParent();
        if (inputRoot == null || inputRoot.getFileName() == null) {
            return null;
        }

        if (!"Input".equalsIgnoreCase(inputRoot.getFileName().toString())) {
            return null;
        }

        Path sampleRoot = inputRoot.getParent();
        if (sampleRoot == null) {
            return null;
        }

        return sampleRoot.resolve("Output");
    }

    private static Path resolveSplitWorkspaceRoot(Path start) {
        Path current = start;
        while (current != null) {
            if (Files.isDirectory(current.resolve("NPDevContract"))
                    && Files.isDirectory(current.resolve("NPDevGenerator"))
                    && Files.isDirectory(current.resolve("NPDevKernel"))
                    && Files.isDirectory(current.resolve("NPDevRuntimeHost"))
                    && Files.isDirectory(current.resolve("NPDevSamples"))) {
                return current.toAbsolutePath().normalize();
            }
            current = current.getParent();
        }
        return null;
    }

    private static String readText(JsonNode root, String... path) {
        if (root == null || path == null || path.length == 0) {
            return null;
        }

        JsonNode current = root;
        for (String element : path) {
            if (current == null) {
                return null;
            }
            current = current.path(element);
        }

        if (current == null || !current.isTextual()) {
            return null;
        }
        return normalize(current.asText());
    }

    private static boolean readBoolean(JsonNode root, boolean fallback, String... path) {
        if (root == null || path == null || path.length == 0) {
            return fallback;
        }

        JsonNode current = root;
        for (String element : path) {
            if (current == null) {
                return fallback;
            }
            current = current.path(element);
        }

        return current != null && current.isBoolean() ? current.asBoolean() : fallback;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = normalize(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    static Path resolveConfiguredPath(String configPath, JsonNode config, String... jsonPath) {
        return resolveConfiguredPath(configPath, readText(config, jsonPath));
    }

    static Path resolveConfiguredPath(String configPath, String configuredPath) {
        String normalized = normalize(configuredPath);
        if (normalized == null) {
            return null;
        }
        // LNCH-20: config.json files in this repo are authored with Windows-style backslash
        // paths (e.g. "..\\Output"). Path.of() only treats '\' as a separator on Windows --
        // on Linux/macOS the whole string becomes one literal (wrong) path segment. '/' is a
        // valid separator on every OS Java runs on, including Windows, so normalizing to it
        // here makes the same config.json resolve correctly regardless of host OS (confirmed
        // live: this was a real CI failure on a Linux runner, not a hypothetical).
        normalized = normalized.replace('\\', '/');

        Path resolved = Path.of(normalized);
        if (!resolved.isAbsolute()) {
            Path configBaseDir = resolveConfigBaseDir(configPath);
            if (configBaseDir != null) {
                resolved = configBaseDir.resolve(resolved);
            }
        }

        return resolved.toAbsolutePath().normalize();
    }

    static Path resolveConfigBaseDir(String configPath) {
        String normalized = normalize(configPath);
        if (normalized == null) {
            return null;
        }

        Path configFile = Path.of(normalized).toAbsolutePath().normalize();
        return configFile.getParent();
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private static void cleanOutputRoot(Path outRoot) throws IOException {
        if (!Files.exists(outRoot)) {
            Files.createDirectories(outRoot);
            return;
        }

        try (var stream = Files.walk(outRoot)) {
            stream
                    .sorted(Comparator.reverseOrder())
                    .filter(p -> !p.equals(outRoot))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed cleaning output path: " + p, e);
                        }
                    });
        }
    }

    private static final class Args {
        final String configPath;
        final String modelPath;
        final String outPath;
        final String dbDefinitionPath;
        final boolean cleanOut;
        final boolean cleanOutExplicit;
        final String schemaRealizationDir;
        final String runtimeHostRoot;
        final String finalAppRoot;
        final boolean assembleFinalApp;
        final boolean assembleFinalAppExplicit;
        final boolean cleanFinalApp;
        final boolean cleanFinalAppExplicit;
        final String generatedFolderName;
        final String metaFolderName;
        /** LNCH-1 P6 (task 6.1). Optional: the previous FinalApp output's canonical compiled-model
         * JSON (see {@code MigrationPlanEmitter}'s javadoc for exactly where that lives). Absent
         * means "fresh install" -- no migration plan is computed. Deliberately named without a
         * "--migration" prefix so it is never caught by {@link #rejectUnsupportedMigrationManagement}
         * /{@code cur.startsWith("--migration")}'s quarantine of the OLD, unsupported migration-
         * management CLI contract (§2.2 of the plan) -- this is a NEW, sanctioned mechanism. */
        final String previousCompiledModelPath;
        /** LNCH-1 P6 (task 6.1). Optional: where to write the computed {@code migration-plan.json}.
         * Absent means "skip plan computation entirely" -- zero behavior change for every existing
         * caller that doesn't pass this flag. */
        final String migrationPlanOutPath;
        /** LNCH-1 P6 (task 6.2b). Optional: an itemized destructive-acknowledgment token (see
         * {@code com.npdev.dsl.v1.schemaevolution.DestructiveAckToken}), written verbatim into the
         * generated manifest's {@code destructiveAcknowledgment} key -- the ONE thing that lets
         * {@code SchemaLifecycleExecutor}'s Phase 4 destructive-path token check actually pass for a
         * real generated app (Session A's {@code planItemStableStrings}/{@code destructiveAcknowledgment}
         * manifest field existed since Phase 4/6.3 but nothing generator-side populated it with a real
         * value until this flag). Absent means "" (unchanged manifest shape from every prior phase).
         * Deliberately named without a "--migration" prefix for the same reason as the two flags
         * above -- {@link #rejectUnsupportedMigrationManagement}'s {@code cur.startsWith("--migration")}
         * quarantine only matches that literal prefix; verified by reading it before picking this name. */
        final String destructiveAcknowledgmentToken;
        /** LNCH-1 closeout C4 (finding C-B2 / LNCH-1-B8). Optional: when set, a plan requested
         * WITHOUT {@code --previousCompiledModel} is a hard error instead of a silent fresh-install
         * plan. A caller passes this when it KNOWS the app was previously deployed -- knowledge the
         * generator cannot have, and must not guess at (it has no database connection, by design:
         * the generator previews, the executor decides). Without it, "no previous model" is
         * genuinely ambiguous between a first generation and a lost one, and the honest default for
         * an ambiguous case is the existing fresh-install plan. */
        final boolean requirePreviousCompiledModel;
        /** R10 (EXT-1, "custom-screen mount"). Optional: a directory of author-supplied,
         *  hand-written web screens, mounted verbatim into the assembled app's own
         *  {@code src/main/resources/static} (see {@code FinalAppAssembler.Options#webAssetsRoot}).
         *  Absent means no mount -- zero behavior change for every existing caller. */
        final String webAssetsRoot;

        private Args(
                String configPath,
                String modelPath,
                String outPath,
                String dbDefinitionPath,
                boolean cleanOut,
                boolean cleanOutExplicit,
                String schemaRealizationDir,
                String runtimeHostRoot,
                String finalAppRoot,
                boolean assembleFinalApp,
                boolean assembleFinalAppExplicit,
                boolean cleanFinalApp,
                boolean cleanFinalAppExplicit,
                String generatedFolderName,
                String metaFolderName,
                String previousCompiledModelPath,
                String migrationPlanOutPath,
                String destructiveAcknowledgmentToken,
                boolean requirePreviousCompiledModel,
                String webAssetsRoot
        ) {
            this.requirePreviousCompiledModel = requirePreviousCompiledModel;
            this.configPath = configPath;
            this.modelPath = modelPath;
            this.outPath = outPath;
            this.dbDefinitionPath = dbDefinitionPath;
            this.cleanOut = cleanOut;
            this.cleanOutExplicit = cleanOutExplicit;
            this.schemaRealizationDir = schemaRealizationDir;
            this.runtimeHostRoot = runtimeHostRoot;
            this.finalAppRoot = finalAppRoot;
            this.previousCompiledModelPath = previousCompiledModelPath;
            this.migrationPlanOutPath = migrationPlanOutPath;
            this.destructiveAcknowledgmentToken = destructiveAcknowledgmentToken;
            this.assembleFinalApp = assembleFinalApp;
            this.assembleFinalAppExplicit = assembleFinalAppExplicit;
            this.cleanFinalApp = cleanFinalApp;
            this.cleanFinalAppExplicit = cleanFinalAppExplicit;
            this.generatedFolderName = generatedFolderName;
            this.metaFolderName = metaFolderName;
            this.webAssetsRoot = webAssetsRoot;
        }

        static Args parse(String[] args) {
            String config = null;
            String model = null;
            String out = null;
            String dbDefinition = null;

            // Professional default: do NOT clean unless explicitly requested.
            boolean clean = false;
            boolean cleanExplicit = false;

            String schemaRealizationDir = null;
            String runtimeHost = null;
            String finalApp = null;
            boolean assemble = false;
            boolean assembleExplicit = false;
            boolean cleanFinal = false;
            boolean cleanFinalExplicit = false;
            String generatedFolder = null;
            String metaFolder = null;
            String previousCompiledModelPath = null;
            String migrationPlanOutPath = null;
            String destructiveAcknowledgmentToken = null;
            boolean requirePreviousCompiledModel = false;
            String webAssetsRoot = null;

            for (int i = 0; i < args.length; i++) {
                String cur = args[i];

                if (cur.startsWith("--migration") || cur.startsWith("--enableMigrations")) {
                    throw migrationsDisabled(cur);
                } else if ("--config".equals(cur) && i + 1 < args.length) {
                    config = args[++i];
                } else if ("--model".equals(cur) && i + 1 < args.length) {
                    model = args[++i];
                } else if ("--out".equals(cur) && i + 1 < args.length) {
                    out = args[++i];
                } else if ("--dbDefinitionPath".equals(cur) && i + 1 < args.length) {
                    dbDefinition = args[++i];
                } else if ("--schemaRealizationDir".equals(cur) && i + 1 < args.length) {
                    schemaRealizationDir = args[++i];
                } else if ("--runtimeHostTemplate".equals(cur) && i + 1 < args.length) {
                    runtimeHost = args[++i];
                } else if ("--finalAppOut".equals(cur) && i + 1 < args.length) {
                    finalApp = args[++i];
                } else if ("--generatedFolderName".equals(cur) && i + 1 < args.length) {
                    generatedFolder = args[++i];
                } else if ("--metaFolderName".equals(cur) && i + 1 < args.length) {
                    metaFolder = args[++i];
                } else if ("--webAssetsRoot".equals(cur) && i + 1 < args.length) {
                    webAssetsRoot = args[++i];
                } else if ("--previousCompiledModel".equals(cur) && i + 1 < args.length) {
                    previousCompiledModelPath = args[++i];
                } else if ("--schemaMigrationPlanOut".equals(cur) && i + 1 < args.length) {
                    migrationPlanOutPath = args[++i];
                } else if ("--destructiveAcknowledgment".equals(cur) && i + 1 < args.length) {
                    destructiveAcknowledgmentToken = args[++i];
                } else if ("--requirePreviousCompiledModel".equals(cur)) {
                    requirePreviousCompiledModel = true;
                } else if ("--assembleFinalApp".equals(cur)) {
                    assemble = true;
                    assembleExplicit = true;
                } else if ("--no-assembleFinalApp".equals(cur)) {
                    assemble = false;
                    assembleExplicit = true;
                } else if ("--clean".equals(cur)) {
                    clean = true;
                    cleanExplicit = true;
                } else if ("--no-clean".equals(cur)) {
                    clean = false;
                    cleanExplicit = true;
                } else if ("--cleanFinalApp".equals(cur)) {
                    cleanFinal = true;
                    cleanFinalExplicit = true;
                } else if ("--no-cleanFinalApp".equals(cur)) {
                    cleanFinal = false;
                    cleanFinalExplicit = true;
                }
            }

            if (normalize(dbDefinition) == null) {
                throw new IllegalArgumentException("--dbDefinitionPath is required");
            }

            return new Args(
                    config,
                    model,
                    out,
                    dbDefinition,
                    clean,
                    cleanExplicit,
                    schemaRealizationDir,
                    runtimeHost,
                    finalApp,
                    assemble,
                    assembleExplicit,
                    cleanFinal,
                    cleanFinalExplicit,
                    generatedFolder,
                    metaFolder,
                    previousCompiledModelPath,
                    migrationPlanOutPath,
                    destructiveAcknowledgmentToken,
                    requirePreviousCompiledModel,
                    webAssetsRoot
            );
        }
    }

    private record FinalAppAssemblyRequest(
            boolean shouldAssemble,
            Path runtimeHostRoot,
            Path finalAppRoot,
            Path generatedArtifactRoot,
            Path migrationsDir,
            String generatedFolderName,
            String metaFolderName,
            boolean deleteBeforeMount,
            Path webAssetsRoot
    ) {
    }
}
