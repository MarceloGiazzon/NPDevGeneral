package com.npdev.generator.api;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.ResolvedSetting;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.generator.emitters.BusinessUiEmitter;
import com.npdev.generator.emitters.ControllerEmitter;
import com.npdev.generator.emitters.DtoEmitter;
import com.npdev.generator.emitters.EntityEmitter;
import com.npdev.generator.emitters.GeneratedFolderSignatureEmitter;
import com.npdev.generator.emitters.InfoPageEmitter;
import com.npdev.generator.emitters.MetadataManifestAssetEmitter;
import com.npdev.generator.emitters.ModelSurfaceEmitter;
import com.npdev.generator.emitters.PluginRequirementAssetEmitter;
import com.npdev.generator.emitters.RuntimeApiEmitter;
import com.npdev.generator.emitters.RuntimeAuthPropertiesEmitter;
import com.npdev.generator.emitters.RuntimeLogPropertiesEmitter;
import com.npdev.generator.emitters.ServiceEmitter;
import com.npdev.generator.emitters.TrustedSourceEmitter;
import com.npdev.generator.emitters.XrefEmitter;
import com.npdev.generator.dbconfig.ConversionHookEmitter;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaRealizationEmitter;
import com.npdev.generator.dbconfig.DatabaseEngine;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import com.npdev.generator.dbconfig.SchemaLifecycleStrategy;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.provenance.BoxManifestEmitter;
import com.npdev.generator.provenance.PackCatalogEmitter;
import com.npdev.generator.settings.SettingsManifestEmitter;
import com.npdev.generator.templates.TemplateEngine;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

public final class GeneratorFacade {

    private final TemplateEngine templates;
    private final GeneratedSourceWriter writer;
    private final SettingResolver settingResolver;
    private final List<String> installedPackAliases;

    public GeneratorFacade(TemplateEngine templates, GeneratedSourceWriter writer) {
        this(templates, writer, new SettingResolver(SettingStore.empty()));
    }

    public GeneratorFacade(TemplateEngine templates, GeneratedSourceWriter writer, SettingResolver settingResolver) {
        this(templates, writer, settingResolver, List.of());
    }

    /** {@code installedPackAliases} mirrors config.json's packs.included list (see GeneratorMain) --
     *  threaded through only so PackCatalogEmitter can mark these packs "included" in the Store
     *  catalog; composition itself already happened before this class runs. */
    public GeneratorFacade(
            TemplateEngine templates,
            GeneratedSourceWriter writer,
            SettingResolver settingResolver,
            List<String> installedPackAliases
    ) {
        this.templates = templates;
        this.writer = writer;
        this.settingResolver = settingResolver == null ? new SettingResolver(SettingStore.empty()) : settingResolver;
        this.installedPackAliases = installedPackAliases == null ? List.of() : List.copyOf(installedPackAliases);
    }

    public void generate(CompiledModel model, Path outRoot, Path schemaRealizationDir) throws Exception {
        generate(model, outRoot, schemaRealizationDir, (Path) null, legacyInMemoryPlan(model, outRoot, schemaRealizationDir, null));
    }

    public void generate(CompiledModel model, Path outRoot, Path schemaRealizationDir, Path modelSourcePath) throws Exception {
        generate(model, outRoot, schemaRealizationDir, modelSourcePath, legacyInMemoryPlan(model, outRoot, schemaRealizationDir, modelSourcePath));
    }

    /**
     * BUILD-2: convenience twin of the 4-arg {@link #generate(CompiledModel, Path, Path, Path)}
     * above, for callers/tests that want {@code linkedSealedPackAliases} without also having to
     * construct a real {@link ResolvedModelSource}/{@link GeneratedDatabasePlan} -- see the widest
     * overload's own doc for what the alias list does.
     */
    public void generate(
            CompiledModel model,
            Path outRoot,
            Path schemaRealizationDir,
            Path modelSourcePath,
            List<String> linkedSealedPackAliases
    ) throws Exception {
        generate(model, outRoot, schemaRealizationDir, null, modelSourcePath,
                legacyInMemoryPlan(model, outRoot, schemaRealizationDir, modelSourcePath),
                List.of(), null, linkedSealedPackAliases);
    }

