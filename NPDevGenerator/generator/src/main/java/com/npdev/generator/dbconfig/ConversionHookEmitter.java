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
import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialect;
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

    /**
     * The engine this app is generated for, or {@code null} for the legacy engine-unaware callers.
     *
     * <p>W1.3 closed the "this emitter has no DatabaseEngine to ask" gap by threading it in from
     * {@code GeneratorFacade}, which already holds the {@link GeneratedDatabasePlan}. Nullable rather
     * than required so the existing no-arg constructor keeps working for tests that emit hooks with
     * no plan in scope -- those keep the previous H2 behaviour exactly.
     */
    private final DatabaseEngine engine;

    public ConversionHookEmitter() {
        this(null);
    }

    /** The engine-aware form. Prefer this: a hook's column type is engine-bound. */
    public ConversionHookEmitter(DatabaseEngine engine) {
        this.engine = engine;
    }

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
                statements.add(guardedAddColumn(table, toCol,
                        "ALTER TABLE " + table + " ADD COLUMN " + toCol + " " + portableSqlType(toField)));
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
                    statements.add(guardedAddColumn(table, targetCol,
                            "ALTER TABLE " + table + " ADD COLUMN " + targetCol + " " + portableSqlType(targetField)));
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
                statements.add(guardedAddColumn(table, setCol,
                        "ALTER TABLE " + table + " ADD COLUMN " + setCol + " " + portableSqlType(setField)));
                statements.add("UPDATE " + table + " SET " + setCol + " = (SELECT m." + matchIdCol
                        + " FROM " + matchTable + " m WHERE m." + onCol + " = " + table + "." + equalsCol
                        + ") WHERE " + setCol + " IS NULL");
                statements.add("ALTER TABLE " + table + " ALTER COLUMN " + setCol + " SET NOT NULL");
                claims.add("ADD_REQUIRED_COLUMN:" + table + ":" + setCol);
                verifyNullChecks.add(setCol);
            }
            // S8 W1.2 (roadmap deferred item #4): merge is split's inverse -- N source columns
            // concatenated (via the portable CONCAT(...) function, NOT ||, so a NULL source argument
            // yields "" rather than propagating NULL through the whole expression -- which is exactly
            // why the WHERE guard below separately requires every source column non-null: a merge
            // that silently dropped a missing field into the middle of a name would be the "partial
            // conversion" B12's discipline forbids, so a row missing any source field is left NULL and
            // the closing SET NOT NULL fails the boot on it, same as every other op here) into one new
            // column, with an author-declared separator between each pair.
            case "merge" -> {
                CompiledField toField = requireField(concept, conversion.to());
                String toCol = SqlIdentifierSupport.columnName(toField);
                List<String> mergeCols = new ArrayList<>();
                for (String mergeField : conversion.mergeFrom()) {
                    mergeCols.add(SqlIdentifierSupport.columnName(requireField(concept, mergeField)));
                }
                String separator = conversion.with() == null ? "" : conversion.with();
                String separatorLiteral = "'" + separator.replace("'", "''") + "'";
                StringBuilder concatArgs = new StringBuilder();
                StringBuilder nullGuard = new StringBuilder();
                for (int i = 0; i < mergeCols.size(); i++) {
                    if (i > 0) {
                        concatArgs.append(", ").append(separatorLiteral).append(", ");
                        nullGuard.append(" AND ");
                    }
                    concatArgs.append(mergeCols.get(i));
                    nullGuard.append(mergeCols.get(i)).append(" IS NOT NULL");
                }
                statements.add(guardedAddColumn(table, toCol,
                        "ALTER TABLE " + table + " ADD COLUMN " + toCol + " " + portableSqlType(toField)));
                statements.add("UPDATE " + table + " SET " + toCol + " = CONCAT(" + concatArgs
                        + ") WHERE " + toCol + " IS NULL AND " + nullGuard);
                statements.add("ALTER TABLE " + table + " ALTER COLUMN " + toCol + " SET NOT NULL");
                claims.add("ADD_REQUIRED_COLUMN:" + table + ":" + toCol);
                verifyNullChecks.add(toCol);
            }
            // S8 W1.2: convert is copy with an explicit CAST to 'to's own declared type instead of a
            // bare assignment -- a source value the target type cannot represent fails the CAST itself
            // (a real SQLException, caught by ConversionHookRunner#executeAndVerify, which rolls back
            // the WHOLE hook transaction and refuses the boot) rather than leaving a partially-typed
            // column, exactly the "fail the whole conversion loudly" behavior B13's own spec's honest
            // decision calls for.
            case "convert" -> {
                CompiledField toField = requireField(concept, conversion.to());
                String toCol = SqlIdentifierSupport.columnName(toField);
                String toSqlType = portableSqlType(toField);
                String fromCol = SqlIdentifierSupport.columnName(requireField(concept, conversion.from()));
                statements.add(guardedAddColumn(table, toCol,
                        "ALTER TABLE " + table + " ADD COLUMN " + toCol + " " + toSqlType));
                statements.add("UPDATE " + table + " SET " + toCol + " = CAST(" + fromCol + " AS " + toSqlType
                        + ") WHERE " + toCol + " IS NULL");
                statements.add("ALTER TABLE " + table + " ALTER COLUMN " + toCol + " SET NOT NULL");
                claims.add("ADD_REQUIRED_COLUMN:" + table + ":" + toCol);
                verifyNullChecks.add(toCol);
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

    /**
     * The column type for a hook's {@code ADD COLUMN}, <b>asked of the engine this app is actually
     * generated for</b> (storage/FULL_SUPPORT_PLAN.md W1.3).
     *
     * <h2>What this used to be, and why it stopped being safe</h2>
     *
     * <p>It used to return {@code H2Dialect.INSTANCE.portableColumnType(...)} unconditionally -- the
     * H2/Postgres COMMON form -- because this emitter had no {@link DatabaseEngine} to ask. That was
     * defensible while those were the only two engines: one hook artifact, valid on either, produced
     * by asking the narrower.
     *
     * <p><b>A third engine ends that.</b> MySQL narrows types H2 does not (no native {@code UUID}),
     * so the "common form" would have emitted a type MySQL cannot create -- and it would have done so
     * in a conversion hook, which runs during a migration, on data. The plan's instruction was to
     * thread the engine through exactly as {@code SchemaRealizationEmitter} already does, or, if that
     * were structurally impossible, to add a generation-time refusal rather than keep the
     * common-denominator type. It was not impossible: {@code GeneratorFacade} already holds the
     * {@link GeneratedDatabasePlan} at the call site, two lines above.
     *
     * <p>{@code IN_MEMORY} has no SQL at all, so a hook has nothing to emit against; the H2 form is
     * kept for that case only, which preserves the behaviour of every legacy caller that passes no
     * plan (the two-argument {@code emit} overload, used by tests).
     */
    /**
     * A hook's {@code ADD COLUMN} in the form the target engine can run -- ledger STOR-5.
     *
     * <p>A conversion hook's convert SQL runs during a migration, on data, on whichever engine the
     * app was generated for. Writing {@code ADD COLUMN IF NOT EXISTS} inline made every hook
     * unrunnable on MySQL and SQL Server, and hid behind a corrupted regex in check-dialect-sites.py
     * (a literal backspace where a word boundary was meant) until that was fixed.
     */
    private String guardedAddColumn(String table, String column, String alterStatement) {
        SqlDialect dialect = engine != null && engine.jdbc() ? engine.dialect() : H2Dialect.INSTANCE;
        return dialect.guardedAddColumn(table, column, alterStatement);
    }

    private String portableSqlType(CompiledField field) {
        SqlDialect dialect = engine != null && engine.jdbc() ? engine.dialect() : H2Dialect.INSTANCE;
        return dialect.portableColumnType(SqlTypeSupport.sqlType(field));
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
