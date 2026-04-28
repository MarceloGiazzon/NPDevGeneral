package com.npdev.generator.api;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.emitters.ConfigEmitter;
import com.npdev.generator.emitters.ControllerEmitter;
import com.npdev.generator.emitters.DtoEmitter;
import com.npdev.generator.emitters.EntityEmitter;
import com.npdev.generator.emitters.FlywayEmitter;
import com.npdev.generator.emitters.GeneratedFolderSignatureEmitter;
import com.npdev.generator.emitters.MetadataManifestAssetEmitter;
import com.npdev.generator.emitters.PluginRequirementAssetEmitter;
import com.npdev.generator.emitters.RepositoryEmitter;
import com.npdev.generator.emitters.RuntimeApiEmitter;
import com.npdev.generator.emitters.ServiceEmitter;
import com.npdev.generator.migration.MigrationDiffEngine;
import com.npdev.generator.migration.MigrationPlan;
import com.npdev.generator.migration.MigrationScriptEmitter;
import com.npdev.generator.migration.StorageSchemaFromCompiledModel;
import com.npdev.generator.migration.StorageSchemaSnapshot;
import com.npdev.generator.migration.StorageSchemaSnapshotStore;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.nio.file.Files;
import java.nio.file.Path;

public final class GeneratorFacade {

    private final TemplateEngine templates;
    private final GeneratedSourceWriter writer;
    private final StorageSchemaFromCompiledModel storageSchemaFromCompiledModel;
    private final StorageSchemaSnapshotStore snapshotStore;
    private final MigrationDiffEngine migrationDiffEngine;
    private final MigrationScriptEmitter migrationScriptEmitter;

    public GeneratorFacade(TemplateEngine templates, GeneratedSourceWriter writer) {
        this(
                templates,
                writer,
                new StorageSchemaFromCompiledModel(),
                new StorageSchemaSnapshotStore(),
                new MigrationDiffEngine(),
                new MigrationScriptEmitter()
        );
    }

    GeneratorFacade(
            TemplateEngine templates,
            GeneratedSourceWriter writer,
            StorageSchemaFromCompiledModel storageSchemaFromCompiledModel,
            StorageSchemaSnapshotStore snapshotStore,
            MigrationDiffEngine migrationDiffEngine,
            MigrationScriptEmitter migrationScriptEmitter
    ) {
        this.templates = templates;
        this.writer = writer;
        this.storageSchemaFromCompiledModel = storageSchemaFromCompiledModel;
        this.snapshotStore = snapshotStore;
        this.migrationDiffEngine = migrationDiffEngine;
        this.migrationScriptEmitter = migrationScriptEmitter;
    }

    public void generate(CompiledModel model, Path outRoot, Path canonicalMigrationsDir) throws Exception {
        generate(model, outRoot, canonicalMigrationsDir, null);
    }

    public void generate(
            CompiledModel model,
            Path outRoot,
            Path canonicalMigrationsDir,
            Path modelSourcePath
    ) throws Exception {
        new EntityEmitter(templates, writer).emit(model);
        new RepositoryEmitter(templates, writer).emit(model);
        new DtoEmitter(templates, writer).emit(model);
        new ServiceEmitter(templates, writer).emit(model);
        new ControllerEmitter(templates, writer).emit(model);

        new ConfigEmitter(templates, writer).emit(model);
        new RuntimeApiEmitter(templates, writer).emit(model, modelSourcePath);
        new MetadataManifestAssetEmitter(writer).emit(model, modelSourcePath);

        // Stage 3: emit deterministic plugin requirement asset derived from the model source.
        new PluginRequirementAssetEmitter(writer).emit(modelSourcePath);

        // Supported database delivery path:
        // emit repeatable schema-realization SQL into the canonical committed folder.
        // This avoids version drift while keeping recreate-style app assembly deterministic.
        new FlywayEmitter().emitRepeatableSchema(model, canonicalMigrationsDir);

        emitPhase8PersistenceArtifacts(model, canonicalMigrationsDir);
        new GeneratedFolderSignatureEmitter().emit(outRoot);
    }

    private void emitPhase8PersistenceArtifacts(CompiledModel model, Path canonicalMigrationsDir) throws Exception {
        if (model == null || canonicalMigrationsDir == null) {
            return;
        }

        Path dbRoot = canonicalMigrationsDir.getParent();
        if (dbRoot == null) {
            return;
        }

        Path snapshotDir = dbRoot.resolve("schema-snapshots");
        Path planDir = dbRoot.resolve("migration-plans");

        Files.createDirectories(snapshotDir);
        Files.createDirectories(planDir);

        Path latestSnapshotFile = snapshotDir.resolve("latest-storage-schema.json");

        StorageSchemaSnapshot previous = snapshotStore.loadIfExists(latestSnapshotFile);
        StorageSchemaSnapshot current = storageSchemaFromCompiledModel.from(model).normalized();

        String snapshotHash = snapshotStore.computeCanonicalHash(current);

        MigrationPlan plan = migrationDiffEngine.diff(previous, current).normalized();

        // Always persist the latest schema snapshot and one content-addressed snapshot.
        snapshotStore.save(latestSnapshotFile, current);
        snapshotStore.save(snapshotDir.resolve("storage-schema-" + snapshotHash + ".json"), current);

        // Internal schema-diff evidence remains repository-side only.
        // These artifacts explain evolution but do not enable supported stateful upgrade management.
        String planFileName = "NPDEV_" + snapshotHash + "__model_delta.sql";
        if (!plan.isEmpty()) {
            migrationScriptEmitter.emit(planDir, planFileName, plan);
        }

        // Also write a stable "latest" plan for quick inspection and deterministic diffing.
        Path latestPlanFile = planDir.resolve("latest-model-delta.sql");
        if (!plan.isEmpty() || !Files.exists(latestPlanFile)) {
            migrationScriptEmitter.emit(planDir, "latest-model-delta.sql", plan);
        }
    }
}
