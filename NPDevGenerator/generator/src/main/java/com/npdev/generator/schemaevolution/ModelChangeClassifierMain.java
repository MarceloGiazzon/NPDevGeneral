package com.npdev.generator.schemaevolution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generator.dbconfig.DatabaseEngine;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import com.npdev.generator.dbconfig.SchemaLifecycleStrategy;
import com.npdev.generator.dbconfig.UserDatabaseDefinition;
import com.npdev.generator.dbconfig.UserDatabaseDefinitionLoader;
import com.npdev.generator.emitters.MetadataManifestAssetEmitter;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * REG-102 fix (MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 1.2, LC-C1/AC-3): a REAL, working offline
 * "diff two model.json snapshots and classify the change" entry point.
 *
 * <p>Deliberately its OWN small main class, never a new flag on {@link com.npdev.generator.GeneratorMain}
 * -- that class's arg parser unconditionally rejects any {@code --migration}-prefixed flag
 * ({@code GeneratorMain#migrationsDisabled}), a guard against a DIFFERENT, retired thing (model.json
 * declaring {@code migrationManagement}/{@code migrations}/{@code schemaEvolution} config keys). The
 * previous CLI wiring (REG-102's root cause) passed five flags that all started with that literal
 * prefix and were rejected on the first one, every time. Every flag here is named without it,
 * mirroring the precedent {@code GeneratorMain}'s own {@code --previousCompiledModel}/
 * {@code --schemaMigrationPlanOut} already set for the identical reason.
 *
 * <p>Reuses {@link MigrationPlanEmitter} (the real, already-tested, no-database model-vs-model diff
 * engine LNCH-1 Phase 6 built) and {@link ModelChangeClassifier} (this fix's coarse-classification
 * mapping over that engine's output) -- no new diffing logic, one grammar.
 *
 * <p>Usage: {@code --current <model.json> [--baseline <model.json>] [--out <report.json>]
 * [--emitCompiledModelTo <compiled-model.json>] [--emitMetadataTo <dir>]}. {@code --baseline}
 * omitted means "fresh install" (matches {@link MigrationPlanEmitter}'s own
 * {@code previousModelOrNull} contract). The report is always printed to stdout; {@code --out} also
 * writes it to a file.
 *
 * <p>{@code --emitCompiledModelTo} (Wave 1.3, LC-C2's fast path): writes
 * {@code CompiledModelCanonicalJson.toJson(current)} to that path ONLY when the classification is
 * {@code METADATA_ONLY} -- refuses (non-zero exit, no file written or overwritten) for anything
 * else, so the fast path can never silently apply a schema-shaped change without a real build.
 * {@code NPDevModelProvider} (the generated app's own compiled-model loader) checks this exact path
 * BEFORE its classpath-baked fallback, so overwriting it and restarting the app's JVM is enough to
 * make {@code PanelRuntime}/{@code AggregateRuntime}/{@code ProcedureRunner} (everything that reads
 * the injected {@code CompiledModel} bean) see the change -- no Gradle, no codegen, no jar surgery.
 *
 * <p>{@code --emitMetadataTo <dir>} (Fast Lane plan item 1a, REG-103 follow-up): the other half of
 * the same fast path. Writes {@code <dir>/src/main/resources/npdev/compiled-metadata.json} (the
 * same bytes {@code RuntimeApiEmitter} would emit, via the same
 * {@code CompiledMetadataCanonicalJson.toJson(modelSourcePath, current)} call) plus
 * {@code <dir>/src/main/resources/npdev/metadata/*.manifest.json} and {@code .../metadata/index.json}
 * (via {@link MetadataManifestAssetEmitter}, unchanged) -- ONLY when the classification is
 * {@code METADATA_ONLY}, same refusal contract as {@code --emitCompiledModelTo}. Deliberately calls
 * these two emitters directly rather than {@code GeneratorFacade.generate()}: {@code current} is
 * already compiled in memory for classification, and this flag exists precisely so a caller does not
 * need the rest of the pipeline (Java source, static UI bundle, schema realization) to refresh these
 * two catalogs. {@code RuntimeMetadataService} (REG-103, Move 13 P5.1) already checks
 * {@code npdev.compiled-metadata.path}/{@code npdev.metadata-index.path}/
 * {@code npdev.generated-resources.path} before its classpath fallback and, by default, resolves all
 * three relative to the app's own working directory as
 * {@code npdev-generated/src/main/resources/...} -- the same relative layout this flag writes under
 * {@code <dir>} -- so a caller only needs to copy {@code <dir>/src/main/resources/npdev/} onto the
 * app's own {@code npdev-generated/src/main/resources/npdev/} and re-sign, exactly as
 * {@code scripts/appgen/Update-AppMetadata.ps1} now does. The static frontend assets under
 * {@code static/npdev-business-ui/} remain a separate, not-yet-fixed problem (REG-109).
 */
public final class ModelChangeClassifierMain {

    private ModelChangeClassifierMain() {
    }

    public static void main(String[] args) throws IOException {
        String baselinePath = null;
        String currentPath = null;
        String outPath = null;
        String emitCompiledModelTo = null;
        String emitMetadataTo = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--baseline" -> baselinePath = args[++i];
                case "--current" -> currentPath = args[++i];
                case "--out" -> outPath = args[++i];
                case "--emitCompiledModelTo" -> emitCompiledModelTo = args[++i];
                case "--emitMetadataTo" -> emitMetadataTo = args[++i];
                default -> throw new IllegalArgumentException("Unrecognized argument: " + args[i]
                        + " (supported: --current, --baseline, --out, --emitCompiledModelTo, --emitMetadataTo)");
            }
        }
        if (currentPath == null || currentPath.isBlank()) {
            throw new IllegalArgumentException("--current is required");
        }

        CompiledModel current = compile(Path.of(currentPath));
        CompiledModel baseline = (baselinePath == null || baselinePath.isBlank()) ? null : compile(Path.of(baselinePath));

        GeneratedDatabasePlan databasePlan = defaultPlan(currentPath, current);
        MigrationPlan plan = MigrationPlanEmitter.compute(current, baseline, databasePlan);
        ModelChangeClassifier.Classification classification = ModelChangeClassifier.classify(plan);

        String json = toReportJson(plan, classification);
        if (outPath != null && !outPath.isBlank()) {
            Path out = Path.of(outPath).toAbsolutePath().normalize();
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            Files.writeString(out, json, StandardCharsets.UTF_8);
        }
        System.out.println(json);

        if (emitCompiledModelTo != null && !emitCompiledModelTo.isBlank()) {
            if (classification.level() != ModelChangeClassifier.Level.METADATA_ONLY) {
                System.err.println("REFUSED: --emitCompiledModelTo requires classification METADATA_ONLY, got "
                        + classification.level() + " -- " + classification.reasons());
                System.exit(2);
            }
            com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson.write(Path.of(emitCompiledModelTo), current);
            System.out.println("Compiled model written to " + emitCompiledModelTo);
        }

        if (emitMetadataTo != null && !emitMetadataTo.isBlank()) {
            if (classification.level() != ModelChangeClassifier.Level.METADATA_ONLY) {
                System.err.println("REFUSED: --emitMetadataTo requires classification METADATA_ONLY, got "
                        + classification.level() + " -- " + classification.reasons());
                System.exit(2);
            }
            Path modelSourcePath = Path.of(currentPath);
            GeneratedSourceWriter writer = new GeneratedSourceWriter(Path.of(emitMetadataTo), new RegenerationPolicy());
            writer.writeRelative(
                    "src/main/resources/npdev/compiled-metadata.json",
                    com.npdev.dsl.v1.compiled.CompiledMetadataCanonicalJson.toJson(modelSourcePath, current)
            );
            try {
                new MetadataManifestAssetEmitter(writer).emit(current, null, modelSourcePath);
            } catch (Exception e) {
                throw new IOException("Failed emitting per-catalog metadata manifests to " + emitMetadataTo, e);
            }
            System.out.println("Metadata catalogs written to " + emitMetadataTo);
        }
    }

    private static CompiledModel compile(Path modelPath) throws IOException {
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new ModelCompiler().compile(ast);
    }

    /**
     * Classification depends only on the model diff -- {@link MigrationPlanEmitter} never contacts
     * a live database (its own class javadoc). A representative, zero-external-configuration default
     * (H2, both table kinds on, keep-existing-if-compatible) keeps this runnable as a quick sanity
     * check before any real deploy target is even known, matching the "PlanOnly" framing
     * {@link MigrationPlanEmitter}'s own javadoc already documents for {@code Build-NpdevApp.ps1}.
     */
    private static GeneratedDatabasePlan defaultPlan(String currentPath, CompiledModel current) {
        UserDatabaseDefinition definition = new UserDatabaseDefinition(
                DatabaseEngine.H2_LOCAL, "", 0, "", "", "", "", "", "",
                true, true, false,
                new SchemaLifecyclePolicy(SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE, false, "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE));
        String fingerprint = UserDatabaseDefinitionLoader.computeSchemaFingerprint(definition, current);
        return new GeneratedDatabasePlan(
                "model-change-classifier",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned -- H2Local is embedded (STOR-14)
                "model-change-classifier",
                "model-change-classifier",
                "cli",
                "",
                "cli-instance",
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
                definition.schemaLifecycle(),
                fingerprint,
                Path.of(currentPath),
                List.of());
    }

    private static String toReportJson(MigrationPlan plan, ModelChangeClassifier.Classification classification)
            throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(plan.toJson());
        root.put("classification", classification.level().name());
        ArrayNode reasons = root.putArray("classificationReasons");
        for (String reason : classification.reasons()) {
            reasons.add(reason);
        }
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n";
    }
}
