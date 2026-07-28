package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Schema-engine rebuild, Phase 7 (SER-P7.2): sibling of {@link SchemaRealizationEmitter}. Reads every
 * operator-authored conversion hook folder from the app definition's {@code migrations/} directory
 * ({@code definition/migrations/<ordinal>-<slug>/hook.json} + {@code convert.sql} + optional
 * engine-variant SQL), validates each {@code hook.json} against {@code conversion-hook.schema.json} at
 * GENERATION time (an invalid hook is a generation ERROR naming the file and the violated rule, never a
 * boot-time surprise), and copies each valid hook into the FinalApp at
 * {@code src/main/resources/db/conversion-hooks/<id>/} so {@code ConversionHookRunner} can load it from
 * the classpath at boot.
 *
 * <p>An app with no {@code definition/migrations/} directory emits nothing (hooks are opt-in).
 *
 * <p><b>Ordering note:</b> the destination folder is the sanitized {@code id}, not the original
 * {@code <ordinal>-<slug>} definition-side folder name -- so {@code id} is what {@code
 * ConversionHookRunner} sorts on at runtime (natural, numeric-aware order) to honor the plan's
 * "ascending ordinal order" rule. Authors should choose an {@code id} that starts with the same ordinal
 * as its definition-side folder (e.g. {@code "001-widen-name"}), mirroring the folder convention.
 */
public final class ConversionHookEmitter {

    private static final String SCHEMA_RESOURCE_PATH = "schema/conversion-hook.schema.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public void emit(Path modelSourcePath, Path outRoot) throws IOException {
        if (modelSourcePath == null || outRoot == null) {
            return;
        }
        Path definitionDir = modelSourcePath.getParent();
        if (definitionDir == null) {
            return;
        }
        Path migrationsDir = definitionDir.resolve("migrations");
        if (!Files.isDirectory(migrationsDir)) {
            return;
        }
        JsonSchema schema = loadSchema();
        Path hooksOut = outRoot.resolve("src").resolve("main").resolve("resources")
                .resolve("db").resolve("conversion-hooks");
        List<Path> hookDirs;
        try (Stream<Path> children = Files.list(migrationsDir)) {
            hookDirs = children.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        for (Path hookDir : hookDirs) {
            emitOne(hookDir, schema, hooksOut);
        }
    }

    private void emitOne(Path hookDir, JsonSchema schema, Path hooksOut) throws IOException {
        Path hookJson = hookDir.resolve("hook.json");
        if (!Files.isRegularFile(hookJson)) {
            throw new IllegalStateException("Conversion hook folder '" + hookDir.getFileName()
                    + "' is missing hook.json (expected at " + hookJson + ").");
        }
        String rawJson = Files.readString(hookJson, StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(rawJson);
        } catch (Exception exception) {
            throw new IllegalStateException("Conversion hook '" + hookJson
                    + "' is not valid JSON: " + exception.getMessage(), exception);
        }
        validate(root, hookJson, schema);

        String id = root.path("id").asText("");
        String safeId = sanitizeId(id, hookJson);

        Path convertSql = hookDir.resolve("convert.sql");
        if (!Files.isRegularFile(convertSql)) {
            throw new IllegalStateException("Conversion hook '" + hookJson + "' (id '" + id
                    + "') is missing its common convert.sql (expected at " + convertSql + ").");
        }

        Path destDir = hooksOut.resolve(safeId);
        Files.createDirectories(destDir);
        copyIfPresent(hookJson, destDir.resolve("hook.json"));
        copyIfPresent(convertSql, destDir.resolve("convert.sql"));
        copyIfPresent(hookDir.resolve("convert.h2.sql"), destDir.resolve("convert.h2.sql"));
        copyIfPresent(hookDir.resolve("convert.postgres.sql"), destDir.resolve("convert.postgres.sql"));
    }

    private void validate(JsonNode root, Path hookJson, JsonSchema schema) {
        Set<ValidationMessage> violations = schema.validate(root);
        if (violations.isEmpty()) {
            return;
        }
        List<String> messages = violations.stream()
                .map(ValidationMessage::getMessage)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        StringBuilder builder = new StringBuilder("Conversion hook schema validation failed for ")
                .append(hookJson).append(":");
        for (String message : messages) {
            builder.append(System.lineSeparator()).append(" - ").append(message);
        }
        throw new IllegalStateException(builder.toString());
    }

    private static String sanitizeId(String id, Path hookJson) {
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Conversion hook '" + hookJson + "' has a blank id.");
        }
        String safe = id.trim().replaceAll("[^A-Za-z0-9_-]", "-");
        if (safe.isBlank()) {
            throw new IllegalStateException("Conversion hook '" + hookJson + "' id '" + id
                    + "' does not sanitize to a safe folder name.");
        }
        return safe;
    }

    private static void copyIfPresent(Path source, Path dest) throws IOException {
        if (Files.isRegularFile(source)) {
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static JsonSchema loadSchema() {
        try (InputStream stream = ConversionHookEmitter.class.getClassLoader().getResourceAsStream(SCHEMA_RESOURCE_PATH)) {
            if (stream == null) {
                throw new IllegalStateException("Unable to locate conversion-hook schema resource: " + SCHEMA_RESOURCE_PATH);
            }
            String schemaJson = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            return factory.getSchema(schemaJson);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to load conversion-hook schema resource: " + SCHEMA_RESOURCE_PATH, exception);
        }
    }
}
