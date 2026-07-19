package com.npdev.generator.schemaevolution;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.dsl.v1.schemaevolution.SchemaDeltaItem;
import com.npdev.generator.dbconfig.DatabaseEngine;
import com.npdev.generator.dbconfig.GeneratedDatabasePlan;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import com.npdev.generator.dbconfig.SchemaLifecycleStrategy;
import com.npdev.generator.dbconfig.UserDatabaseDefinition;
import com.npdev.generator.dbconfig.UserDatabaseDefinitionLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 6 (task 6.4). Unit coverage for {@link MigrationPlanEmitter} over model-pairs
 * covering every item type the plan's task 6.4 text enumerates. Pure -- no I/O, no CLI, no
 * generated app -- these tests construct {@link CompiledModel} pairs directly and call
 * {@link MigrationPlanEmitter#compute} (see {@link com.npdev.generator.GeneratorMainMigrationPlanCliTest}
 * for the CLI-hook-level coverage of the same logic via a real {@code GeneratorMain.main(...)} run).
 *
 * <p>The destructive-item scenarios ({@code DROP_COLUMN}/{@code DROP_TABLE}/{@code NARROW_TYPE})
 * each independently recompute the expected {@link DestructiveAckToken} by constructing the exact
 * same {@link SchemaDeltaItem} record {@code com.finalexec.db.SchemaDeltaReport} would build for the
 * identical underlying change, and asserts the plan's own token matches -- proving task 6.1's (A)
 * "share, not duplicate" decision holds with a real test, not just a docstring claim (see
 * {@link MigrationPlanEmitter}'s class javadoc for the full design-decision reasoning).
 */
class MigrationPlanEmitterTest {

    @TempDir
    Path tempDir;

    @Test
    void additiveOptionalFieldIsAddColumnNotDestructive() {
        CompiledModel oldModel = model(concept("Widget", "", id(), field("name", "string", false, false)));
        CompiledModel newModel = model(concept("Widget", "",
                id(), field("name", "string", false, false), field("description", "string", false, false)));

        MigrationPlan plan = MigrationPlanEmitter.compute(newModel, oldModel, plan("t1"));

        assertFalse(plan.freshInstall());
        assertEquals(1, plan.items().size());
        PlanItem item = plan.items().get(0);
        assertEquals(PlanItem.Kind.ADD_COLUMN, item.kind());
        assertEquals("widgets", item.table());
        assertEquals("description", item.column());
        assertFalse(item.destructive());
        assertNull(item.stableString());
        assertNull(plan.destructiveAckToken(), "no destructive items -- no ack token");
    }

    @Test
    void requiredFieldWithLiteralDefaultIsAddColumnBackfillNotDestructive() {
        CompiledSchema literalDefaultSchema = new CompiledSchema(
                null, Map.of(), null, List.of(), List.of(), 1, "description",
                null, null, null, null, null);
        CompiledField priority = new CompiledField(
                "priority", "int", "java.lang.Integer", false, true, false, List.of(), null, literalDefaultSchema);

        CompiledModel oldModel = model(concept("Widget", "", id(), field("name", "string", false, false)));
        CompiledModel newModel = model(concept("Widget", "", id(), field("name", "string", false, false), priority));

        MigrationPlan plan = MigrationPlanEmitter.compute(newModel, oldModel, plan("t2"));

        assertEquals(1, plan.items().size());
        PlanItem item = plan.items().get(0);
        assertEquals(PlanItem.Kind.ADD_COLUMN_BACKFILL, item.kind());
        assertEquals("widgets", item.table());
        assertEquals("priority", item.column());
        assertFalse(item.destructive(), "a literal-default backfill is safe, not destructive (LNCH-1 Phase 5)");
        assertTrue(item.description().contains("1"), "expected the literal default value surfaced in the description: " + item.description());
        assertNull(plan.destructiveAckToken());
    }

    @Test
    void removedFieldIsDropColumnDestructiveAndTokenMatchesTheSharedVocabularyIndependently() {
        CompiledModel oldModel = model(concept("Widget", "",
                id(), field("name", "string", false, false), field("legacyFlag", "boolean", false, false)));
        CompiledModel newModel = model(concept("Widget", "",
                id(), field("name", "string", false, false)));

        GeneratedDatabasePlan dbPlan = plan("sha256:drop-column-to-fingerprint");
        MigrationPlan plan = MigrationPlanEmitter.compute(newModel, oldModel, dbPlan);

        assertEquals(1, plan.items().size());
        PlanItem item = plan.items().get(0);
        assertEquals(PlanItem.Kind.DROP_COLUMN, item.kind());
        assertEquals("widgets", item.table());
        assertEquals("legacy_flag", item.column());
        assertTrue(item.destructive());
        assertEquals("DROP_COLUMN:widgets:legacy_flag:BOOLEAN", item.stableString());

        // Independent parity check (task 6.4): construct the SAME SchemaDeltaItem a live-DB-driven
        // com.finalexec.db.SchemaDeltaReport would build for this exact change, and confirm the
        // token matches -- proving byte-identical construction, not just an equal-looking string.
        SchemaDeltaItem.DropColumn independentlyConstructed = new SchemaDeltaItem.DropColumn("widgets", "legacy_flag", "BOOLEAN");
        assertEquals(independentlyConstructed.stableString(), item.stableString());
        String expectedToken = DestructiveAckToken.compute(dbPlan.schemaFingerprint(), List.of(independentlyConstructed.stableString()));
        assertEquals(expectedToken, plan.destructiveAckToken());
    }

    @Test
    void removedConceptIsDropTableDestructiveAndTokenMatchesTheSharedVocabularyIndependently() {
        CompiledModel oldModel = model(
                concept("Widget", "", id()),
                concept("Gadget", "", id()));
        CompiledModel newModel = model(concept("Widget", "", id()));

        GeneratedDatabasePlan dbPlan = plan("sha256:drop-table-to-fingerprint");
        MigrationPlan plan = MigrationPlanEmitter.compute(newModel, oldModel, dbPlan);

        assertEquals(1, plan.items().size());
        PlanItem item = plan.items().get(0);
        assertEquals(PlanItem.Kind.DROP_TABLE, item.kind());
        assertEquals("gadgets", item.table());
        assertTrue(item.destructive());
        assertEquals("DROP_TABLE:gadgets", item.stableString(),
                "R1 (F2): the row count is display metadata only and is OUT of the hashed stable "
                        + "string, so a concept-drop token computed here byte-matches the executor's "
                        + "at boot regardless of the live row count");

        // Independent parity check: the row count no longer participates in the stable string, so
        // this token is now byte-identical to what a REAL boot with any actual row count computes
        // (the whole point of R1 / F2 -- concept-drop acknowledgment is finally plan-computable).
        SchemaDeltaItem.DropTable independentlyConstructed = new SchemaDeltaItem.DropTable("gadgets", -1L);
        String expectedToken = DestructiveAckToken.compute(dbPlan.schemaFingerprint(), List.of(independentlyConstructed.stableString()));
        assertEquals(expectedToken, plan.destructiveAckToken());
    }

    @Test
    void renamedFieldIsRenameColumnAndResolvesToNothingDestructive() {
        CompiledModel oldModel = model(concept("Widget", "", id(), field("name", "string", false, false)));
        CompiledField fullName = new CompiledField(
                "fullName", "string", "java.lang.String", false, false, false,
                List.of(), null, null, null, null, List.of(), null, null, "name");
        CompiledModel newModel = model(concept("Widget", "", id(), fullName));

        MigrationPlan plan = MigrationPlanEmitter.compute(newModel, oldModel, plan("t5"));

        assertEquals(1, plan.items().size());
        PlanItem item = plan.items().get(0);
        assertEquals(PlanItem.Kind.RENAME_COLUMN, item.kind());
        assertEquals("widgets", item.table());
        assertEquals("full_name", item.column());
        assertEquals("name", item.renamedFrom());
        assertFalse(item.destructive(), "a resolved rename is safe, not destructive");
        assertNull(plan.destructiveAckToken());
    }

    @Test
    void renamedConceptIsRenameTableAndResolvesToNothingDestructive() {
        CompiledModel oldModel = model(concept("User", "", id()));
        CompiledModel newModel = model(renamedConcept("Account", "User"));

        MigrationPlan plan = MigrationPlanEmitter.compute(newModel, oldModel, plan("t6"));

        assertEquals(1, plan.items().size());
        PlanItem item = plan.items().get(0);
        assertEquals(PlanItem.Kind.RENAME_TABLE, item.kind());
        assertEquals("accounts", item.table());
        assertEquals("users", item.renamedFrom());
        assertFalse(item.destructive());
        assertNull(plan.destructiveAckToken());
    }

    @Test
    void widenedTypeIsWidenTypeNotDestructive() {
        CompiledModel oldModel = model(concept("Widget", "", id(), field("loginCount", "int", false, false)));
        CompiledModel newModel = model(concept("Widget", "", id(), field("loginCount", "long", false, false)));

        MigrationPlan plan = MigrationPlanEmitter.compute(newModel, oldModel, plan("t7"));

        assertEquals(1, plan.items().size());
        PlanItem item = plan.items().get(0);
        assertEquals(PlanItem.Kind.WIDEN_TYPE, item.kind());
        assertEquals("widgets", item.table());
        assertEquals("login_count", item.column());
        assertEquals("INTEGER", item.fromType());
        assertEquals("BIGINT", item.toType());
        assertFalse(item.destructive());
        assertNull(plan.destructiveAckToken());
    }

    @Test
    void narrowedTypeIsNarrowTypeDestructiveAndTokenMatchesTheSharedVocabularyIndependently() {
        CompiledModel oldModel = model(concept("Widget", "", id(), field("loginCount", "long", false, false)));
        CompiledModel newModel = model(concept("Widget", "", id(), field("loginCount", "int", false, false)));

        GeneratedDatabasePlan dbPlan = plan("sha256:narrow-type-to-fingerprint");
        MigrationPlan plan = MigrationPlanEmitter.compute(newModel, oldModel, dbPlan);

        assertEquals(1, plan.items().size());
        PlanItem item = plan.items().get(0);
        assertEquals(PlanItem.Kind.NARROW_TYPE, item.kind());
        assertEquals("widgets", item.table());
        assertEquals("login_count", item.column());
        assertEquals("BIGINT", item.fromType());
        assertEquals("INTEGER", item.toType());
        assertTrue(item.destructive());
        assertEquals("NARROW_TYPE:widgets:login_count:BIGINT:INTEGER", item.stableString());

        SchemaDeltaItem.NarrowType independentlyConstructed =
                new SchemaDeltaItem.NarrowType("widgets", "login_count", "BIGINT", "INTEGER");
        String expectedToken = DestructiveAckToken.compute(dbPlan.schemaFingerprint(), List.of(independentlyConstructed.stableString()));
        assertEquals(expectedToken, plan.destructiveAckToken());
    }

    @Test
    void newUniqueConstraintIsAddUniqueConstraintNotDestructiveButFlaggedForPreCheck() {
        CompiledModel oldModel = model(concept("Widget", "", id(), field("email", "string", false, false)));
        CompiledModel newModel = model(concept("Widget", "", id(), field("email", "string", false, true)));

        MigrationPlan plan = MigrationPlanEmitter.compute(newModel, oldModel, plan("t9"));

        assertEquals(1, plan.items().size());
        PlanItem item = plan.items().get(0);
        assertEquals(PlanItem.Kind.ADD_UNIQUE_CONSTRAINT, item.kind());
        assertEquals("widgets", item.table());
        assertEquals(List.of("email"), item.constraintColumns());
        assertFalse(item.destructive(), "a new unique constraint is not destructive -- it needs a data pre-check, not an acknowledgment");
        assertTrue(item.description().toLowerCase(Locale.ROOT).contains("pre-check"),
                "expected the plan to flag this as needing a data pre-check: " + item.description());
        assertNull(plan.destructiveAckToken());
    }

    @Test
    void freshInstallHasEmptyItemsNullFromFingerprintAndNoAckToken() {
        CompiledModel newModel = model(concept("Widget", "", id(), field("name", "string", false, false)));

        MigrationPlan plan = MigrationPlanEmitter.compute(newModel, null, plan("t10"));

        assertTrue(plan.freshInstall());
        assertNull(plan.fromFingerprint());
        assertEquals("t10", plan.toFingerprint());
        assertTrue(plan.items().isEmpty());
        assertNull(plan.destructiveAckToken());
    }

    @Test
    void noChangeSameModelTwiceProducesEmptyItemsMatchingFingerprintsAndNoAckToken() {
        CompiledModel model = model(concept("Widget", "", id(), field("name", "string", false, false)));
        UserDatabaseDefinition definition = new UserDatabaseDefinition(
                DatabaseEngine.H2_LOCAL, "", 0, "", "", "", "", "", "",
                true, true,
                new SchemaLifecyclePolicy(SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE, false, "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE));
        // The REAL production fingerprint for this exact model+definition -- not an arbitrary
        // placeholder -- because this test specifically asserts fromFingerprint == toFingerprint,
        // which is only meaningful when toFingerprint is the value the SAME algorithm would produce.
        String realFingerprint = UserDatabaseDefinitionLoader.computeSchemaFingerprint(definition, model);

        MigrationPlan plan = MigrationPlanEmitter.compute(model, model, planWithFingerprint(realFingerprint));

        assertFalse(plan.freshInstall(), "a previous model WAS supplied -- this is 'no changes since last build', a DIFFERENT state from fresh install");
        assertEquals(realFingerprint, plan.fromFingerprint());
        assertEquals(realFingerprint, plan.toFingerprint());
        assertTrue(plan.items().isEmpty());
        assertNull(plan.destructiveAckToken(), "nothing to acknowledge -- tokens 'match' trivially since there is nothing destructive");
    }

    private static CompiledModel model(CompiledConcept... concepts) {
        Map<String, CompiledConcept> byName = new java.util.LinkedHashMap<>();
        for (CompiledConcept concept : concepts) {
            byName.put(concept.getName(), concept);
        }
        return new CompiledModel("test", "1.0.0", "1.0.0", byName);
    }

    private static CompiledConcept concept(String name, String tableNameOverride, CompiledField... fields) {
        return new CompiledConcept(name, name, tableNameOverride, List.of(fields));
    }

    private static CompiledConcept renamedConcept(String name, String renamedFrom, CompiledField... fields) {
        return new CompiledConcept(
                name, name, "",
                List.of(fields.length == 0 ? new CompiledField[]{id()} : fields),
                List.<String>of(),
                List.<com.npdev.dsl.v1.compiled.CompiledInvariant>of(),
                (com.npdev.dsl.v1.compiled.CompiledLifecycle) null,
                (com.npdev.dsl.v1.compiled.CompiledPresentationMetadata) null,
                (String) null,
                (String) null,
                List.<com.npdev.dsl.v1.compiled.CompiledIndex>of(),
                (com.npdev.dsl.v1.compiled.CompiledConceptAccess) null,
                renamedFrom
        );
    }

    private static CompiledField id() {
        return new CompiledField("id", "uuid", "java.util.UUID", true, true, false);
    }

    private static CompiledField field(String name, String dslType, boolean required, boolean unique) {
        return new CompiledField(name, dslType, javaType(dslType), false, required, unique);
    }

    private static String javaType(String dslType) {
        return switch (dslType) {
            case "int" -> "java.lang.Integer";
            case "long" -> "java.lang.Long";
            case "boolean" -> "java.lang.Boolean";
            default -> "java.lang.String";
        };
    }

    private GeneratedDatabasePlan plan(String schemaFingerprint) {
        return planWithFingerprint(schemaFingerprint);
    }

    private GeneratedDatabasePlan planWithFingerprint(String schemaFingerprint) {
        return new GeneratedDatabasePlan(
                "migration-plan-emitter-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                "migration-plan-emitter-test",
                "migration-plan-emitter-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:migration-plan-emitter-test",
                "org.h2.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                true,
                true,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE,
                        false,
                        "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE
                ),
                schemaFingerprint,
                tempDir.resolve("database.json"),
                List.of("test")
        );
    }
}
