package com.finalexec.db;

import com.finalexec.db.schemastate.CurrentColumn;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentTable;
import com.finalexec.db.schemastate.DesiredSchema;
import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiff;
import com.finalexec.db.schemastate.SchemaDiffEngine;
import com.finalexec.db.schemastate.SchemaDiffItem;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-53 (`docs/archive/programme-history/REMAINDER_CLOSURE_PLAN.md` §1.2/§3.2). Root cause traced live: {@code
 * SqlTypeSupport.sqlType} -- the single shared mapper its own class javadoc names as feeding
 * "Generator DDL, bond DDL, and database-definition fingerprints" -- hardcoded every
 * {@code string}/{@code enum} field to {@code VARCHAR(255)}, never consulting {@code
 * CompiledSchema.getMaxLength()}. So the "desired" side of the schema diff never varied with a
 * model's declared maxLength, and a narrowing (or widening) silently vanished before ever reaching
 * {@link SchemaDiffEngine} -- which has always classified a {@code VARCHAR(n) -> VARCHAR(m)} change
 * correctly once given two genuinely different type strings (unchanged by this fix; see {@code
 * SchemaDiffEngineTest#narrowingTypeIsDestructive}).
 *
 * <p>This test drives the REAL pipeline -- {@link SqlTypeSupport#sqlType} builds the manifest's
 * declared type exactly as {@code UserDatabaseDefinitionLoader.mapType}/{@code
 * SchemaRealizationEmitter} do at generation time, then {@link DesiredSchemaFactory#fromManifest}
 * and {@link SchemaDiffEngine#diff} run unmodified -- rather than hand-writing a literal type
 * string, which would bypass the actual bug (that was exactly {@code DesiredSchemaFactoryTest}'s
 * blind spot: it always hand-writes its type strings, so it could never have caught this).
 *
 * <p><b>Both engines, with one pure test:</b> every class in this pipeline ({@code SqlTypeSupport},
 * {@link DesiredSchemaFactory}, {@link SchemaDiffEngine}) is engine-agnostic -- no DataSource, no
 * JDBC, no engine-specific branching anywhere in it. The LIVE side of the comparison (a real
 * column's length, read back via JDBC) is a separate, unmodified code path already independently
 * proven correct for both H2 and Postgres by the pre-existing {@code CurrentSchemaReaderH2Test} /
 * {@code CurrentSchemaReaderPostgresTest} golden tests (both assert a live {@code VARCHAR(120)}
 * column's length round-trips as {@code 120}). Duplicating this test per engine would exercise
 * identical bytecode twice for no additional coverage.
 */
class Reg53MaxLengthSchemaDiffTest {

    @Test
    void narrowingAModelFieldsDeclaredMaxLengthProducesADestructiveNarrowTypeDiffItem() {
        String declaredType = SqlTypeSupport.sqlType(stringField("name", 10));

        DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifestWithNameType(declaredType));
        CurrentSchema current = current(cTable("widgets",
                cCol("id", "UUID", false), cCol("name", "VARCHAR(255)", true)));

        SchemaDiff diff = new SchemaDiffEngine().diff(desired, current);

        SchemaDiffItem nameItem = diff.items().stream()
                .filter(i -> "name".equals(i.column()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "a maxLength narrowing (255 -> 10) must produce a diff item for 'name', "
                                + "not silently vanish. Full diff: " + diff.items()));
        assertEquals(SafetyClass.DESTRUCTIVE_NARROW_TYPE, nameItem.safetyClass());
    }

    @Test
    void unchangedDefaultMaxLengthStillProducesNoDiff() {
        // Regression guard for the overwhelmingly common case: a field with NO declared maxLength
        // must keep resolving to the same VARCHAR(255) it always has, so this fix introduces no
        // spurious diff for every model that never declared a maxLength at all.
        String declaredType = SqlTypeSupport.sqlType(stringField("name", null));
        assertEquals("VARCHAR(255)", declaredType, "no declared maxLength must keep the existing default");

        DesiredSchema desired = DesiredSchemaFactory.fromManifest(manifestWithNameType(declaredType));
        CurrentSchema current = current(cTable("widgets",
                cCol("id", "UUID", false), cCol("name", "VARCHAR(255)", true)));

        assertTrue(new SchemaDiffEngine().diff(desired, current).isEmpty(),
                "an unchanged default-length field must not produce a diff item");
    }

    private static CompiledField stringField(String name, Integer maxLength) {
        CompiledSchema schema = maxLength == null
                ? null
                : new CompiledSchema("string", null, null, null, null, maxLength, null, null, null);
        return new CompiledField(name, "string", "String", false, true, false, List.of(), null, schema);
    }

    private static SchemaLifecycleExecutor.SchemaManifest manifestWithNameType(String nameDeclaredType) {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, "sha256:x",
                List.of(),
                List.of("widgets"),
                Map.of("widgets", List.of("id", "name")),
                Map.of("widgets", List.of()),
                Map.of("widgets", Map.of("id", "UUID", "name", nameDeclaredType)),
                Map.of(),
                Map.of(),
                false, "KeepExistingIfCompatible", "NpdevOwnedTablesOnly", "", "",
                // Only "id" is required -- "name" stays nullable on both sides so the ONLY dimension
                // under test is the type-string change; a nullability mismatch here would produce an
                // unrelated NEEDS_HOOK item and mask (or spuriously satisfy) the assertion below.
                Map.of("widgets", List.of("id")),
                Map.of(),
                Map.of(),
                Map.of()
        );
    }

    private static CurrentSchema current(CurrentTable... tables) {
        Map<String, CurrentTable> map = new LinkedHashMap<>();
        for (CurrentTable t : tables) {
            map.put(t.name(), t);
        }
        return new CurrentSchema(map);
    }

    private static CurrentTable cTable(String name, CurrentColumn... cols) {
        Map<String, CurrentColumn> map = new LinkedHashMap<>();
        for (CurrentColumn c : cols) {
            map.put(c.name(), c);
        }
        return new CurrentTable(name, map, List.of("id"), List.of(), List.of(), List.of());
    }

    private static CurrentColumn cCol(String name, String type, boolean nullable) {
        return new CurrentColumn(name, type, null, null, nullable, null);
    }
}
