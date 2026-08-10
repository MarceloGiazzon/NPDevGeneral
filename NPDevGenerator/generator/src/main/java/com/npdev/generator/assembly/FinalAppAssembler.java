package com.npdev.generator.assembly;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
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
            "npdev-build-info.properties",
            // F2 (FIRST_IMPRESSION_SPEC.md I3): maintainer-facing digests/policy about the
            // NPDevRuntimeHost TEMPLATE itself and this SOURCE REPO's own build-output-location
            // policy -- neither means anything in a generated app living outside this repo (its own
            // build/ dir is normal there), and NO_BUILD_ARTIFACTS.policy actively contradicts a
            // generated app's own docker/gradle build. README.md is handled separately (overwritten
            // post-copy by writeAppReadme, not excluded, since every app needs SOME README).
            "PROJECT_DIGEST.md",
            "MIGRATION_DIGEST.md",
            "NO_BUILD_ARTIFACTS.policy"
    );
    private static final List<String> UNSUPPORTED_RUNTIME_HOST_CONTROLLER_SOURCES = List.of(
            "com/finalexec/HelloController.java",
            "com/finalexec/api/experimental/*.java"
    );
    private static final List<String> UNSUPPORTED_RUNTIME_HOST_SERVICE_SOURCES = List.of(
            "com/finalexec/npdev/service/experimental/*.java"
    );

    /**
     * PORT-1: the one directory under a FinalApp root that regeneration must not remove -- the app's
     * own database. Must stay in step with {@code UserDatabaseDefinitionLoader.DATA_ROOT_FOLDER},
     * which decides where the app and its {@code _ops} toolbox both look.
     *
     * <p>Twin-pair {@code app-data-root-anchor-three-seams} (token: npdev-app-data-root-anchor).
     * This SPARES the root the other two decide and resolve; a drift here is silent data loss on the
     * next regeneration.
     */
    private static final String PRESERVED_APP_DATA_DIRECTORY = "data";

    public AssemblyResult assemble(Options options) throws IOException {
        Options normalized = options.normalized();
        validate(normalized);

        if (normalized.deleteBeforeMount() && Files.exists(normalized.finalAppRoot())) {
            ensureSafeDeleteTarget(normalized);
            deleteTree(normalized.finalAppRoot());
        }
        // PORT-1: an app's database now lives at <FinalApp>/data (app-relative, so a generated app
        // can be handed to someone else and still find it). deleteBeforeMount predates that and
        // would take the database with it on every regeneration -- which is not merely data loss, it
        // is data loss that leaves the schema-evolution tests green against a fresh database instead
        // of the existing one they exist to exercise. deleteTree() therefore spares exactly this one
        // directory; everything mounted afterwards is regenerated anyway.

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
        writeAppReadme(normalized, generatedMount);
        appendRuntimeHostLibsDirDefault(normalized);
        appendAppJavaVersionDefault(normalized);

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

    /**
     * Delete the FinalApp tree, sparing the app's own database directory.
     *
     * <p>PORT-1: {@code <FinalApp>/data} is where every generated app keeps its database now that the
     * path is app-relative rather than an absolute path from the authoring machine. It is the one
     * thing under this root that regeneration cannot reproduce, so it is the one thing this does not
     * remove. Everything else here is emitted output.
     *
     * <p>Consequence, stated rather than left to be discovered: this method no longer guarantees the
     * root is gone, so the "still exists" retry now asks whether anything BUT the spared directory
     * survived.
     */
    private static void deleteTree(Path root) throws IOException {
        Path preserved = root.resolve(PRESERVED_APP_DATA_DIRECTORY);
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= DELETE_RETRY_ATTEMPTS; attempt++) {
            try (var stream = Files.walk(root)) {
                var paths = stream
                        .filter(path -> !path.startsWith(preserved))
                        .sorted((left, right) -> right.compareTo(left))
                        .toList();
                for (Path path : paths) {
                    if (path.equals(root) && Files.exists(preserved)) {
                        continue;
                    }
                    deletePathWithRetry(path);
                }
            }
            if (!Files.exists(root) || onlyPreservedDataRemains(root, preserved)) {
                return;
            }

            lastFailure = new IOException("Delete target still exists after attempt " + attempt + ": " + root);
            if (attempt < DELETE_RETRY_ATTEMPTS) {
                sleepBeforeRetry(root, attempt, lastFailure);
            }
        }

        throw lastFailure;
    }

    private static boolean onlyPreservedDataRemains(Path root, Path preserved) throws IOException {
        try (var entries = Files.list(root)) {
            return entries.allMatch(entry -> entry.equals(preserved));
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
        // SER-P9.1: the db/migration-plans/ entry was here to stop stale residue from the dead
        // com.finalexec.npdev.migration.* lineage leaking into freshly assembled apps; that lineage
        // is deleted now, so there is nothing left to skip it for.
        return normalized.startsWith("src/main/resources/db/migration/")
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

    /**
     * F2 (FIRST_IMPRESSION_SPEC.md I3): the bulk {@code copyTree} of {@code runtimeHostRoot} at the
     * top of {@link #assemble} carries README.md along with it -- the app a newcomer generates ships
     * with NPDevRuntimeHost's OWN maintainer-facing README ("Provide the template runtime shell that
     * hosts assembled NPDev applications...", byte-identical), not a word about the app itself.
     * Overwrites it, after that copy, with one derived from the compiled model that actually shipped
     * ({@code compiled-model.json} -- unlike the AI-beta-scenario-only {@code model.json}
     * {@link #writeAiBetaLocalProfile} reads, this file is written for every generated app, see
     * {@code GeneratorMainMigrationPlanCliTest}). A plain {@code README.md.template} next to
     * {@code build.gradle.template} (this class's existing convention, via
     * {@link #materializeRootTemplate}) cannot do this: that mechanism is a byte-for-byte copy with
     * no placeholder substitution, and the DoD needs the namespace/version to actually appear.
     */
    private static void writeAppReadme(Options options, Path generatedMount) throws IOException {
        Path compiledModel = generatedMount
                .resolve("src")
                .resolve("main")
                .resolve("resources")
                .resolve("npdev")
                .resolve("compiled-model.json");
        if (!Files.isRegularFile(compiledModel)) {
            return;
        }

        var root = OBJECT_MAPPER.readTree(compiledModel.toFile());
        String namespace = text(root, "namespace");
        String version = text(root, "version");
        String dslVersion = text(root, "dslVersion");
        String title = namespace.isBlank() ? "NPDev Generated App" : namespace;

        String readme = "# " + title + "\n"
                + "\n"
                + "Generated by NPDev from the model `" + namespace + "`"
                + (version.isBlank() ? "" : " (model version `" + version + "`)")
                + (dslVersion.isBlank() ? "" : ", DSL `" + dslVersion + "`") + ".\n"
                + "**This directory is generated output -- to pick up a model change, re-run\n"
                + "`npdev generate app` against the updated model rather than hand-editing files here.**\n"
                + "\n"
                + "## Run it\n"
                + "\n"
                + "**Build the jar first, whichever way you run the app** -- the generated `Dockerfile`\n"
                + "packages an already-built jar, it does not run Gradle inside the image:\n"
                + "\n"
                + "```sh\n"
                + "./gradlew bootJar\n"
                + "```\n"
                + "\n"
                + "Then either run it directly:\n"
                + "\n"
                + "```sh\n"
                + "java -jar build/libs/FinalExec-0.1.0.jar --spring.profiles.active=dev\n"
                + "# open http://localhost:8080 (see docker-compose.yml's ports: mapping if you\n"
                + "# changed the port at generation time -- it varies per generated app)\n"
                + "```\n"
                + "\n"
                + "or in Docker:\n"
                + "\n"
                + "```sh\n"
                + "cp .env.example .env    # set NPDEV_AUTH_APIKEYS at minimum\n"
                + "docker compose up\n"
                + "```\n"
                + "\n"
                + "## Where things are\n"
                + "\n"
                + "- **Admin UI** -- the generated CRUD admin UI is served from the app root (`/`).\n"
                + "- **REST API** -- generated endpoints live under `/api/**`.\n"
                + "- **Login / bootstrap** -- `POST /api/auth/login` mints a session token; the Super User\n"
                + "  bootstrap key is written to `SUPER_USER_KEY.txt` on first boot (see the comments in this\n"
                + "  app's `docker-compose.yml`).\n"
                + "\n"
                + "## Database\n"
                + "\n"
                + "Starts against an embedded dev/test engine by default (no separate database container).\n"
                + "To deploy against Postgres instead (a separate container, durable across restarts),\n"
                + "regenerate this app with `db.engine=Postgres` in `db.definition.json` -- see\n"
                + "`docs/DEPLOYMENT.md` in the NPDev platform repository this app was generated from.\n";

        Files.writeString(options.finalAppRoot().resolve("README.md"), readme);
    }

    /**
     * REG-128 / N2 (FIRST_IMPRESSION_PLAN.md I8): {@code resolveNpdevRuntimeLibsDir} in the
     * materialized {@code build.gradle} (see {@code NPDevRuntimeHost/build.gradle.template}) walks
     * UP from the assembled app's own directory looking for a sibling {@code .npdev-root} marker --
     * a heuristic that only works when the assembled app happens to sit nested under (or beside) the
     * source repo it was generated from. An app assembled anywhere else (the common case: {@code
     * --output} deliberately points OUTSIDE this repo, per this README's own Quickstart) never finds
     * the marker and falls through to a nonsensical relative fallback, so {@code ./gradlew bootJar}
     * cannot find the platform jars even after {@code sync-runtimehost-libs.ps1 -BuildLocalJars} ran
     * successfully.
     *
     * <p>Appends (never overwrites -- {@code NPDevRuntimeHost/gradle.properties}'s own REG-10
     * comment explains why the checked-in template must never carry a hardcoded path) a resolved
     * {@code npdevRuntimeHostLibsDir} default to the assembled app's {@code gradle.properties},
     * computed the SAME way {@code scripts/npdev-common.ps1}'s {@code Get-NPDevRuntimeHostLibsDir}
     * does (env var override, else {@code <this repo's parent>/Build/runtimehost-libs}) -- so a
     * freshly generated app finds the jars {@code sync-runtimehost-libs.ps1}'s OWN default just
     * wrote, with no manual step. Because this always bakes a value into {@code gradle.properties},
     * and {@code providers.gradleProperty()} can't tell "explicit -P" apart from "read from the
     * properties file", a build-time {@code NPDEV_RUNTIMEHOST_LIBS_DIR} env var override would be
     * permanently shadowed by this generation-time default -- REG-137 fixed this by having the
     * template check the env var BEFORE the gradle property. An explicit
     * {@code -PnpdevRuntimeHostLibsDir=...} passed to a later {@code gradlew} invocation still wins
     * whenever no env var is set.
     */
    private static void appendRuntimeHostLibsDirDefault(Options options) throws IOException {
        Path repoRoot = options.runtimeHostRoot().toAbsolutePath().normalize().getParent();
        if (repoRoot == null) {
            return;
        }
        String resolved = System.getenv("NPDEV_RUNTIMEHOST_LIBS_DIR");
        if (resolved == null || resolved.isBlank()) {
            String buildRoot = System.getenv("NPDEV_BUILD_ROOT");
            Path buildRootPath = (buildRoot == null || buildRoot.isBlank())
                    ? repoRoot.resolveSibling("Build")
                    : Path.of(buildRoot);
            resolved = buildRootPath.resolve("runtimehost-libs").toString();
        }
        // gradle.properties is parsed as a Java .properties file, where "\" is an escape
        // character -- a raw Windows path would corrupt on read. Forward slashes are accepted by
        // Gradle/the JVM on every OS this template ships for.
        String propertyValue = resolved.replace('\\', '/');

        Path gradleProperties = options.finalAppRoot().resolve("gradle.properties");
        String appended = "\n# npdev generate app (REG-128): resolved default for this machine/session --\n"
                + "# an explicit -PnpdevRuntimeHostLibsDir=... on the gradlew command line still wins.\n"
                + "npdevRuntimeHostLibsDir=" + propertyValue + "\n";
        Files.writeString(
                gradleProperties, appended, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
        );
    }

    /**
     * deps-and-java/PLAN.md W1.4: append-only, mirroring {@link #appendRuntimeHostLibsDirDefault}'s
     * own convention (REG-128) -- always bakes the resolved value in, even when it is the same 17
     * the template's own fallback would already pick, so gradle.properties is never ambiguous about
     * what level a given generated app actually targets.
     */
    private static void appendAppJavaVersionDefault(Options options) throws IOException {
        Path gradleProperties = options.finalAppRoot().resolve("gradle.properties");
        String appended = "\n# npdev generate app (deps-and-java/PLAN.md W1.4): this app's own Gradle toolchain level --\n"
                + "# from config.json's build.javaVersion (default 17 when absent).\n"
                + "npdevAppJavaVersion=" + options.javaVersion() + "\n";
        Files.writeString(
                gradleProperties, appended, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND
        );
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
        // Insertion-ordered, not Map.of(...): java.util.Map.of produces an ImmutableCollections.MapN
        // whose iteration order is randomized per-JVM by ImmutableCollections.SALT. Jackson serializes
        // maps in iteration order, so a Map.of here made this manifest's two storageBoundary keys emit
        // in a run-to-run varying order -- the sole source of GATE-DET-1's byte-nondeterminism.
        Map<String, Object> storageBoundary = new LinkedHashMap<>();
        storageBoundary.put("runtimeTables", "NPDev execution, audit, trace, scheduling, and reliability data");
        storageBoundary.put("businessTables", "Generated model concept data");
        manifest.put("storageBoundary", storageBoundary);
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
            boolean deleteBeforeMount,
            /** deps-and-java/PLAN.md W1.3/W1.4: the generated app's own Gradle toolchain level
             *  (config.json's build.javaVersion, already validated against {17,21} by the caller).
             *  Platform modules never read this -- only the assembled app's own gradle.properties. */
            int javaVersion
    ) {
        Options normalized() {
            return new Options(
                    normalize(runtimeHostRoot),
                    normalize(generatedArtifactRoot),
                    normalize(finalAppRoot),
                    canonicalMigrationsDir == null ? null : normalize(canonicalMigrationsDir),
                    normalizeName(generatedFolderName, DEFAULT_GENERATED_FOLDER_NAME),
                    normalizeName(metaFolderName, "npdev-meta"),
                    deleteBeforeMount,
                    javaVersion <= 0 ? 17 : javaVersion
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
