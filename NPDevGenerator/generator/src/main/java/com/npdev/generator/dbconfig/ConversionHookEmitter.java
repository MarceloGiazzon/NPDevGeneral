package com.npdev.generator.dbconfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledConversion;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        emit(null, modelSourcePath, outRoot);
    }

    /**
     * S7 Phase B (B13): {@code model} is optional (null keeps this method's original operator-only
     * behavior) -- when present, every {@link CompiledModel#getConversions()} entry is ALSO compiled
     * to a {@code hook.json}/{@code convert.sql} pair and written to the SAME {@code hooksOut}
     * directory as the operator-authored ones above, so {@code ConversionHookRunner} runs both
     * through one execution path. An {@code id} declared by both sources is a generation error --
     * silently letting one overwrite the other in the destination folder would be exactly the kind
     * of silent conflict this vocabulary's own X0 rule forbids.
     */
    public void emit(CompiledModel model, Path modelSourcePath, Path outRoot) throws IOException {
        if (outRoot == null) {
            return;
        }
        Path hooksOut = outRoot.resolve("src").resolve("main").resolve("resources")
                .resolve("db").resolve("conversion-hooks");
        Set<String> emittedIds = new HashSet<>();

        if (modelSourcePath != null) {
            Path definitionDir = modelSourcePath.getParent();
            if (definitionDir != null) {
                Path migrationsDir = definitionDir.resolve("migrations");
                if (Files.isDirectory(migrationsDir)) {
                    JsonSchema schema = loadSchema();
                    List<Path> hookDirs;
                    try (Stream<Path> children = Files.list(migrationsDir)) {
                        hookDirs = children.filter(Files::isDirectory)
                                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                                .toList();
                    }
                    for (Path hookDir : hookDirs) {
                        String id = emitOne(hookDir, schema, hooksOut);
                        if (!emittedIds.add(id.toLowerCase(Locale.ROOT))) {
                            throw new IllegalStateException("Conversion hook id '" + id
                                    + "' is declared more than once under " + hookDir.getParent());
                        }
                    }
                }
            }
        }

        if (model != null) {
            for (CompiledConversion conversion : model.getConversions()) {
                if (!emittedIds.add(conversion.id().toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("conversions[] id '" + conversion.id()
                            + "' collides with an operator-authored migrations/ conversion hook id -- "
                            + "rename one of the two.");
                }
                emitDeclared(conversion, model, hooksOut);
            }
        }
    }

    private String emitOne(Path hookDir, JsonSchema schema, Path hooksOut) throws IOException {
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
        return id;
    }

    /**
     * S7 Phase B (B13): compiles one declared {@link CompiledConversion} to a hook.json/convert.sql
     * pair and writes it under {@code hooksOut}. Scope note: every conversion op targets a field the
     * compiler already checked exists on the concept, but every generated hook claims
     * {@code ADD_REQUIRED_COLUMN} -- i.e. this v1 supports a target field that is BRAND NEW in this
     * model version (the spec's own three examples, and the only shape that reaches
     * {@code ConversionHookRunner} at all: a field that already exists and merely needs data
     * migrated converges as SAFE_ADDITIVE/NEEDS_BACKFILL with no residual diff item for a hook to
     * claim -- see {@code SchemaDiffEngine}). {@code ADD COLUMN IF NOT EXISTS} + a {@code WHERE col
     * IS NULL} guard on every UPDATE + a re-runnable {@code SET NOT NULL} make the whole hook
     * idempotent by construction (B12's guarantee), not by author discipline. A row the UPDATE
     * cannot populate (e.g. split with no space in the source value) is left NULL and the closing
     * {@code SET NOT NULL} then fails the boot loudly with the engine's own error -- never a silent
     * partial conversion.
     */
    private void emitDeclared(CompiledConversion conversion, CompiledModel model, Path hooksOut) throws IOException {
        CompiledConcept concept = model.findConcept(conversion.concept())
                .orElseThrow(() -> new IllegalStateException("conversion '" + conversion.id()
                        + "' declares concept '" + conversion.concept() + "', which is not a declared concept"));
        String table = SqlIdentifierSupport.tableName(concept);

        List<String> statements = new ArrayList<>();
        List<String> claims = new ArrayList<>();
        List<String> verifyNullChecks = new ArrayList<>();

        switch (conversion.op()) {
            case "copy" -> {
                CompiledField toField = requireField(concept, conversion.to());
                String toCol = SqlIdentifierSupport.columnName(toField);
                String fromCol = SqlIdentifierSupport.columnName(requireField(concept, conversion.from()));
                statements.add("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + toCol + " " + portableSqlType(toField));
                statements.add("UPDATE " + table + " SET " + toCol + " = " + fromCol + " WHERE " + toCol + " IS NULL");
                statements.add("ALTER TABLE " + table + " ALTER COLUMN " + toCol + " SET NOT NULL");
                claims.add("ADD_REQUIRED_COLUMN:" + table + ":" + toCol);
                verifyNullChecks.add(toCol);
            }
            case "split" -> {
                String fromCol = SqlIdentifierSupport.columnName(requireField(concept, conversion.from()));
                for (CompiledConversion.CompiledConversionSplitTarget target : conversion.into()) {
                    CompiledField targetField = requireField(concept, target.field());
                    String targetCol = SqlIdentifierSupport.columnName(targetField);
                    statements.add("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + targetCol + " " + portableSqlType(targetField));
                }
                StringBuilder setClause = new StringBuilder();
                StringBuilder nullGuard = new StringBuilder();
                for (CompiledConversion.CompiledConversionSplitTarget target : conversion.into()) {
                    String targetCol = SqlIdentifierSupport.columnName(requireField(concept, target.field()));
                    if (setClause.length() > 0) {
                        setClause.append(", ");
                        nullGuard.append(" OR ");
                    }
                    setClause.append(targetCol).append(" = ").append(splitExpression(fromCol, target.take()));
                    nullGuard.append(targetCol).append(" IS NULL");
                    claims.add("ADD_REQUIRED_COLUMN:" + table + ":" + targetCol);
                    verifyNullChecks.add(targetCol);
                }
                statements.add("UPDATE " + table + " SET " + setClause
                        + " WHERE (" + nullGuard + ") AND " + fromCol + " IS NOT NULL"
                        + " AND POSITION(' ' IN " + fromCol + ") > 0");
                for (String nullCheckCol : verifyNullChecks) {
                    statements.add("ALTER TABLE " + table + " ALTER COLUMN " + nullCheckCol + " SET NOT NULL");
                }
            }
            case "lookup" -> {
                CompiledConversion.CompiledConversionLookupMatch match = conversion.match();
                CompiledConcept matchConcept = model.findConcept(match.concept())
                        .orElseThrow(() -> new IllegalStateException("conversion '" + conversion.id()
                                + "' declares match.concept '" + match.concept() + "', which is not a declared concept"));
                String matchTable = SqlIdentifierSupport.tableName(matchConcept);
                String onCol = SqlIdentifierSupport.columnName(requireField(matchConcept, match.on()));
                String equalsCol = SqlIdentifierSupport.columnName(requireField(concept, match.equals()));
                CompiledField matchIdField = matchConcept.getFields().stream()
                        .filter(CompiledField::isId).findFirst()
                        .orElseThrow(() -> new IllegalStateException("concept '" + matchConcept.getName()
                                + "' (conversion '" + conversion.id() + "'s match.concept) has no id field"));
                String matchIdCol = SqlIdentifierSupport.columnName(matchIdField);
                CompiledField setField = requireField(concept, conversion.set());
                String setCol = SqlIdentifierSupport.columnName(setField);
                statements.add("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS " + setCol + " " + portableSqlType(setField));
                statements.add("UPDATE " + table + " SET " + setCol + " = (SELECT m." + matchIdCol
                        + " FROM " + matchTable + " m WHERE m." + onCol + " = " + table + "." + equalsCol
                        + ") WHERE " + setCol + " IS NULL");
                statements.add("ALTER TABLE " + table + " ALTER COLUMN " + setCol + " SET NOT NULL");
                claims.add("ADD_REQUIRED_COLUMN:" + table + ":" + setCol);
                verifyNullChecks.add(setCol);
            }
            default -> throw new IllegalStateException("conversion '" + conversion.id()
                    + "' declares unrecognized op '" + conversion.op() + "'");
        }

        String convertSql = String.join(";\n", statements) + ";\n";
        StringBuilder verifySql = new StringBuilder("SELECT COUNT(*) FROM ").append(table).append(" WHERE ");
        for (int i = 0; i < verifyNullChecks.size(); i++) {
            if (i > 0) {
                verifySql.append(" OR ");
            }
            verifySql.append(verifyNullChecks.get(i)).append(" IS NULL");
        }

        com.fasterxml.jackson.databind.node.ObjectNode hookJsonNode = OBJECT_MAPPER.createObjectNode();
        hookJsonNode.put("id", conversion.id());
        com.fasterxml.jackson.databind.node.ArrayNode claimsNode = hookJsonNode.putArray("claims");
        claims.forEach(claimsNode::add);
        hookJsonNode.put("description", "Generated from declared conversion '" + conversion.id()
                + "' (op=" + conversion.op() + ", concept=" + conversion.concept() + ").");
        hookJsonNode.put("verifySql", verifySql.toString());
        hookJsonNode.put("verifyExpect", 0);

        validate(hookJsonNode, Path.of("conversions[" + conversion.id() + "]"), loadSchema());

        String safeId = sanitizeId(conversion.id(), Path.of("conversions[" + conversion.id() + "]"));
        Path destDir = hooksOut.resolve(safeId);
        Files.createDirectories(destDir);
        Files.writeString(destDir.resolve("hook.json"),
                OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(hookJsonNode),
                StandardCharsets.UTF_8);
        Files.writeString(destDir.resolve("convert.sql"), convertSql, StandardCharsets.UTF_8);
    }

    private static CompiledField requireField(CompiledConcept concept, String fieldName) {
        return concept.getFields().stream()
                .filter(field -> field.getName() != null && field.getName().equalsIgnoreCase(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("concept '" + concept.getName()
                        + "' has no field '" + fieldName + "'"));
    }

    /** JSONB has no H2 equivalent (H2 has JSON); every other {@link SqlTypeSupport#sqlType} result is
     *  already identical on H2 and Postgres (confirmed by the schema engine's own {@code renderType}
     *  and {@code addBackfillAndTightenColumn} precedents). */
    private static String portableSqlType(CompiledField field) {
        String sqlType = SqlTypeSupport.sqlType(field);
        return "JSONB".equalsIgnoreCase(sqlType) ? "JSON" : sqlType;
    }

    private static String splitExpression(String fromCol, String take) {
        return switch (take) {
            case "before-first-space" -> "SUBSTRING(" + fromCol + " FROM 1 FOR POSITION(' ' IN " + fromCol + ") - 1)";
            case "after-first-space" -> "SUBSTRING(" + fromCol + " FROM POSITION(' ' IN " + fromCol + ") + 1)";
            default -> throw new IllegalStateException("unrecognized split take '" + take + "'");
        };
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
