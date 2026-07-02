package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Internal tables previously had NO column-evolution path at all -- {@code appendTable()} only
 * ever emits {@code CREATE TABLE IF NOT EXISTS}, a no-op once the table already exists, so a new
 * column added to an internal table definition (e.g. npdev_tenant's persistence_mode) would never
 * reach an already-booted app's database. Proves the fix: every internal table's columns are also
 * emitted into the safe-additive repeatable migration, defaulted columns keep their default, and
 * this doesn't depend on any business concept existing in the model at all.
 */
final class SchemaRealizationEmitterInternalTableAdditiveColumnsTest {

    @TempDir
    Path tempDir;

    @Test
    void internalTableColumnsAppearInTheAdditiveMigrationWithDefaultsPreserved() throws Exception {
        CompiledConcept lonelyConcept = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(new CompiledField("id", "uuid", "java.util.UUID", true, true, false))
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(lonelyConcept.getName(), lonelyConcept));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        Path schemaDir = outRoot.resolve("src/main/resources/db/schema-realization");
        String additiveSql = Files.readString(schemaDir.resolve("R__npdev_schema_additive_columns.sql"));

        assertTrue(additiveSql.contains("ALTER TABLE npdev_tenant ADD COLUMN IF NOT EXISTS tenant_id"), additiveSql);
        assertTrue(additiveSql.contains("ALTER TABLE npdev_tenant ADD COLUMN IF NOT EXISTS status"), additiveSql);
        assertTrue(
                additiveSql.contains("ALTER TABLE npdev_tenant ADD COLUMN IF NOT EXISTS persistence_mode")
                        && additiveSql.contains("DEFAULT 'default'"),
                "expected persistence_mode to carry its declared default:\n" + additiveSql);
        assertTrue(additiveSql.contains("ALTER TABLE npdev_promotion_state ADD COLUMN IF NOT EXISTS event_id"), additiveSql);
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "internal-additive-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                "internal-additive-test",
                "internal-additive-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:internal-additive-test",
                "org.h2.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                true,
                false,
                new SchemaLifecyclePolicy(
                        SchemaLifecycleStrategy.KEEP_EXISTING_IF_COMPATIBLE,
                        false,
                        "",
                        SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE
                ),
                "test-fingerprint",
                tempDir.resolve("database.json"),
                List.of("test")
        );
    }
}
