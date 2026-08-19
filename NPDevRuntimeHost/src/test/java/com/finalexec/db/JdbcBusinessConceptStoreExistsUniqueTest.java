package com.finalexec.db;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5.2 (closes RUN-1 item 4): live proof of {@link JdbcBusinessConceptStore#existsUnique} against a
 * REAL H2 datasource -- both that its candidate-narrowing SQL {@code WHERE} preserves the EXACT
 * pre-R5.2 comparison semantics ({@link com.npdev.kernel.ports.ConceptStore#uniqueValuesCollide},
 * pinned engine-independently by {@code ConceptStoreExistsUniqueTest}), and that it no longer
 * materializes an entire tenant table to answer one yes/no question -- the bug RUN-1 item 4 named as
 * "the platform's worst remaining data-scale landmine".
 *
 * <p>Before this override existed, every create/update ran {@code conceptStore.findAll(tenantId,
 * conceptName)} first: fetch and deserialize EVERY row, then loop comparing in the JVM. The volume
 * test below measures the fix the only honest way available in this session -- {@code npdev bench}
 * (roadmap item R3.7) does not exist yet -- by timing the NEW pushdown against the OLD path
 * ({@link com.npdev.kernel.ports.ConceptStore#findAll}, still present and still used elsewhere) over
 * the identical 100k-row table, in the same JVM, back to back.
 */
class JdbcBusinessConceptStoreExistsUniqueTest {

    private static final String TENANT_A = "tenant-a";
    private static final String CONCEPT = "Widget";

    private DataSource dataSource;
    private JdbcBusinessConceptStore store;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id UUID NOT NULL, sku VARCHAR(255), qty INT, "
                    + "price NUMERIC(19,4), region VARCHAR(255), tenant_id VARCHAR(120) NOT NULL, PRIMARY KEY (id))");
        }
        CompiledConcept widget = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(
                        new CompiledField("id", "uuid", "java.util.UUID", true, true, false),
                        new CompiledField("sku", "string", "String", false, false, true),
                        new CompiledField("qty", "int", "Integer", false, false, false),
                        new CompiledField("price", "decimal", "java.math.BigDecimal", false, false, false),
                        new CompiledField("region", "string", "String", false, false, false)
                )
        );
        CompiledModel model = new CompiledModel("r5.2.existsUnique", "1.0.0", "1.0.0", Map.of(widget.getName(), widget));
        store = new JdbcBusinessConceptStore(dataSource, model);
    }

    private String seed(String tenantId, Map<String, Object> data) {
        String id = UUID.randomUUID().toString();
        store.save(new ConceptRecord(CONCEPT, id, tenantId, data));
        return id;
    }

    // ------------------------------------------------------------------ semantics parity with the pre-R5.2 full scan

    @Test
    void caseAndWhitespaceInsensitiveCollisionIsDetectedThroughSql() {
        seed(TENANT_A, Map.of("sku", "ABC-1"));

        assertTrue(store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("  abc-1  "), null),
                "the DB's own unique constraint is case-sensitive/untrimmed -- the pre-check must still catch this");
        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("ABC-2"), null));
    }

    @Test
    void numericColumnCollidesWithAStringCandidateCarryingTheSameDigits() {
        // Ledger RUN-1's own documented scenario: a numeric field submitted as a JSON string via the
        // untyped create(Map) path must still collide with the DB's numeric-typed stored value.
        seed(TENANT_A, Map.of("qty", 42));

        assertTrue(store.existsUnique(TENANT_A, CONCEPT, List.of("qty"), List.of("42"), null));
        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("qty"), List.of("42.0"), null),
                "a different text representation must not collide -- exact text equality, not numeric equality, "
                        + "for the String-vs-numeric branch");
    }

    @Test
    void decimalScaleMismatchNeitherSideAStringDoesNotCollideDespiteNativeSqlEqualityBeingLooser() {
        // price is NUMERIC(19,4); the physically stored value is always scale-4 ("42.0000"), no
        // matter what scale was written. SQL's native `price = 42` is TRUE (value-based, scale-blind)
        // -- a safe SUPERSET this override's candidate query relies on -- but the pinned JVM rule
        // (neither operand a String) falls back to toString() equality, which is FALSE for
        // "42.0000" vs "42". The pushdown's Java-side re-check must reproduce that exact answer,
        // proving the SQL layer's looser candidate match never leaks into the final result.
        seed(TENANT_A, Map.of("price", new BigDecimal("42.0000")));

        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("price"), List.of(42), null),
                "an Integer candidate against a differently-scaled stored decimal must not collide, "
                        + "even though the SQL candidate query would find this row");
        assertTrue(store.existsUnique(TENANT_A, CONCEPT, List.of("price"), List.of("42.0000"), null),
                "an exact-text String candidate matching the stored scale must still collide");
        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("price"), List.of("42"), null),
                "a String candidate with a DIFFERENT text representation ('42' vs '42.0000') must not collide");
    }

    @Test
    void aNullCandidateValueNeverCollides() {
        seed(TENANT_A, Map.of("sku", "ABC-1"));

        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), java.util.Collections.singletonList(null), null));
    }

    @Test
    void excludingTheCurrentRowsOwnIdMeansUpdatingToItsOwnValueIsNotAConflict() {
        String id = seed(TENANT_A, Map.of("sku", "ABC-1"));

        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("ABC-1"), id));
        assertTrue(store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("ABC-1"), null),
                "sanity check: without excludeId the same row IS a match");
        assertTrue(store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("ABC-1"), UUID.randomUUID().toString()),
                "excluding a DIFFERENT id must not suppress the real collision");
    }

    @Test
    void anotherTenantsMatchingValueNeverCollides() {
        seed("tenant-b", Map.of("sku", "ABC-1"));

        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("ABC-1"), null));
    }

    @Test
    void compoundUniqueRequiresEveryFieldToMatchTheSameRow() {
        seed(TENANT_A, Map.of("region", "west", "sku", "ABC-1"));

        assertTrue(store.existsUnique(TENANT_A, CONCEPT, List.of("region", "sku"), List.of("west", "ABC-1"), null));
        assertFalse(store.existsUnique(TENANT_A, CONCEPT, List.of("region", "sku"), List.of("east", "ABC-1"), null),
                "matching only one of the compound fields must not collide (AND, not OR)");
    }

    // ------------------------------------------------------------------ the scale fix itself

    /**
     * The measurement: {@code npdev bench} (R3.7) does not exist, so this times the NEW pushdown
     * against the OLD full-scan path ({@link com.npdev.kernel.ports.ConceptStore#findAll}) over the
     * SAME 100k-row table, in the same JVM/test run, back to back -- an honest, reproducible,
     * apples-to-apples "before vs after" comparison rather than an arbitrary absolute threshold.
     */
    @Test
    void existsUniqueOverOneHundredThousandRowsIsDramaticallyFasterThanTheOldFullScanItReplaced() {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO widgets (id, sku, qty, tenant_id) "
                    + "SELECT RANDOM_UUID(), CONCAT('sku-', X), X, 'tenant-a' FROM SYSTEM_RANGE(1, 100000)");
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }

        Instant pushdownStart = Instant.now();
        boolean collides = store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("sku-does-not-exist"), null);
        Duration pushdownElapsed = Duration.between(pushdownStart, Instant.now());
        assertFalse(collides);

        Instant fullScanStart = Instant.now();
        java.util.List<ConceptRecord> everyRow = store.findAll(TENANT_A, CONCEPT);
        Duration fullScanElapsed = Duration.between(fullScanStart, Instant.now());
        assertEquals(100_000, everyRow.size(), "sanity check: the old path really does fetch the whole table");
        // R5.2 measurement evidence (no `npdev bench` -- see class javadoc): printed so a real run's
        // console/CI log carries the actual numbers, not just a pass/fail.
        System.out.println("R5.2 measured: existsUnique(100k rows)=" + pushdownElapsed.toMillis()
                + "ms, findAll(100k rows)=" + fullScanElapsed.toMillis() + "ms");

        // The RELATIVE comparison (not an absolute millisecond budget) is the meaningful signal: both
        // measurements run back-to-back in the SAME JVM/test process, so both absorb whatever CPU/
        // disk contention this machine happens to be under at the time (a real, measured hazard on a
        // shared dev machine running several agents' builds at once -- an absolute "<300ms" bound
        // flaked at 365ms under exactly that contention before this comment was written). A 3x floor
        // is deliberately conservative: fetching and deserializing 100k rows into ConceptRecord
        // objects vs. a single targeted WHERE should realistically differ by 100-1000x, so 3x still
        // fails loudly if the pushdown regresses to a full scan while tolerating heavy machine noise.
        assertTrue(pushdownElapsed.toMillis() * 3 < Math.max(fullScanElapsed.toMillis(), 1),
                "existsUnique (" + pushdownElapsed.toMillis() + "ms) should be at least 3x faster than a full "
                        + "findAll scan (" + fullScanElapsed.toMillis() + "ms) over the identical 100k-row table -- "
                        + "the measured proof that it no longer materializes the whole table");

        // A real duplicate is still found correctly at this scale.
        assertTrue(store.existsUnique(TENANT_A, CONCEPT, List.of("sku"), List.of("SKU-99999"), null),
                "case-insensitive match against row 99999 of 100000 must still be found");
    }

    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(getClass().getName());
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
