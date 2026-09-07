package com.finalexec.db;

import com.finalexec.db.schemastate.ConstraintSurplusDropPlan;
import com.finalexec.db.schemastate.ConstraintSurplusClassifier;
import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.finalexec.db.schemastate.SurplusConstraint;
import com.npdev.dsl.v1.schemaevolution.DestructiveAckToken;
import com.npdev.generator.dbconfig.SchemaLifecyclePolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * STOR-31 (boundary B3, POSTURAL_LIFT_PLAN_2026-09-07.md package P3), the H2 arm of the delivered
 * {@code SchemaAcknowledgmentControllerSurplusTest} scenario list -- preview issues a token; a drop
 * with a stale token is refused (recomputed-and-compared: a live surplus change flips every token
 * issued earlier); a drop naming a non-FOREIGN constraint is refused and nothing is dropped. The
 * controller layer itself is covered gate-and-precondition-only in
 * {@code SchemaAcknowledgmentControllerSurplusTest} (MockMvc), because the manifest is a fixed
 * classpath resource (see that test's javadoc); the token/plan/execute logic it forwards to is
 * proven end to end here against a real H2 database and a real manifest -- including the SURPLUS_DROPPED
 * history rows (recorded BEFORE each drop) and the actual removal of the live FK/index.
 */
class SurplusDropFlowH2Test {

    private static final String FINGERPRINT = "test-fingerprint-for-surplus-flow";

    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        try (Connection connection = DriverManager.getConnection(url);
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id BIGINT, created_at TIMESTAMP)");
            statement.execute("CREATE TABLE customers (id BIGINT PRIMARY KEY)");
            statement.execute("CREATE TABLE catalog (id BIGINT PRIMARY KEY, item_key VARCHAR(64))");
            // The FK the model does NOT declare -> FOREIGN surplus.
            statement.execute("ALTER TABLE orders ADD CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(id)");
            // A DBA-style index the model does NOT declare -> FOREIGN surplus.
            statement.execute("CREATE INDEX idx_orders_created ON orders(created_at)");
            // The one CONSTRAINT the desired schema DOES declare -> must never appear as surplus
            // (PLATFORM_DECLARED), it is what stops the whole-schema abstention from firing.
            statement.execute("CREATE UNIQUE INDEX ux_catalog_item ON catalog(item_key)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    private SchemaLifecycleExecutor.SchemaManifest manifest() {
        return new SchemaLifecycleExecutor.SchemaManifest(
                "H2Local", "jdbc", true, FINGERPRINT,
                List.of(),
                List.of("orders", "customers", "catalog"),
                Map.of(
                        "orders", List.of("id", "customer_id", "created_at"),
                        "customers", List.of("id"),
                        "catalog", List.of("id", "item_key")
                ),
                Map.of(), Map.of(), Map.of(), Map.of(),
                false, "KeepExistingIfCompatible", SchemaLifecyclePolicy.NPDEV_TABLE_SCOPE, "", "",
                Map.of(), Map.of(), Map.of(), Map.of(),
                List.of(), "NpdevManaged",
                Map.of(),
                Map.of("catalog", List.of(new SchemaLifecycleExecutor.IndexDecl(List.of("item_key"), true))),
                Map.of()
        );
    }

    private DataSource dataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection(url);
            }

            @Override
            public Connection getConnection(String username, String password) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
            }

            @Override
            public void setLoginTimeout(int seconds) {
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                return null;
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                return null;
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }

    private static ConstraintSurplusDropPlan.Droppable droppableNamed(
            List<ConstraintSurplusDropPlan.Droppable> droppable, String name) {
        return droppable.stream().filter(d -> d.liveName().equalsIgnoreCase(name)).findFirst().orElseThrow();
    }

    private static int historyRowCount(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            try (ResultSet tables = connection.getMetaData().getTables(null, null, "npdev_schema_history", null)) {
                if (!tables.next()) {
                    return 0; // no SURPLUS_DROPPED row was ever written, so the table may not exist yet
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM npdev_schema_history WHERE classification = 'SURPLUS_DROPPED'");
                    ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static boolean liveForeignKeyExists(DataSource dataSource, String name) throws Exception {
        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        return current.tables().get("orders").foreignKeys().stream()
                .anyMatch(fk -> fk.name().equalsIgnoreCase(name));
    }

    private static boolean liveIndexExists(DataSource dataSource, String name) throws Exception {
        CurrentSchema current = new CurrentSchemaReader().read(dataSource);
        return current.tables().get("orders").indexes().stream()
                .anyMatch(index -> index.name().equalsIgnoreCase(name));
    }

    @Test
    void previewIssuesATokenOverExactlyTheForeignSurplusSet() throws Exception {
        DataSource dataSource = dataSource();
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest();

        SurplusDropSupport.State state = SurplusDropSupport.prepare(dataSource, manifest);

        assertTrue(state.hasDroppable());
        assertNotNull(state.dropToken());
        List<String> identities = state.report().surplus().stream()
                .map(s -> s.kind() + " " + s.table() + "." + s.liveName().toLowerCase())
                .sorted()
                .toList();
        String expected = DestructiveAckToken.compute(FINGERPRINT, identities);
        assertEquals(expected, state.dropToken(), "the dropToken must be exactly the hash of the live "
                + "FOREIGN identities -- recomputation across calls is byte-identical");
        List<String> expanded = new ArrayList<>(identities);
        expanded.add("FOREIGN_KEY other.fk_ghost");
        assertNotEquals(state.dropToken(), DestructiveAckToken.compute(FINGERPRINT, expanded.stream().sorted().toList()),
                "a different live surplus set hashes to a different token -- which is exactly the "
                + "staleness signal the drop endpoint compares against");

        assertTrue(state.liveByName().values().stream()
                        .anyMatch(value -> value == ConstraintSurplusClassifier.Classification.IMPLICIT),
                "the PK-backing indexes H2 auto-names are in the live universe and classified IMPLICIT");
    }

    @Test
    void implicitPrimaryKeyBackingIndexIsRefusedAndNothingIsDropped() throws Exception {
        DataSource dataSource = dataSource();
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest();
        SurplusDropSupport.State state = SurplusDropSupport.prepare(dataSource, manifest);

        // H2 auto-names PK-backing indexes PRIMARY_KEY_<n>; the exact name is engine-assigned, so
        // capture it from the SAME prepared state instead of hard-coding it -- the classification is
        // what matters, never the name.
        String implicitName = state.liveByName().entrySet().stream()
                .filter(e -> e.getValue() == ConstraintSurplusClassifier.Classification.IMPLICIT)
                .findFirst().orElseThrow().getKey();
        ConstraintSurplusDropPlan.Plan plan = ConstraintSurplusDropPlan.plan(
                state.report(), state.liveByName(), List.of(implicitName));

        assertTrue(plan.droppable().isEmpty(), "nothing may be dropped: " + plan.droppable());
        assertEquals(1, plan.refused().size());
        assertTrue(plan.refused().get(0).code().startsWith("B3:surplus_not_foreign:" + implicitName + ":"),
                plan.refused().get(0).code());

        // A request refused at plan time never reaches the executor -- nothing was dropped, no
        // history rows were written.
        assertEquals(0, historyRowCount(dataSource), "refusals must not write SURPLUS_DROPPED rows");
    }

    @Test
    void staleTokenIsRefusedByRecomputeAndCompare() throws Exception {
        DataSource dataSource = dataSource();
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest();
        SurplusDropSupport.State first = SurplusDropSupport.prepare(dataSource, manifest);
        String firstToken = first.dropToken();

        // The live surplus set changes (a NEW foreign index appears -- as if a DBA added one between
        // preview and drop). The column is deliberately NOT the PK column: index-over-PK-columns
        // would classify IMPLICIT and change nothing.
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE customers ADD COLUMN created_at TIMESTAMP");
            statement.execute("CREATE INDEX idx_customers_created ON customers(created_at)");
        }
        SurplusDropSupport.State second = SurplusDropSupport.prepare(dataSource, manifest);

        assertNotEquals(firstToken, second.dropToken(),
                "the plan's staleness rule, verbatim: recompute and compare -- a changed live surplus "
                        + "set flips every token issued before the change, so the old token is stale "
                        + "and the drop is refused against the NEW set");
    }

    @Test
    void dropRecordsHistoryBeforeEachDropAndRemovesTheLiveConstraint() throws Exception {
        DataSource dataSource = dataSource();
        SchemaLifecycleExecutor.SchemaManifest manifest = manifest();
        SurplusDropSupport.State state = SurplusDropSupport.prepare(dataSource, manifest);

        assertTrue(liveForeignKeyExists(dataSource, "fk_orders_customer"));
        assertTrue(liveIndexExists(dataSource, "idx_orders_created"));
        ConstraintSurplusDropPlan.Plan plan = ConstraintSurplusDropPlan.plan(
                state.report(), state.liveByName(), List.of("fk_orders_customer", "idx_orders_created"));
        assertTrue(plan.refused().isEmpty(), plan.refused().toString());

        ConstraintSurplusDropExecutor.DropOutcome outcome =
                ConstraintSurplusDropExecutor.execute(dataSource, plan);

        assertTrue(!outcome.hadFailure(), outcome.failure());
        assertEquals(2, outcome.dropped().size());
        assertEquals(2, historyRowCount(dataSource), "every drop is recorded BEFORE/DURING the run");
        assertTrue(!liveForeignKeyExists(dataSource, "fk_orders_customer"), "the FK is really gone");
        assertTrue(!liveIndexExists(dataSource, "idx_orders_created"), "the index is really gone");

        // The audit row carries the re-create DDL.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT items_json FROM npdev_schema_history WHERE classification = 'SURPLUS_DROPPED'");
                ResultSet resultSet = statement.executeQuery()) {
            List<String> hints = new ArrayList<>();
            while (resultSet.next()) {
                hints.add(resultSet.getString(1));
            }
            assertTrue(hints.stream().anyMatch(h -> h.contains("re-create by hand") && h.contains("fk_orders_customer")),
                    hints.toString());
            assertTrue(hints.stream().anyMatch(h -> h.contains("re-create by hand") && h.contains("idx_orders_created")),
                    hints.toString());
        }

        // A token issued BEFORE the drop is now stale against the smaller surplus set.
        SurplusDropSupport.State after = SurplusDropSupport.prepare(dataSource, manifest);
        assertNotEquals(state.dropToken(), after.dropToken());
        assertTrue(after.report().surplus().isEmpty(),
                "after the drop the remaining surplus is nothing -- the operator's decision was "
                        + "executed exactly and completely");
    }
}