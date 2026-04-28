package com.npdev.generator.assembly;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class FinalAppAssembler {

    private static final String DEFAULT_GENERATED_FOLDER_NAME = "npdev-generated";
    private static final int DELETE_RETRY_ATTEMPTS = 10;
    private static final long DELETE_RETRY_DELAY_MILLIS = 500L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> EXCLUDED_DIRECTORY_NAMES = Set.of(
            ".gradle",
            ".idea",
            ".git",
            "build",
            "out",
            "target",
            "node_modules",
            "runtime-data",
            "npdev-generated",
            "npdev-meta"
    );
    private static final Set<String> EXCLUDED_FILE_NAMES = Set.of(
            ".DS_Store",
            "Thumbs.db",
            "npdev-build-info.properties"
    );
    private static final List<String> UNSUPPORTED_RUNTIME_HOST_CONTROLLER_SOURCES = List.of(
            "com/finalexec/HelloController.java",
            "com/finalexec/api/internal/*.java",
            "com/finalexec/api/experimental/*.java"
    );
    private static final List<String> UNSUPPORTED_RUNTIME_HOST_SERVICE_SOURCES = List.of(
            "com/finalexec/npdev/service/internal/*.java",
            "com/finalexec/npdev/service/experimental/*.java"
    );

    public AssemblyResult assemble(Options options) throws IOException {
        Options normalized = options.normalized();
        validate(normalized);

        if (normalized.deleteBeforeMount() && Files.exists(normalized.finalAppRoot())) {
            ensureSafeDeleteTarget(normalized);
            deleteTree(normalized.finalAppRoot());
        }

        Files.createDirectories(normalized.finalAppRoot());

        CopyStats hostStats = copyTree(
                normalized.runtimeHostRoot(),
                normalized.finalAppRoot(),
                CopyMode.RUNTIME_HOST_BASE,
                normalized
        );
        materializeRootTemplate(normalized.runtimeHostRoot(), normalized.finalAppRoot(), "build.gradle");
        materializeRootTemplate(normalized.runtimeHostRoot(), normalized.finalAppRoot(), "settings.gradle");

        Path generatedMount = normalized.finalAppRoot().resolve(normalized.generatedFolderName());
        CopyStats generatedStats = copyTree(
                normalized.generatedArtifactRoot(),
                generatedMount,
                CopyMode.GENERATED_ARTIFACT,
                normalized
        );

        int migrationCount = copyGeneratedMigrations(normalized);
        boolean modelDiffBaselineInstalled = copyModelDiffBaseline(normalized);
        writeSchemaRealizationManifest(normalized, migrationCount, modelDiffBaselineInstalled);

        return new AssemblyResult(
                normalized.finalAppRoot(),
                generatedMount,
                hostStats.filesCopied(),
                generatedStats.filesCopied(),
                migrationCount,
                modelDiffBaselineInstalled
        );
    }

    private static void validate(Options options) throws IOException {
        requireDirectory(options.runtimeHostRoot(), "RuntimeHost template root");
        requireDirectory(options.generatedArtifactRoot(), "Generated artifact root");

        Path buildTemplate = options.runtimeHostRoot().resolve("build.gradle.template");
        Path legacyBuild = options.runtimeHostRoot().resolve("build.gradle");
        if (!Files.exists(buildTemplate) && !Files.exists(legacyBuild)) {
            throw new IOException("RuntimeHost template root must contain build.gradle.template: " + buildTemplate);
        }

        Path finalRoot = options.finalAppRoot();
        if (finalRoot == null) {
            throw new IOException("Final app root not provided");
        }
        if (pathsOverlap(finalRoot, options.runtimeHostRoot())) {
            throw new IOException("Final app root must not overlap RuntimeHost template root: " + finalRoot);
        }
        if (pathsOverlap(finalRoot, options.generatedArtifactRoot())) {
            throw new IOException("Final app root must not overlap generated artifact root: " + finalRoot);
        }
    }

    private static void requireDirectory(Path path, String label) throws IOException {
        if (path == null) {
            throw new IOException(label + " not provided");
        }
        if (!Files.isDirectory(path)) {
            throw new IOException(label + " not found: " + path);
        }
    }

    private static void ensureSafeDeleteTarget(Options options) throws IOException {
        Path finalRoot = options.finalAppRoot();
        if (isSameOrAncestor(finalRoot, options.runtimeHostRoot())) {
            throw new IOException("Refusing to delete a final app root that contains RuntimeHost template root: " + finalRoot);
        }
        if (isSameOrAncestor(finalRoot, options.generatedArtifactRoot())) {
            throw new IOException("Refusing to delete a final app root that contains generated artifact root: " + finalRoot);
        }
        if (finalRoot.getParent() == null) {
            throw new IOException("Refusing to delete filesystem root: " + finalRoot);
        }
    }

    private static boolean isSameOrAncestor(Path maybeAncestor, Path child) {
        return child.equals(maybeAncestor) || child.startsWith(maybeAncestor);
    }

    private static boolean pathsOverlap(Path left, Path right) {
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    private static void deleteTree(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            var paths = stream.sorted((left, right) -> right.compareTo(left)).toList();
            for (Path path : paths) {
                deletePathWithRetry(path);
            }
        }
    }

    private static void deletePathWithRetry(Path path) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= DELETE_RETRY_ATTEMPTS; attempt++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (IOException ex) {
                lastFailure = ex;
                if (attempt >= DELETE_RETRY_ATTEMPTS) {
                    break;
                }
                sleepBeforeRetry(path, attempt, ex);
            }
        }
        throw lastFailure;
    }

    private static void sleepBeforeRetry(Path path, int attempt, IOException ex) throws IOException {
        try {
            Thread.sleep(DELETE_RETRY_DELAY_MILLIS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            IOException wrapped = new IOException(
                    "Interrupted while retrying delete for " + path + " after attempt " + attempt,
                    interrupted
            );
            wrapped.addSuppressed(ex);
            throw wrapped;
        }
    }

    private static CopyStats copyTree(Path sourceRoot, Path destinationRoot, CopyMode mode, Options options)
            throws IOException {
        Files.createDirectories(destinationRoot);
        CopyStats stats = new CopyStats();

        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(sourceRoot) && shouldSkipDirectory(sourceRoot, dir, mode, options)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }

                Path destination = destinationRoot.resolve(sourceRoot.relativize(dir).toString()).normalize();
                Files.createDirectories(destination);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (shouldSkipFile(sourceRoot, file, mode)) {
                    return FileVisitResult.CONTINUE;
                }

                Path relative = sourceRoot.relativize(file);
                Path destination = destinationRoot.resolve(relative.toString()).normalize();
                Files.createDirectories(destination.getParent());
                Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
                stats.incrementFilesCopied();
                return FileVisitResult.CONTINUE;
            }
        });

        return stats;
    }

    private static boolean shouldSkipDirectory(Path sourceRoot, Path dir, CopyMode mode, Options options) {
        Path relative = sourceRoot.relativize(dir);
        String name = dir.getFileName().toString();
        if (EXCLUDED_DIRECTORY_NAMES.contains(name)) {
            return true;
        }
        if (name.equals(options.generatedFolderName()) || name.equals(options.metaFolderName())) {
            return true;
        }
        return mode == CopyMode.GENERATED_ARTIFACT && isGeneratedMigrationArtifactPath(relative);
    }

    private static boolean shouldSkipFile(Path sourceRoot, Path file, CopyMode mode) {
        Path relative = sourceRoot.relativize(file);
        String fileName = file.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);

        if (EXCLUDED_FILE_NAMES.contains(fileName)
                || lower.endsWith(".log")
                || lower.endsWith(".tmp")
                || lower.endsWith(".bak")
                || lower.endsWith(".iml")) {
            return true;
        }

        if (mode == CopyMode.RUNTIME_HOST_BASE && relative.getNameCount() == 1) {
            return fileName.equals("build.gradle")
                    || fileName.equals("build.gradle.template")
                    || fileName.equals("settings.gradle.template");
        }

        if (mode == CopyMode.RUNTIME_HOST_BASE
                && (isUnsupportedRuntimeHostControllerSource(relative)
                || isUnsupportedRuntimeHostServiceSource(relative))) {
            return true;
        }

        return mode == CopyMode.GENERATED_ARTIFACT && isGeneratedMigrationArtifactPath(relative);
    }

    private static boolean isUnsupportedRuntimeHostControllerSource(Path relative) {
        String normalized = relative.toString().replace('\\', '/');
        String prefix = "src/main/java/";
        if (!normalized.startsWith(prefix)) {
            return false;
        }
        String javaSource = normalized.substring(prefix.length());
        for (String pattern : UNSUPPORTED_RUNTIME_HOST_CONTROLLER_SOURCES) {
            if (matchesGlob(javaSource, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnsupportedRuntimeHostServiceSource(Path relative) {
        String normalized = relative.toString().replace('\\', '/');
        String prefix = "src/main/java/";
        if (!normalized.startsWith(prefix)) {
            return false;
        }
        String javaSource = normalized.substring(prefix.length());
        for (String pattern : UNSUPPORTED_RUNTIME_HOST_SERVICE_SOURCES) {
            if (matchesGlob(javaSource, pattern)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesGlob(String value, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int index = 0; index < pattern.length(); index++) {
            char ch = pattern.charAt(index);
            if (ch == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return value.matches(regex.toString());
    }

    private static boolean isGeneratedMigrationArtifactPath(Path relative) {
        String normalized = relative.toString().replace('\\', '/');
        return normalized.startsWith("src/main/resources/db/migration/")
                || normalized.startsWith("src/main/resources/db/migration-plans/")
                || normalized.startsWith("src/main/resources/db/schema-snapshots/");
    }

    private static void materializeRootTemplate(Path sourceRoot, Path destinationRoot, String fileName) throws IOException {
        Path template = sourceRoot.resolve(fileName + ".template");
        Path legacy = sourceRoot.resolve(fileName);
        Path source = Files.exists(template) ? template : legacy;
        if (!Files.exists(source)) {
            return;
        }

        Files.copy(source, destinationRoot.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
    }

    private static int copyGeneratedMigrations(Options options) throws IOException {
        Path sourceDir = options.canonicalMigrationsDir();
        if (sourceDir == null || !Files.isDirectory(sourceDir)) {
            return 0;
        }

        Path destinationDir = options.finalAppRoot()
                .resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("db")
                .resolve("migration");
        Files.createDirectories(destinationDir);

        int copied = 0;
        try (var stream = Files.list(sourceDir)) {
            for (Path source : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("R__"))
                    .filter(path -> path.getFileName().toString().endsWith(".sql"))
                    .toList()) {
                Files.copy(
                        source,
                        destinationDir.resolve(source.getFileName().toString()),
                        StandardCopyOption.REPLACE_EXISTING
                );
                copied++;
            }
        }
        return copied;
    }

    private static boolean copyModelDiffBaseline(Options options) throws IOException {
        Path migrationsDir = options.canonicalMigrationsDir();
        if (migrationsDir == null) {
            return false;
        }

        Path dbRoot = migrationsDir.getParent();
        if (dbRoot == null) {
            return false;
        }

        Path source = dbRoot.resolve("schema-snapshots").resolve("latest-storage-schema.json");
        if (!Files.isRegularFile(source)) {
            return false;
        }

        Path destination = options.finalAppRoot()
                .resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("npdev")
                .resolve("model-diff-baseline.json");
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    private static void writeSchemaRealizationManifest(
            Options options,
            int schemaRealizationSqlCount,
            boolean modelDiffBaselineInstalled
    ) throws IOException {
        Path destination = schemaRealizationManifestPath(options);
        Files.createDirectories(destination.getParent());

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("manifestVersion", "1.0.0");
        manifest.put("surfaceName", "NPDev Schema Realization Support");
        manifest.put("surfaceType", "schema-realization");
        manifest.put("supportStatus", "supported");
        manifest.put("deliveryMode", "recreate-style-app");
        manifest.put("supportedWorkflow", List.of("validate", "compile", "generate", "assemble", "build", "run", "verify"));
        manifest.put("schemaRealizationEnabled", true);
        manifest.put("upgradeManagementSupported", false);
        manifest.put("upgradeManagementStatus", "unsupported");
        manifest.put("canonicalRuntimeSchemaSqlPath", "src/main/resources/db/migration");
        manifest.put("schemaRealizationSqlFilePattern", "R__*.sql");
        manifest.put("schemaRealizationSqlCount", schemaRealizationSqlCount);
        manifest.put("generatedArtifactMount", options.generatedFolderName());
        manifest.put(
                "modelDiffBaselinePath",
                modelDiffBaselineInstalled ? "src/main/resources/npdev/model-diff-baseline.json" : ""
        );
        manifest.put("internalAnalysisArtifacts", List.of("db/migration-plans", "db/schema-snapshots"));
        manifest.put(
                "notes",
                List.of(
                        "Repeatable schema SQL supports recreate-style app realization.",
                        "Stateful upgrade management remains outside the supported public NPDev path."
                )
        );

        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(destination.toFile(), manifest);
    }

    private static Path schemaRealizationManifestPath(Options options) {
        return options.finalAppRoot()
                .resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("npdev")
                .resolve("support")
                .resolve("schema-realization.manifest.json");
    }

    private enum CopyMode {
        RUNTIME_HOST_BASE,
        GENERATED_ARTIFACT
    }

    private static final class CopyStats {
        private int filesCopied;

        int filesCopied() {
            return filesCopied;
        }

        void incrementFilesCopied() {
            filesCopied++;
        }
    }

    public record Options(
            Path runtimeHostRoot,
            Path generatedArtifactRoot,
            Path finalAppRoot,
            Path canonicalMigrationsDir,
            String generatedFolderName,
            String metaFolderName,
            boolean deleteBeforeMount
    ) {
        Options normalized() {
            return new Options(
                    normalize(runtimeHostRoot),
                    normalize(generatedArtifactRoot),
                    normalize(finalAppRoot),
                    canonicalMigrationsDir == null ? null : normalize(canonicalMigrationsDir),
                    normalizeName(generatedFolderName, DEFAULT_GENERATED_FOLDER_NAME),
                    normalizeName(metaFolderName, "npdev-meta"),
                    deleteBeforeMount
            );
        }

        private static Path normalize(Path path) {
            if (path == null) {
                return null;
            }
            return path.toAbsolutePath().normalize();
        }

        private static String normalizeName(String value, String fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return value.trim();
        }
    }

    public record AssemblyResult(
            Path finalAppRoot,
            Path generatedMount,
            int runtimeHostFilesCopied,
            int generatedFilesCopied,
            int generatedMigrationsCopied,
            boolean modelDiffBaselineInstalled
    ) {
    }
}