    public void generate(CompiledModel model, Path outRoot, Path schemaRealizationDir, GeneratedDatabasePlan databasePlan) throws Exception {
        generate(model, outRoot, schemaRealizationDir, (Path) null, databasePlan);
    }

    public void generate(
            CompiledModel model,
            Path outRoot,
            Path schemaRealizationDir,
            Path modelSourcePath,
            GeneratedDatabasePlan databasePlan
    ) throws Exception {
        generate(model, outRoot, schemaRealizationDir, null, modelSourcePath, databasePlan);
    }

    public void generate(
            CompiledModel model,
            Path outRoot,
            Path schemaRealizationDir,
            ResolvedModelSource resolvedModelSource,
            GeneratedDatabasePlan databasePlan
    ) throws Exception {
        generate(model, outRoot, schemaRealizationDir, resolvedModelSource, databasePlan, List.of());
    }

    /**
     * LNCH-1 P6 (task 6.1/6.3): {@code migrationPlanDestructiveItemStableStrings} is
     * {@code com.npdev.generator.schemaevolution.MigrationPlan#destructiveItemStableStrings()},
     * threaded down to {@link SchemaRealizationEmitter}'s manifest (task 6.3's agreement-check
     * enrichment) when {@code GeneratorMain} computed a migration plan this generation pass (its
     * new, optional {@code --previous-compiled-model}/{@code --migration-plan-out} flags). Empty
     * for every existing caller -- zero behavior change when no plan was computed.
     */
    public void generate(
            CompiledModel model,
            Path outRoot,
            Path schemaRealizationDir,
            ResolvedModelSource resolvedModelSource,
            GeneratedDatabasePlan databasePlan,
            List<String> migrationPlanDestructiveItemStableStrings
    ) throws Exception {
        generate(model, outRoot, schemaRealizationDir, resolvedModelSource, databasePlan,
                migrationPlanDestructiveItemStableStrings, null);
    }

    /**
     * LNCH-1 P6 (task 6.2b): {@code destructiveAcknowledgmentToken} is
     * {@code GeneratorMain}'s new, optional {@code --destructiveAcknowledgment} CLI flag, threaded
     * down to {@link SchemaRealizationEmitter}'s manifest verbatim as the {@code destructiveAcknowledgment}
     * key -- the value {@code SchemaLifecycleExecutor}'s Phase 4 token check reads at boot. {@code null}
     * for every existing caller -- zero behavior change (the manifest key is emitted as {@code ""},
     * matching the shape every prior phase already produced).
     */
    public void generate(
            CompiledModel model,
            Path outRoot,
            Path schemaRealizationDir,
            ResolvedModelSource resolvedModelSource,
            GeneratedDatabasePlan databasePlan,
            List<String> migrationPlanDestructiveItemStableStrings,
            String destructiveAcknowledgmentToken
    ) throws Exception {
        generate(
                model,
                outRoot,
                schemaRealizationDir,
                resolvedModelSource,
                databasePlan,
                migrationPlanDestructiveItemStableStrings,
                destructiveAcknowledgmentToken,
                List.of()
        );
    }

