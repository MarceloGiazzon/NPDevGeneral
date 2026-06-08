package com.npdev.generator.assembly;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
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
            "libs",
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
            "com/finalexec/api/experimental/*.java"
    );
    private static final List<String> UNSUPPORTED_RUNTIME_HOST_SERVICE_SOURCES = List.of(
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

        int schemaRealizationCount = countSchemaRealizationArtifacts(generatedMount);
        writeAiBetaLocalProfile(normalized, generatedMount);
        writeSchemaRealizationManifest(normalized, schemaRealizationCount);

        return new AssemblyResult(
                normalized.finalAppRoot(),
                generatedMount,
                hostStats.filesCopied(),
                generatedStats.filesCopied(),
                schemaRealizationCount
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
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= DELETE_RETRY_ATTEMPTS; attempt++) {
            try (var stream = Files.walk(root)) {
                var paths = stream.sorted((left, right) -> right.compareTo(left)).toList();
                for (Path path : paths) {
                    deletePathWithRetry(path);
                }
            }
            if (!Files.exists(root)) {
                return;
            }

            lastFailure = new IOException("Delete target still exists after attempt " + attempt + ": " + root);
            if (attempt < DELETE_RETRY_ATTEMPTS) {
                sleepBeforeRetry(root, attempt, lastFailure);
            }
        }

        throw lastFailure;
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

    private static int countSchemaRealizationArtifacts(Path generatedMount) throws IOException {
        Path realizationDir = generatedMount
                .resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("db")
                .resolve("schema-realization");
        if (!Files.isDirectory(realizationDir)) {
            return 0;
        }
        try (var stream = Files.walk(realizationDir)) {
            return (int) stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".sql")
                            || path.getFileName().toString().endsWith(".json"))
                    .count();
        }
    }

    private static void writeAiBetaLocalProfile(Options options, Path generatedMount) throws IOException {
        Path generatedModel = generatedMount
                .resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("npdev")
                .resolve("model.json");
        if (!Files.isRegularFile(generatedModel)) {
            return;
        }

        var root = OBJECT_MAPPER.readTree(generatedModel.toFile());
        var metadata = root.path("metadata");
        String scenarioId = text(metadata, "scenarioId");
        List<ApiKeyMapping> mappings = new ArrayList<>();
        mappings.add(new ApiKeyMapping("api-dev", "dev", "developer", List.of("admin")));
        mappings.add(new ApiKeyMapping("dev-key", "dev", "developer", List.of("admin")));

        for (var userNode : metadata.path("auth").path("testUsers")) {
            String userId = text(userNode, "userId");
            String tenantId = text(userNode, "tenantId");
            if (userId.isBlank() || tenantId.isBlank()) {
                continue;
            }
            List<String> roles = new ArrayList<>();
            for (var roleNode : userNode.path("roles")) {
                String role = roleNode.asText("").trim();
                if (!role.isBlank()) {
                    roles.add(role);
                }
            }
            if (!roles.isEmpty()) {
                String apiKey = "ai-" + slug(scenarioId) + "-" + slug(userId);
                mappings.add(new ApiKeyMapping(apiKey, tenantId, userId, List.copyOf(roles)));
            }
        }
        if (mappings.size() <= 2) {
            return;
        }

        String encodedMappings = encodeMappings(mappings);
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Generated by NPDevGenerator for expanded Beta 0 local smoke evidence.\n");
        yaml.append("npdev:\n");
        yaml.append("  auth:\n");
        yaml.append("    mode: apikey\n");
        yaml.append("    api-keys: \"").append(escapeYamlDoubleQuoted(encodedMappings)).append("\"\n");
        yaml.append("  security:\n");
        yaml.append("    apiKey:\n");
        yaml.append("      required: true\n");
        yaml.append("    encodedMappings: \"").append(escapeYamlDoubleQuoted(encodedMappings)).append("\"\n");
        yaml.append("    apiKeys:\n");
        for (ApiKeyMapping mapping : mappings) {
            yaml.append("      ").append(mapping.apiKey()).append(":\n");
            yaml.append("        actor: ").append(mapping.actorId()).append("\n");
            yaml.append("        tenant: ").append(mapping.tenantId()).append("\n");
            yaml.append("        roles: [").append(String.join(", ", mapping.roles())).append("]\n");
        }

        Path destination = options.finalAppRoot()
                .resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("application-ai-beta-local.yml");
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, yaml.toString());
    }

    private static String encodeMappings(List<ApiKeyMapping> mappings) {
        List<String> encoded = new ArrayList<>();
        for (ApiKeyMapping mapping : mappings) {
            encoded.add(mapping.apiKey() + "=" + mapping.tenantId() + ":" + mapping.actorId() + ":"
                    + String.join("|", mapping.roles()));
        }
        return String.join(";", encoded);
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String fieldName) {
        var value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String slug(String value) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        normalized = normalized.replaceAll("^-+|-+$", "");
        return normalized.isBlank() ? "user" : normalized;
    }

    private static String escapeYamlDoubleQuoted(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void writeSchemaRealizationManifest(
            Options options,
            int schemaRealizationSqlCount
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
        manifest.put("canonicalRuntimeSchemaSqlPath", "src/main/resources/db/schema-realization");
        manifest.put("businessSchemaRealizationSqlFilePattern", "V*__npdev_schema_realization.sql");
        manifest.put("schemaRealizationSqlCount", schemaRealizationSqlCount);
        manifest.put("runtimeSchemaSqlPattern", "V*.sql");
        manifest.put("storageBoundary", Map.of(
                "runtimeTables", "NPDev execution, audit, trace, scheduling, and reliability data",
                "businessTables", "Generated model concept data"
        ));
        manifest.put("generatedArtifactMount", options.generatedFolderName());
        manifest.put("internalAnalysisArtifacts", List.of());
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

    private record ApiKeyMapping(String apiKey, String tenantId, String actorId, List<String> roles) {
        ApiKeyMapping {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
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
            int schemaRealizationArtifactsCopied
    ) {
    }
}
