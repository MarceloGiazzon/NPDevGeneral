package com.npdev.generator.dbconfig;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledGroupByField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.kernel.storage.sql.PaginationClause;
import com.npdev.kernel.storage.sql.PostgresDialect;
import com.npdev.kernel.storage.sql.ReturningStrategy;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlType;
import com.npdev.kernel.storage.sql.StorageCapability;
import com.npdev.kernel.storage.sql.UpsertStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S3's exit condition: <b>a capability-violating model is refused at GENERATION time</b> -- not at
 * boot, not at first query. Refusing late is the same defect as not refusing.
 *
 * <p>The violating engine here is a deliberately crippled test dialect rather than a real one,
 * because every registered SQL engine currently supports everything -- which is the point of
 * building this before the second engine lands, and is also why the gate needs a test that does not
 * wait for one.
 */
@DisplayName("S3: the generator refuses a model the engine cannot honour")
class StorageCapabilityGateTest {

    // ------------------------------------------------------------------ the crippled engine

    /** Supports the bare minimum; everything interesting is absent. Mirrors what a document store
     *  would look like on day one, without pretending to be one. */
    private static SqlDialect dialectWithout(StorageCapability... absent) {
        Set<StorageCapability> capabilities = new java.util.LinkedHashSet<>(
                List.of(StorageCapability.values()));
        List.of(absent).forEach(capabilities::remove);
        return new SqlDialect() {
            @Override public String name() {
                return "limited-test-engine";
            }
            @Override public Set<StorageCapability> capabilities() {
                return capabilities;
            }
            // Nothing below is exercised by this gate; each throws rather than returning a plausible
            // Postgres answer, so a future test that DOES reach one fails loudly instead of passing
            // on a borrowed result.
            @Override public String quoteIdentifier(String raw) {
                throw new UnsupportedOperationException();
            }
            @Override public String keyableTextColumnType() {
                throw new UnsupportedOperationException();
            }
            @Override public String defaultableTextColumnType() {
                throw new UnsupportedOperationException();
            }
            @Override public String selectForUpdate(String columns, String table, String whereClause) {
                throw new UnsupportedOperationException();
            }
            @Override public String renameColumn(String table, String from, String to) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean isUniqueViolation(java.sql.SQLException failure) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean isReservedIdentifier(String rawIdentifier) {
                throw new UnsupportedOperationException();
            }
            @Override public String guardedCreateTable(String table, String statement) {
                throw new UnsupportedOperationException();
            }
            @Override public String guardedCreateIndex(String index, String table, String statement) {
                throw new UnsupportedOperationException();
            }
            @Override public String guardedAddColumn(String table, String column, String statement) {
                throw new UnsupportedOperationException();
            }
            @Override public boolean foldsUnquotedIdentifiersToLowerCase() {
                throw new UnsupportedOperationException();
            }
            @Override public String autoIncrementColumn(SqlType type) {
                throw new UnsupportedOperationException();
            }
            @Override public String jsonColumnType() {
                throw new UnsupportedOperationException();
            }
            @Override public boolean isJsonColumnType(String name) {
                throw new UnsupportedOperationException();
            }
            @Override public String timestampColumnType() {
                throw new UnsupportedOperationException();
            }
            @Override public String portableColumnType(String declared) {
                throw new UnsupportedOperationException();
            }
            @Override public PaginationClause limitOffset() {
                throw new UnsupportedOperationException();
            }
            @Override public PaginationClause limitOnly() {
                throw new UnsupportedOperationException();
            }
            @Override public String rowLimit(long rows) {
                throw new UnsupportedOperationException();
            }
            @Override public UpsertStrategy upsert() {
                throw new UnsupportedOperationException();
            }
            @Override public ReturningStrategy returning() {
                throw new UnsupportedOperationException();
            }
            @Override public String cast(String expression, SqlType type) {
                throw new UnsupportedOperationException();
            }
            @Override public Set<String> systemSchemas() {
                throw new UnsupportedOperationException();
            }
            @Override public String listTablesSql() {
                throw new UnsupportedOperationException();
            }
            @Override public String listColumnsSql() {
                throw new UnsupportedOperationException();
            }
            @Override public String listIndexesSql() {
                throw new UnsupportedOperationException();
            }
            @Override public String constraintExistsSql() {
                throw new UnsupportedOperationException();
            }
            @Override public String tableExistsInCurrentSchemaSql(String table) {
                throw new UnsupportedOperationException();
            }
            @Override public String guardedConstraintDdl(String constraint, String table, String ddl) {
                throw new UnsupportedOperationException();
            }
        };
    }

    // ------------------------------------------------------------------ models

    private static CompiledModel modelWithJoinQuery() {
        CompiledConcept order = new CompiledConcept("Order", "Order", "orders", List.of(
                field("id", true, false, null),
                field("customer", false, false, "Customer")));
        CompiledQuery summary = new CompiledQuery(
                "orderSummary", "Order", null, List.of(), null, List.of(), List.of(), null, null,
                java.util.Map.of(),
                List.of(new CompiledGroupByField("customer.region", null)),
                List.of(), null);
        return model(java.util.Map.of("Order", order), List.of(summary));
    }

    private static CompiledModel modelWithUniqueField() {
        CompiledConcept customer = new CompiledConcept("Customer", "Customer", "customers", List.of(
                field("id", true, false, null),
                field("email", false, true, null)));
        return model(java.util.Map.of("Customer", customer), List.of());
    }