    /**
     * BUILD-2 (BT-2's own "the linking" follow-on, ledger item BUILD-2): {@code
     * linkedSealedPackAliases} names {@code BuiltinPackComposer}-alias-prefixed packs (e.g. {@code
     * "identity"}, matching {@code identity::User}-style concept names) whose concepts this app links
     * as a precompiled sealed jar (see {@code com.npdev.generator.packs.SealedPackJarBuilder}) rather
     * than generating their own entity/DTO/service/controller/REST/business-UI sources. Empty for
     * every existing caller -- zero behavior change.
     *
     * <p><b>Narrow by design, not by accident.</b> Only the six emitters that produce that pack's own
     * Java/REST/UI surface skip the linked pack's concepts ({@link EntityEmitter}, {@link DtoEmitter},
     * {@link ServiceEmitter}, {@link ControllerEmitter}, {@link RuntimeApiEmitter}, {@link
     * BusinessUiEmitter}) -- every OTHER emitter in this method (schema realization, the model-xref,
     * the model surface, the metadata manifest, ...) still sees the FULL model, because a linked
     * pack's physical tables/DDL and cross-catalog metadata are unrelated to whether its Java sources
     * exist locally. This is why linking is NOT (yet) a path to a linked pack actually SERVING CRUD:
     * with no controller emitted for it anywhere (the sealed jar carries no REST layer of its own --
     * see {@code SealedPackJarBuilder}'s own class doc), a linked pack's concepts become unreachable
     * over HTTP rather than broken -- a real, intentionally left gap, not silently papered over.
     */
    public void generate(
            CompiledModel model,
            Path outRoot,
            Path schemaRealizationDir,
            ResolvedModelSource resolvedModelSource,
            GeneratedDatabasePlan databasePlan,
            List<String> migrationPlanDestructiveItemStableStrings,
            String destructiveAcknowledgmentToken,
            List<String> linkedSealedPackAliases
    ) throws Exception {
        generate(
                model,
                outRoot,
                schemaRealizationDir,
                resolvedModelSource,
                resolvedModelSource == null ? null : resolvedModelSource.rootModelPath(),
                databasePlan,
                migrationPlanDestructiveItemStableStrings,
                destructiveAcknowledgmentToken,
                linkedSealedPackAliases
        );
    }

    private void generate(
            CompiledModel model,
            Path outRoot,
            Path schemaRealizationDir,
            ResolvedModelSource resolvedModelSource,
            Path modelSourcePath,
            GeneratedDatabasePlan databasePlan
    ) throws Exception {
        generate(model, outRoot, schemaRealizationDir, resolvedModelSource, modelSourcePath, databasePlan,
                List.of(), null, List.of());
    }

