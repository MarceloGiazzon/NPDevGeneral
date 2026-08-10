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

/** LIFT-UPLOAD-P2: a `file`-typed field maps to a JSON handle column, not a blob. */
final class SchemaRealizationEmitterFileFieldTest {

    @TempDir
    Path tempDir;

    @Test
    void fileFieldEmitsAJsonHandleColumn() throws Exception {
        CompiledConcept document = new CompiledConcept(
                "Document", "Document", "documents",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("attachment", "file", "java.util.Map", false, false, false)
                )
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(document.getName(), document));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        Path schemaDir = outRoot.resolve("src/main/resources/db/schema-realization");
        String v1Sql = Files.readString(schemaDir.resolve("V1__npdev_schema_realization.sql"));
        // H2's dialect renders the shared "JSONB" SqlTypeSupport mapping as "JSON" (no JSONB type in H2);
        // either rendering proves the field got a JSON-family handle column, not a blob/varchar.
        assertTrue(v1Sql.contains("attachment JSON"), v1Sql);
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "file-field-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                false, // externallyProvisioned (STOR-14) -- NPDev provisioned this test's database
                "file-field-test",
                "file-field-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:file-field-test",
                "org.h2.Driver",
                "sa",
                "",
                "",
                0,
                "",
                "",
                false,
                true,
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
