package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.paths.CanonicalModelPaths;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import com.npdev.generator.assembly.FinalAppAssembler;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.migration.MigrationRiskThreshold;
import com.npdev.generator.migration.StatefulMigrationOptions;
import com.npdev.generator.migration.StatefulMigrationPlanner;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class GeneratorMain {

    static final String CONFIG_MIGRATIONS_DISABLED = "CONFIG_MIGRATIONS_DISABLED";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        Args a = Args.parse(args);

        JsonNode config = readConfig(a.configPath);
        rejectUnsupportedMigrationManagement(config);

        Path modelPath = resolveModelPath(a, config);
        Path outRoot = resolveOutputRoot(a, config, modelPath);
        boolean cleanOut = resolveCleanOutput(a, config);

        // Clean only the disposable output folder (generated Java/resources).
        // Canonical migrations live elsewhere and are NOT cleaned.
        if (cleanOut && !a.migrationPlanOnly) {
            cleanOutputRoot(outRoot);
        }

        // Canonical migrations directory (committed in GPT repo).
        Path migrationsDir = resolveMigrationsDir(a.migrationsDir);

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(modelPath);

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
        StatefulMigrationOptions migrationOptions = new StatefulMigrationOptions(
                a.migrationMode,
                a.migrationPlanOnly,
                a.migrationRiskThreshold,
                a.migrationDecisionReportPath == null ? null : Path.of(a.migrationDecisionReportPath).toAbsolutePath().normalize()
        );

        if (a.migrationPlanOnly) {
            var result = new StatefulMigrationPlanner().plan(compiled, migrationsDir, migrationOptions);
            System.out.println("Migration plan OK. Dry-run SQL: " + result.dryRunSqlPath());
            System.out.println("Migration decision: " + result.decisionReportPath());
            return;
        }

        TemplateEngine templates = new TemplateEngine("npdev-templates/");

        GeneratedSourceWriter writer =
                new GeneratedSourceWriter(outRoot, new RegenerationPolicy());

        new GeneratorFacade(templates, writer).generate(
                compiled,
                outRoot,
                migrationsDir,
                modelPath,
                migrationOptions
        );

        writer.flushSummary();

        System.out.println("Generation OK. Output: " + outRoot);
        System.out.println("Schema realization SQL: " + migrationsDir);

        FinalAppAssemblyRequest assemblyRequest = resolveFinalAppAssemblyRequest(a, config, outRoot, migrationsDir);
        if (assemblyRequest.shouldAssemble()) {
            FinalAppAssembler.AssemblyResult assemblyResult = new FinalAppAssembler().assemble(
                    new FinalAppAssembler.Options(
                            assemblyRequest.runtimeHostRoot(),
                            outRoot,
                            assemblyRequest.finalAppRoot(),
                            migrationsDir,
                            assemblyRequest.generatedFolderName(),
                            assemblyRequest.metaFolderName(),
                            assemblyRequest.deleteBeforeMount()
                    )
            );

            System.out.println("Final app assembly OK. Root: " + assemblyResult.finalAppRoot());
            System.out.println("Generated mount: " + assemblyResult.generatedMount());
            System.out.println("Schema realization manifest: " + assemblyRequest.finalAppRoot()
                    .resolve("src")
                    .resolve("main")
                    .resolve("resources")
                    .resolve("npdev")
                    .resolve("support")
                    .resolve("schema-realization.manifest.json")
                    .toAbsolutePath()
                    .normalize());
            System.out.println("RuntimeHost files copied: " + assemblyResult.runtimeHostFilesCopied());
            System.out.println("Generated files copied: " + assemblyResult.generatedFilesCopied());
            System.out.println("Schema realization SQL copied: " + assemblyResult.generatedMigrationsCopied());
        }
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

        return new FinalAppAssemblyRequest(
                shouldAssemble,
                runtimeHostRoot,
                finalAppRoot,
                outRoot,
                migrationsDir,
                generatedFolderName,
                metaFolderName,
                deleteBeforeMount
        );
    }

    private static Path resolveMigrationsDir(String migrationsDirArg) throws IOException {
        Path cwd = Path.of("").toAbsolutePath();

        Path migrationsDir;
        if (migrationsDirArg != null && !migrationsDirArg.isBlank()) {
            migrationsDir = Path.of(migrationsDirArg);
        } else {
            // Default: committed canonical folder for schema-realization SQL inside GPT repo
            migrationsDir = cwd.resolve("db-history")
                    .resolve("src").resolve("main").resolve("resources")
                    .resolve("db").resolve("migration");
        }

        Files.createDirectories(migrationsDir);
        return migrationsDir.toAbsolutePath().normalize();
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
                    && Files.isDirectory(current.resolve("NPDevEditor"))
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
        final boolean cleanOut;
        final boolean cleanOutExplicit;
        final String migrationsDir;
        final String runtimeHostRoot;
        final String finalAppRoot;
        final boolean assembleFinalApp;
        final boolean assembleFinalAppExplicit;
        final boolean cleanFinalApp;
        final boolean cleanFinalAppExplicit;
        final String generatedFolderName;
        final String metaFolderName;
        final String migrationMode;
        final boolean migrationPlanOnly;
        final MigrationRiskThreshold migrationRiskThreshold;
        final String migrationDecisionReportPath;

        private Args(
                String configPath,
                String modelPath,
                String outPath,
                boolean cleanOut,
                boolean cleanOutExplicit,
                String migrationsDir,
                String runtimeHostRoot,
                String finalAppRoot,
                boolean assembleFinalApp,
                boolean assembleFinalAppExplicit,
                boolean cleanFinalApp,
                boolean cleanFinalAppExplicit,
                String generatedFolderName,
                String metaFolderName,
                String migrationMode,
                boolean migrationPlanOnly,
                MigrationRiskThreshold migrationRiskThreshold,
                String migrationDecisionReportPath
        ) {
            this.configPath = configPath;
            this.modelPath = modelPath;
            this.outPath = outPath;
            this.cleanOut = cleanOut;
            this.cleanOutExplicit = cleanOutExplicit;
            this.migrationsDir = migrationsDir;
            this.runtimeHostRoot = runtimeHostRoot;
            this.finalAppRoot = finalAppRoot;
            this.assembleFinalApp = assembleFinalApp;
            this.assembleFinalAppExplicit = assembleFinalAppExplicit;
            this.cleanFinalApp = cleanFinalApp;
            this.cleanFinalAppExplicit = cleanFinalAppExplicit;
            this.generatedFolderName = generatedFolderName;
            this.metaFolderName = metaFolderName;
            this.migrationMode = migrationMode;
            this.migrationPlanOnly = migrationPlanOnly;
            this.migrationRiskThreshold = migrationRiskThreshold;
            this.migrationDecisionReportPath = migrationDecisionReportPath;
        }

        static Args parse(String[] args) {
            String config = null;
            String model = null;
            String out = null;

            // Professional default: do NOT clean unless explicitly requested.
            boolean clean = false;
            boolean cleanExplicit = false;

            // Optional: explicit canonical migrations directory
            String migDir = null;
            String runtimeHost = null;
            String finalApp = null;
            boolean assemble = false;
            boolean assembleExplicit = false;
            boolean cleanFinal = false;
            boolean cleanFinalExplicit = false;
            String generatedFolder = null;
            String metaFolder = null;
            String migrationMode = "disabled";
            boolean migrationPlanOnly = false;
            MigrationRiskThreshold migrationRiskThreshold = MigrationRiskThreshold.SAFE_ADDITIVE;
            String migrationDecisionReportPath = null;

            for (int i = 0; i < args.length; i++) {
                String cur = args[i];

                if (cur.startsWith("--migrationMode=")) {
                    migrationMode = cur.substring("--migrationMode=".length());
                    validateMigrationMode(migrationMode);
                } else if (cur.startsWith("--migrationRiskThreshold=")) {
                    migrationRiskThreshold = MigrationRiskThreshold.parse(cur.substring("--migrationRiskThreshold=".length()));
                } else if (cur.startsWith("--migrationDecisionReport=")) {
                    migrationDecisionReportPath = cur.substring("--migrationDecisionReport=".length());
                } else if ("--config".equals(cur) && i + 1 < args.length) {
                    config = args[++i];
                } else if ("--model".equals(cur) && i + 1 < args.length) {
                    model = args[++i];
                } else if ("--out".equals(cur) && i + 1 < args.length) {
                    out = args[++i];
                } else if ("--migrationsDir".equals(cur) && i + 1 < args.length) {
                    migDir = args[++i];
                } else if ("--runtimeHostTemplate".equals(cur) && i + 1 < args.length) {
                    runtimeHost = args[++i];
                } else if ("--finalAppOut".equals(cur) && i + 1 < args.length) {
                    finalApp = args[++i];
                } else if ("--generatedFolderName".equals(cur) && i + 1 < args.length) {
                    generatedFolder = args[++i];
                } else if ("--metaFolderName".equals(cur) && i + 1 < args.length) {
                    metaFolder = args[++i];
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
                } else if ("--migrationPlanOnly".equals(cur)) {
                    migrationPlanOnly = true;
                    if ("disabled".equalsIgnoreCase(migrationMode)) {
                        migrationMode = "additive-only";
                    }
                } else if ("--enableMigrations".equals(cur) || "--migrationManagement".equals(cur)) {
                    throw migrationsDisabled(cur);
                } else if ("--migrationMode".equals(cur) && i + 1 < args.length) {
                    migrationMode = args[++i];
                    validateMigrationMode(migrationMode);
                } else if ("--migrationRiskThreshold".equals(cur) && i + 1 < args.length) {
                    migrationRiskThreshold = MigrationRiskThreshold.parse(args[++i]);
                } else if ("--migrationDecisionReport".equals(cur) && i + 1 < args.length) {
                    migrationDecisionReportPath = args[++i];
                }
            }

            return new Args(
                    config,
                    model,
                    out,
                    clean,
                    cleanExplicit,
                    migDir,
                    runtimeHost,
                    finalApp,
                    assemble,
                    assembleExplicit,
                    cleanFinal,
                    cleanFinalExplicit,
                    generatedFolder,
                    metaFolder,
                    migrationMode,
                    migrationPlanOnly,
                    migrationRiskThreshold,
                    migrationDecisionReportPath
            );
        }

        private static void validateMigrationMode(String migrationMode) {
            if ("disabled".equalsIgnoreCase(migrationMode)
                    || "off".equalsIgnoreCase(migrationMode)
                    || "additive-only".equalsIgnoreCase(migrationMode)) {
                return;
            }
            throw migrationsDisabled("--migrationMode=" + migrationMode);
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
            boolean deleteBeforeMount
    ) {
    }
}