    private void generate(
            CompiledModel model,
            Path outRoot,
            Path schemaRealizationDir,
            ResolvedModelSource resolvedModelSource,
            Path modelSourcePath,
            GeneratedDatabasePlan databasePlan,
            List<String> migrationPlanDestructiveItemStableStrings,
            String destructiveAcknowledgmentToken,
            List<String> linkedSealedPackAliases
    ) throws Exception {
        // REG-44: fail BEFORE emitting anything. A model that declares row-level access rules while
        // crud.kernelControlled is false would generate an app that silently enforces neither them nor
        // any coarse CRUD permission check -- see UnenforceableAccessRuleCheck for why that is an error
        // rather than a warning, and why the check cannot live in SemanticValidator.
        UnenforceableAccessRuleCheck.verify(model, settingResolver);

        boolean kernelControlled = settingResolver.value(NpdevSettings.CRUD_KERNEL_CONTROLLED, SettingTarget.app());
        String superUserRole = settingResolver.value(NpdevSettings.SECURITY_SUPER_USER_ROLE, SettingTarget.app());
        boolean internalTablesEnabled = settingResolver.value(NpdevSettings.INTERNAL_TABLES, SettingTarget.app());

        // BUILD-2: the six Java/REST/UI-source emitters below see a NARROWER model when a pack is
        // linked as a sealed jar; every other emitter downstream keeps the FULL `model` -- see this
        // method's own overload doc for exactly why.
        CompiledModel appOwnedSourceModel = excludeLinkedSealedPackConcepts(model, linkedSealedPackAliases);

        new EntityEmitter(templates, writer).emit(appOwnedSourceModel);
        new DtoEmitter(templates, writer).emit(appOwnedSourceModel);
        new ServiceEmitter(templates, writer).emit(appOwnedSourceModel, kernelControlled, settingResolver);
        new ControllerEmitter(templates, writer).emit(appOwnedSourceModel);

        new RuntimeApiEmitter(templates, writer).emit(appOwnedSourceModel, resolvedModelSource, modelSourcePath, superUserRole);
        new InfoPageEmitter(templates, writer).emit(model, databasePlan);
        // R10.2: schema-driven model surface, emitted unconditionally (like info.html) rather than
        // gated on UI_GENERATE_BUSINESS_UI -- it walks the canonical model JSON itself, not the
        // business UI's panels, so it has no dependency on that flag being on.
        new ModelSurfaceEmitter(templates, writer).emit(model);
        if (settingResolver.value(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app())) {
            new BusinessUiEmitter(templates, writer).emit(appOwnedSourceModel, superUserRole, settingResolver);
            // Phase 7: provenance/store/box-view admin surfaces ride along with the business UI,
            // since they are only reachable through its super-user admin nav.
            new BoxManifestEmitter().emit(model, writer);
            new PackCatalogEmitter().emit(writer, internalTablesEnabled, installedPackAliases);
        }
        new TrustedSourceEmitter(writer).emit(model, modelSourcePath);
        new MetadataManifestAssetEmitter(writer).emit(model, resolvedModelSource, modelSourcePath);

        // Stage 3: emit deterministic plugin requirement asset derived from the model source.
        new PluginRequirementAssetEmitter(writer).emit(resolvedModelSource, modelSourcePath);

        // XREF-1: the model-wide reference index, npdev/model-xref.json. Emitted from the same
        // model source the two emitters above read, so an app carries the answer to "what
        // references this field?" without a rebuild.
        new XrefEmitter(writer).emit(resolvedModelSource, modelSourcePath);

        // Auth: when the model personalizes auth.mode, emit the runtime auth properties that drive it.
        ResolvedSetting<String> authMode = settingResolver.resolve(NpdevSettings.AUTH_MODE, SettingTarget.app());
        if (authMode.isOverridden()) {
            new RuntimeAuthPropertiesEmitter(writer).emit(authMode.value());
        }

        // Logging: when the model personalizes log.enabled/log.level, emit the real
        // logging.level.root property that drives them. Unpersonalized apps emit nothing and keep
        // the RuntimeHost profile defaults -- these settings used to resolve in
        // resolved-settings.json without affecting anything; this is their real consumer.
        ResolvedSetting<Boolean> logEnabled = settingResolver.resolve(NpdevSettings.LOG_ENABLED, SettingTarget.app());
        ResolvedSetting<String> logLevel = settingResolver.resolve(NpdevSettings.LOG_LEVEL, SettingTarget.app());
        if (logEnabled.isOverridden() || logLevel.isOverridden()) {
            new RuntimeLogPropertiesEmitter(writer).emit(logEnabled.value(), logLevel.value());
        }

        // Provenance: record the resolved settings cascade so it is inspectable in the generated app.
        new SettingsManifestEmitter(writer).emit(settingResolver);

        new SchemaRealizationEmitter().emit(model, outRoot, databasePlan, modelSourcePath,
                migrationPlanDestructiveItemStableStrings, destructiveAcknowledgmentToken);
        // SER-P7.2: operator-authored conversion hooks (definition/migrations/<ordinal>-<slug>/), opt-in
        // -- a no-op when the app declares none. S7 Phase B (B13): also compiles model.conversions[]
        // (the declarative vocabulary) to the same destination -- one execution path, not two.
        // W1.3: the engine, from the plan already in scope two lines above. Before this the
        // emitter asked H2 unconditionally, which was the narrower of the only two engines
        // that existed -- and stops being safe with a third (MySQL has no native UUID).
        new ConversionHookEmitter(databasePlan == null ? null : databasePlan.engine())
                .emit(model, modelSourcePath, outRoot);
        new GeneratedFolderSignatureEmitter().emit(outRoot);
    }

