package com.npdev.generator.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.compiled.CompiledModel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StatefulMigrationPlanner {
    public static final String ADDITIVE_ONLY_REJECTED = "ADDITIVE_ONLY_MIGRATION_REJECTED";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Pattern VERSIONED_MIGRATION = Pattern.compile("^V(\\d+)__.+\\.sql$");

    private final StorageSchemaFromCompiledModel storageSchemaFromCompiledModel;
    private final StorageSchemaSnapshotStore snapshotStore;
    private final MigrationDiffEngine migrationDiffEngine;
    private final MigrationScriptEmitter migrationScriptEmitter;
    private final MigrationRiskAssessmentBuilder riskAssessmentBuilder;

    public StatefulMigrationPlanner() {
        this(
                new StorageSchemaFromCompiledModel(),
                new StorageSchemaSnapshotStore(),
                new MigrationDiffEngine(),
                new MigrationScriptEmitter(),
                new MigrationRiskAssessmentBuilder()
        );
    }

    StatefulMigrationPlanner(
            StorageSchemaFromCompiledModel storageSchemaFromCompiledModel,
            StorageSchemaSnapshotStore snapshotStore,
            MigrationDiffEngine migrationDiffEngine,
            MigrationScriptEmitter migrationScriptEmitter,
            MigrationRiskAssessmentBuilder riskAssessmentBuilder
    ) {
        this.storageSchemaFromCompiledModel = storageSchemaFromCompiledModel;
        this.snapshotStore = snapshotStore;
        this.migrationDiffEngine = migrationDiffEngine;
        this.migrationScriptEmitter = migrationScriptEmitter;
        this.riskAssessmentBuilder = riskAssessmentBuilder;
    }

    public StatefulMigrationResult plan(CompiledModel model, Path canonicalMigrationsDir, StatefulMigrationOptions options) throws Exception {
        StorageSchemaSnapshot current = storageSchemaFromCompiledModel.from(model).normalized();
        Path dbRoot = requireDbRoot(canonicalMigrationsDir);
        Path latestSnapshot = dbRoot.resolve("schema-snapshots").resolve("latest-storage-schema.json");
        StorageSchemaSnapshot previous = snapshotStore.loadIfExists(latestSnapshot);
        return plan(previous, current, canonicalMigrationsDir, options);
    }

    public StatefulMigrationResult plan(
            StorageSchemaSnapshot previous,
            StorageSchemaSnapshot current,
            Path canonicalMigrationsDir,
            StatefulMigrationOptions options
    ) throws Exception {
        StatefulMigrationOptions normalizedOptions = options == null ? StatefulMigrationOptions.disabled() : options;
        Path dbRoot = requireDbRoot(canonicalMigrationsDir);
        Path snapshotDir = dbRoot.resolve("schema-snapshots");
        Path planDir = dbRoot.resolve("migration-plans");
        Files.createDirectories(canonicalMigrationsDir);
        Files.createDirectories(snapshotDir);
        Files.createDirectories(planDir);

        StorageSchemaSnapshot prev = previous == null ? new StorageSchemaSnapshot("none", List.of()) : previous.normalized();
        StorageSchemaSnapshot curr = current == null ? new StorageSchemaSnapshot("unknown", List.of()) : current.normalized();
        String snapshotHash = snapshotStore.computeCanonicalHash(curr);
        MigrationPlan plan = migrationDiffEngine.diff(prev, curr).normalized();
        MigrationRiskAssessment assessment = riskAssessmentBuilder.build(prev, curr);

        Path dryRunSql = migrationScriptEmitter.emit(planDir, "latest-model-delta.sql", plan);
        if (!plan.isEmpty()) {
            migrationScriptEmitter.emit(planDir, "NPDEV_" + snapshotHash + "__model_delta.sql", plan);
        }

        boolean allowed = !normalizedOptions.additiveOnly()
                || (assessment.deterministicPlan()
                && assessment.breakingChanges().isEmpty()
                && normalizedOptions.riskThreshold().allows(assessment.overallRisk()));
        Path versionedMigration = null;
        if (allowed && normalizedOptions.additiveOnly() && !normalizedOptions.migrationPlanOnly() && !plan.isEmpty()) {
            String versionedFileName = nextVersionedMigrationName(canonicalMigrationsDir);
            versionedMigration = migrationScriptEmitter.emit(canonicalMigrationsDir, versionedFileName, plan);
        }

        Path latestSnapshot = snapshotDir.resolve("latest-storage-schema.json");
        Path contentAddressedSnapshot = snapshotDir.resolve("storage-schema-" + snapshotHash + ".json");
        if (allowed && !normalizedOptions.migrationPlanOnly()) {
            snapshotStore.save(latestSnapshot, curr);
            snapshotStore.save(contentAddressedSnapshot, curr);
        } else {
            snapshotStore.save(snapshotDir.resolve("planned-storage-schema-" + snapshotHash + ".json"), curr);
        }

        Path decisionReport = normalizedOptions.decisionReportPath() != null
                ? normalizedOptions.decisionReportPath()
                : planDir.resolve("latest-migration-decision.json");
        writeDecisionReport(decisionReport, normalizedOptions, allowed, assessment, dryRunSql, versionedMigration, snapshotHash);

        if (!allowed) {
            throw new IllegalStateException(ADDITIVE_ONLY_REJECTED
                    + ": risk=" + assessment.overallRisk()
                    + ", threshold=" + normalizedOptions.riskThreshold()
                    + ", breakingChanges=" + assessment.breakingChanges());
        }

        return new StatefulMigrationResult(
                true,
                normalizedOptions.migrationPlanOnly(),
                versionedMigration != null,
                assessment.overallRisk(),
                snapshotHash,
                plan.operations().size(),
                dryRunSql,
                versionedMigration,
                decisionReport
        );
    }

    private void writeDecisionReport(
            Path decisionReport,
            StatefulMigrationOptions options,
            boolean allowed,
            MigrationRiskAssessment assessment,
            Path dryRunSql,
            Path versionedMigration,
            String snapshotHash
    ) throws Exception {
        Files.createDirectories(decisionReport.getParent());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schemaVersion", "npdev-stateful-migration-decision.v1");
        report.put("generatedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        report.put("migrationMode", options.migrationMode());
        report.put("migrationPlanOnly", options.migrationPlanOnly());
        report.put("riskThreshold", options.riskThreshold().name());
        report.put("allowed", allowed);
        report.put("overallRisk", assessment.overallRisk());
        report.put("deterministicPlan", assessment.deterministicPlan());
        report.put("operationCount", assessment.operationCount());
        report.put("safeChanges", assessment.safeChanges());
        report.put("safeNewTableNotNullChanges", changesWithPrefix(assessment.safeChanges(), "set required on new table "));
        report.put("backfillRequiredChanges", assessment.backfillRequiredChanges());
        report.put("existingTableNotNullBackfillRequiredChanges", changesWithPrefix(assessment.backfillRequiredChanges(), "tighten required "));
        report.put("manualReviewChanges", assessment.manualReviewChanges());
        report.put("breakingChanges", assessment.breakingChanges());
        report.put("rejectedDestructiveChanges", allowed ? List.of() : assessment.breakingChanges());
        report.put("dryRunSqlPath", dryRunSql.toAbsolutePath().normalize().toString());
        report.put("versionedFlywayMigrationPath", versionedMigration == null ? "" : versionedMigration.toAbsolutePath().normalize().toString());
        report.put("currentHash", snapshotHash);
        Files.writeString(
                decisionReport,
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report) + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
    }

    private static List<String> changesWithPrefix(List<String> values, String prefix) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(value -> value != null && value.startsWith(prefix))
                .toList();
    }

    private static Path requireDbRoot(Path canonicalMigrationsDir) {
        if (canonicalMigrationsDir == null) {
            throw new IllegalArgumentException("canonicalMigrationsDir must be non-null");
        }
        Path dbRoot = canonicalMigrationsDir.toAbsolutePath().normalize().getParent();
        if (dbRoot == null) {
            throw new IllegalArgumentException("canonicalMigrationsDir must have a parent directory");
        }
        return dbRoot;
    }

    private static String nextVersionedMigrationName(Path canonicalMigrationsDir) throws Exception {
        int nextVersion = 6000;
        if (Files.isDirectory(canonicalMigrationsDir)) {
            try (var stream = Files.list(canonicalMigrationsDir)) {
                nextVersion = stream
                        .filter(Files::isRegularFile)
                        .map(path -> path.getFileName().toString())
                        .map(VERSIONED_MIGRATION::matcher)
                        .filter(Matcher::matches)
                        .map(matcher -> Integer.parseInt(matcher.group(1)))
                        .max(Comparator.naturalOrder())
                        .map(value -> Math.max(6000, value + 1))
                        .orElse(6000);
            }
        }
        return "V" + nextVersion + "__npdev_model_delta.sql";
    }
}