    private static CompiledModel model(java.util.Map<String, CompiledConcept> concepts,
                                       List<CompiledQuery> queries) {
        return new CompiledModel("test", "2.0", "1.0.0", concepts, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), queries, List.of(), List.of(), List.of());
    }

    private static CompiledField field(String name, boolean id, boolean unique, String reference) {
        return new CompiledField(name, "text", "String", id, false, unique, List.of(), reference, null);
    }

    // ------------------------------------------------------------------ the exit condition

    @Test
    @DisplayName("a join on an engine with no server-side join is REFUSED, naming the query")
    void refusesServerSideJoin() {
        SqlDialect limited = dialectWithout(StorageCapability.SERVER_SIDE_JOIN);

        StorageCapabilityGate.UnsupportedStorageEngineException refusal =
                assertThrows(StorageCapabilityGate.UnsupportedStorageEngineException.class,
                        () -> StorageCapabilityGate.verifyAgainst(modelWithJoinQuery(), limited, "LimitedTest"));

        String message = refusal.getMessage();
        // The engine, the capability, and -- the part that makes it actionable -- the model element.
        assertTrue(message.contains("LimitedTest"), message);
        assertTrue(message.contains("SERVER_SIDE_JOIN"), message);
        assertTrue(message.contains("orderSummary"), message);
        assertTrue(message.contains("customer.region"), message);
        // And both ways out.
        assertTrue(message.contains("use an engine that does"), message);
        assertTrue(message.contains("postgres"), message);
    }

    @Test
    @DisplayName("a unique field on an engine with no unique constraints is REFUSED, naming the field")
    void refusesUniqueConstraint() {
        SqlDialect limited = dialectWithout(StorageCapability.UNIQUE_CONSTRAINTS);

        StorageCapabilityGate.UnsupportedStorageEngineException refusal =
                assertThrows(StorageCapabilityGate.UnsupportedStorageEngineException.class,
                        () -> StorageCapabilityGate.verifyAgainst(modelWithUniqueField(), limited, "LimitedTest"));

        assertTrue(refusal.getMessage().contains("Customer.email"), refusal.getMessage());
        assertEquals(Set.of(StorageCapability.UNIQUE_CONSTRAINTS), refusal.unmet().keySet());
    }

    @Test
    @DisplayName("EVERY unmet capability is reported at once, not the first one")
    void reportsAllUnmetCapabilitiesTogether() {
        // An author who must regenerate five times to discover five problems will reasonably
        // conclude the tool is guessing.
        SqlDialect limited = dialectWithout(
                StorageCapability.SERVER_SIDE_JOIN,
                StorageCapability.AGGREGATION_PIPELINE,
                StorageCapability.FOREIGN_KEYS);

        StorageCapabilityGate.UnsupportedStorageEngineException refusal =
                assertThrows(StorageCapabilityGate.UnsupportedStorageEngineException.class,
                        () -> StorageCapabilityGate.verifyAgainst(modelWithJoinQuery(), limited, "LimitedTest"));

        assertEquals(
                Set.of(StorageCapability.SERVER_SIDE_JOIN,
                        StorageCapability.AGGREGATION_PIPELINE,
                        StorageCapability.FOREIGN_KEYS),
                refusal.unmet().keySet());
    }

    @Test
    @DisplayName("the same model passes on a real engine that supports it -- the gate is not a blanket no")
    void acceptsWhatPostgresSupports() {
        assertDoesNotThrow(() -> StorageCapabilityGate.verifyAgainst(
                modelWithJoinQuery(), PostgresDialect.INSTANCE, "Postgres"));
        assertDoesNotThrow(() -> StorageCapabilityGate.verifyAgainst(
                modelWithUniqueField(), PostgresDialect.INSTANCE, "Postgres"));
    }

    @Test
    @DisplayName("a capability nothing needs is not required -- requirements come from the model")
    void requirementsAreDerivedFromTheModel() {
        // No snapshot/restore anywhere in the model, so an engine without it is fine.
        assertDoesNotThrow(() -> StorageCapabilityGate.verifyAgainst(
                modelWithUniqueField(), dialectWithout(StorageCapability.SNAPSHOT_RESTORE), "LimitedTest"));

        List<StorageCapability> needed = StorageCapabilityGate.requirementsOf(modelWithUniqueField())
                .stream().map(StorageCapabilityGate.Requirement::capability).distinct().toList();
        assertTrue(needed.contains(StorageCapability.UNIQUE_CONSTRAINTS), needed.toString());
        assertTrue(!needed.contains(StorageCapability.SNAPSHOT_RESTORE), needed.toString());
    }

    @Test
    @DisplayName("the InMemory engine is skipped explicitly, not by accident")
    void inMemoryHasNoDialectAndIsNotChecked() {
        // DatabaseEngine.IN_MEMORY.dialect() throws rather than handing back a Postgres answer that
        // would be wrong in every particular; the gate asks jdbc() first.
        assertThrows(IllegalStateException.class, DatabaseEngine.IN_MEMORY::dialect);
        assertDoesNotThrow(() -> StorageCapabilityGate.verify(modelWithJoinQuery(), null));
    }

    @Test
    @DisplayName("the capability matrix is generated from the code, so it cannot drift from it")
    void capabilityMatrixReflectsTheDialects() {
        String matrix = StorageCapabilityGate.capabilityMatrix();
        assertTrue(matrix.contains("postgres"), matrix);
        assertTrue(matrix.contains("h2"), matrix);
        // H2 does not roll DDL back (boundary B11) and Postgres does -- if the matrix ever shows
        // both the same for this row, one of the dialects is lying about itself.
        assertTrue(matrix.contains("DDL_IN_TRANSACTION"), matrix);
        String ddlRow = matrix.lines()
                .filter(line -> line.startsWith("DDL_IN_TRANSACTION"))
                .findFirst().orElseThrow();
        assertTrue(ddlRow.contains("yes") && ddlRow.contains("NO"), ddlRow);
    }
}