    /**
     * BUILD-2: returns {@code model} unchanged when {@code linkedSealedPackAliases} is empty (every
     * existing caller) -- otherwise a copy with every concept whose qualified name starts with
     * {@code "<alias>::"} (the SAME alias-prefix convention {@code BuiltinPackComposer} already
     * establishes for {@code identity::User}-style names) removed, so the six Java/REST/UI emitters
     * this feeds simply never see that pack's concepts and therefore never emit anything for them --
     * the mechanism {@code linkedSealedPackAliases}'s own doc describes.
     */
    private static CompiledModel excludeLinkedSealedPackConcepts(CompiledModel model, List<String> linkedSealedPackAliases) {
        if (linkedSealedPackAliases == null || linkedSealedPackAliases.isEmpty()) {
            return model;
        }
        List<String> prefixes = linkedSealedPackAliases.stream()
                .filter(alias -> alias != null && !alias.isBlank())
                .map(alias -> alias.trim() + "::")
                .toList();
        if (prefixes.isEmpty()) {
            return model;
        }

        java.util.LinkedHashMap<String, com.npdev.dsl.v1.compiled.CompiledConcept> filtered = new java.util.LinkedHashMap<>();
        for (com.npdev.dsl.v1.compiled.CompiledConcept concept : model.getConcepts()) {
            String name = concept.getName();
            boolean linked = name != null && prefixes.stream().anyMatch(name::startsWith);
            if (!linked) {
                filtered.put(name, concept);
            }
        }

        // Same widest constructor BuiltinPackComposer.merge already uses (and the same comment about
        // WHY it must be the widest one -- a narrower overload has silently dropped whole model
        // catalogs in the past, see that class's own note): every OTHER catalog is carried over
        // unchanged, only `concepts` differs.
        return new CompiledModel(
                model.getNamespace(),
                model.getDslVersion(),
                model.getVersion(),
                filtered,
                model.getDomainTypes(),
                model.getCapabilities(),
                model.getBindings(),
                model.getEvents(),
                model.getFlows(),
                model.getOrchestrationRules(),
                model.getQueries(),
                model.getRuleProfiles(),
                model.getProcedures(),
                model.getPanels(),
                model.getGuidePages(),
                model.getAggregates(),
                model.getAutoPanels(),
                model.getDocuments(),
                model.getExternalAi(),
                model.getSettings(),
                model.getRoles(),
                model.getPropertyScopes(),
                model.getProperties(),
                model.getContexts(),
                model.getConversions()
        );
    }

    private static GeneratedDatabasePlan legacyInMemoryPlan(
            CompiledModel model,
            Path outRoot,
            Path schemaRealizationDir,
            Path modelSourcePath
    ) {
        Path definitionHint = firstNonNull(modelSourcePath, schemaRealizationDir, outRoot, Path.of("."))
                .toAbsolutePath()
                .normalize();
        List<String> fingerprintInputs = List.of(
                "legacyGeneratorFacade=true",
                "engine=" + DatabaseEngine.IN_MEMORY.externalName(),
                "storageMode=" + DatabaseEngine.IN_MEMORY.storageMode(),
                "namespace=" + (model == null ? "" : model.getNamespace()),
                "modelSourcePath=" + (modelSourcePath == null ? "" : modelSourcePath.toAbsolutePath().normalize())
        );
        return new GeneratedDatabasePlan(
                "legacy-generator-facade",
                DatabaseEngine.IN_MEMORY,
                DatabaseEngine.IN_MEMORY.storageMode(),
                false,
                false, // externallyProvisioned -- InMemory has no server to be external (STOR-14)
                "",
                "",
                "none",
                "",
                "",
                "",
                "",
                0,
                0,
                "",
                "",
                "",
                "",
                "",
                0,
                "",
                "",
                true,
                true,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE,
                        false,
                        "",
                        SchemaLifecyclePolicy.NPDEV_STORE_SCOPE
                ),
                "sha256:" + sha256(String.join("\n", fingerprintInputs)),
                definitionHint,
                fingerprintInputs
        );
    }

    private static Path firstNonNull(Path first, Path second, Path third, Path fallback) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third == null ? fallback : third;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute legacy schema fingerprint", exception);
        }
    }
}
