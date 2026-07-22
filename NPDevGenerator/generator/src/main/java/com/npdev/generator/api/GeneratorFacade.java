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
import com.npdev.generator.emitters.MetadataManifestAssetEmitter;
import com.npdev.generator.emitters.PluginRequirementAssetEmitter;
import com.npdev.generator.emitters.RuntimeApiEmitter;
import com.npdev.generator.emitters.RuntimeAuthPropertiesEmitter;
import com.npdev.generator.emitters.RuntimeLogPropertiesEmitter;
import com.npdev.generator.emitters.ServiceEmitter;
import com.npdev.generator.emitters.TrustedSourceEmitter;
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
                resolvedModelSource == null ? null : resolvedModelSource.rootModelPath(),
                databasePlan,
                migrationPlanDestructiveItemStableStrings,
                destructiveAcknowledgmentToken
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
        generate(model, outRoot, schemaRealizationDir, resolvedModelSource, modelSourcePath, databasePlan, List.of(), null);
    }

    private void generate(
            CompiledModel model,
            Path outRoot,
            Path schemaRealizationDir,
            ResolvedModelSource resolvedModelSource,
            Path modelSourcePath,
            GeneratedDatabasePlan databasePlan,
            List<String> migrationPlanDestructiveItemStableStrings,
            String destructiveAcknowledgmentToken
    ) throws Exception {
        boolean kernelControlled = settingResolver.value(NpdevSettings.CRUD_KERNEL_CONTROLLED, SettingTarget.app());
        String superUserRole = settingResolver.value(NpdevSettings.SECURITY_SUPER_USER_ROLE, SettingTarget.app());
        boolean internalTablesEnabled = settingResolver.value(NpdevSettings.INTERNAL_TABLES, SettingTarget.app());

        new EntityEmitter(templates, writer).emit(model);
        new DtoEmitter(templates, writer).emit(model);
        new ServiceEmitter(templates, writer).emit(model, kernelControlled, settingResolver);
        new ControllerEmitter(templates, writer).emit(model);

        new RuntimeApiEmitter(templates, writer).emit(model, resolvedModelSource, modelSourcePath, superUserRole);
        if (settingResolver.value(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app())) {
            new BusinessUiEmitter(templates, writer).emit(model, superUserRole, settingResolver);
            // Phase 7: provenance/store/box-view admin surfaces ride along with the business UI,
            // since they are only reachable through its super-user admin nav.
            new BoxManifestEmitter().emit(model, writer);
            new PackCatalogEmitter().emit(writer, internalTablesEnabled, installedPackAliases);
        }
        new TrustedSourceEmitter(writer).emit(model, modelSourcePath);
        new MetadataManifestAssetEmitter(writer).emit(model, resolvedModelSource, modelSourcePath);

        // Stage 3: emit deterministic plugin requirement asset derived from the model source.
        new PluginRequirementAssetEmitter(writer).emit(resolvedModelSource, modelSourcePath);

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
        new GeneratedFolderSignatureEmitter().emit(outRoot);
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
