package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** LIFT-UNIQUE-P2: compound (multi-field) unique invariants emit a composite UNIQUE constraint. */
final class SchemaRealizationEmitterCompoundUniqueTest {

    @TempDir
    Path tempDir;

    @Test
    void compoundUniqueEmitsTenantScopedCompositeConstraint() throws Exception {
        CompiledConcept membership = new CompiledConcept(
                "Membership", "Membership", "memberships",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("orgId", "string", "String", false, true, false),
                        new CompiledField("email", "string", "String", false, true, false)
                ),
                List.of(),
                List.of(new CompiledInvariant(
                        "unique(orgId,email)", "unique", "orgId", null, List.of("orgId", "email")
                ))
        );
        CompiledModel model = new CompiledModel("test", "1.0.0", "1.0.0", Map.of(membership.getName(), membership));

        Path outRoot = tempDir.resolve("app");
        new SchemaRealizationEmitter().emit(model, outRoot, plan(), tempDir.resolve("model.json"));

        Path schemaDir = outRoot.resolve("src/main/resources/db/schema-realization");
        String v1Sql = Files.readString(schemaDir.resolve("V1__npdev_schema_realization.sql"));
        assertTrue(
                v1Sql.contains("ADD CONSTRAINT uq_memberships_org_id_email UNIQUE (tenant_id, org_id, email)"),
                v1Sql
        );
    }

    private GeneratedDatabasePlan plan() {
        return new GeneratedDatabasePlan(
                "compound-unique-test",
                DatabaseEngine.H2_LOCAL,
                DatabaseEngine.H2_LOCAL.storageMode(),
                true,
                "compound-unique-test",
                "compound-unique-test",
                "test",
                tempDir.resolve("data").toString(),
                "db-instance",
                "",
                "",
                0,
                0,
                "jdbc:h2:mem:compound-unique-test",
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
