package com.npdev.generator.api;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.settings.NpdevSettings;
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
import com.npdev.generator.emitters.ServiceEmitter;
import com.npdev.generator.emitters.TrustedSourceEmitter;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaRealizationEmitter;
import com.npdev.generator.dbconfig.DatabaseEngine;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import com.npdev.generator.dbconfig.SchemaLifecycleStrategy;
import com.npdev.generator.output.GeneratedSourceWriter;
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

    public GeneratorFacade(TemplateEngine templates, GeneratedSourceWriter writer) {
        this(templates, writer, new SettingResolver(SettingStore.empty()));
    }

    public GeneratorFacade(TemplateEngine templates, GeneratedSourceWriter writer, SettingResolver settingResolver) {
        this.templates = templates;
        this.writer = writer;
        this.settingResolver = settingResolver == null ? new SettingResolver(SettingStore.empty()) : settingResolver;
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
        generate(
                model,
                outRoot,
                schemaRealizationDir,
                resolvedModelSource,
                resolvedModelSource == null ? null : resolvedModelSource.rootModelPath(),
                databasePlan
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
        new EntityEmitter(templates, writer).emit(model);
        new DtoEmitter(templates, writer).emit(model);
        new ServiceEmitter(templates, writer).emit(model);
        new ControllerEmitter(templates, writer).emit(model);

        new RuntimeApiEmitter(templates, writer).emit(model, resolvedModelSource, modelSourcePath);
        if (settingResolver.value(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app())) {
            new BusinessUiEmitter(templates, writer).emit(model);
        }
        new TrustedSourceEmitter(writer).emit(model, modelSourcePath);
        new MetadataManifestAssetEmitter(writer).emit(model, resolvedModelSource, modelSourcePath);

        // Stage 3: emit deterministic plugin requirement asset derived from the model source.
        new PluginRequirementAssetEmitter(writer).emit(resolvedModelSource, modelSourcePath);

        // Provenance: record the resolved settings cascade so it is inspectable in the generated app.
        new SettingsManifestEmitter(writer).emit(settingResolver);

        new SchemaRealizationEmitter().emit(model, outRoot, databasePlan, modelSourcePath);
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
