package com.finalexec.db.datamobility;

import com.finalexec.db.schemastate.CurrentSchema;
import com.finalexec.db.schemastate.CurrentSchemaReader;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real-engine proof {@link DataMobilityStructureCheckDirectionTest} cannot give: two REAL,
 * independent H2 in-memory databases, real {@code CREATE TABLE} DDL, real {@link CurrentSchemaReader}
 * introspection (JDBC {@code DatabaseMetaData}, not a hand-built record), then the same {@link
 * DataMobilityStructureCheck} the pure-record tests exercise. This is the closest thing to the
 * coordinator's requested "H2 round trip" without touching a second engine -- H2, Postgres, MySQL and
 * SQL Server all differ in how they SPELL a type back through the catalog, but the classification logic
 * under test here (widening/narrowing, missing table/column, nullability) does not depend on which
 * engine did the spelling, only on {@code com.npdev.dsl.v1.schemaevolution.SqlTypeNormalization}
 * already making H2's and Postgres's spellings compare equal -- proven elsewhere
 * ({@code SchemaLifecycleExecutorNormalizeSqlTypePostgresAliasTest} and friends), not re-proven here.
 */
class DataMobilityStructureCheckH2Test {

    private static final Set<String> H2_SYSTEM_SCHEMAS = SqlDialects.forName("h2").systemSchemas();

    @Test
    void matchingLiveSchemas_areEqual() throws SQLException {
        CurrentSchema source = readSchema("jdbc:h2:mem:dmsc-source-match-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "CREATE TABLE orders (id UUID NOT NULL, amount NUMERIC(10,2) NOT NULL, "
                        + "notes VARCHAR(255), PRIMARY KEY (id))");
        CurrentSchema target = readSchema("jdbc:h2:mem:dmsc-target-match-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "CREATE TABLE orders (id UUID NOT NULL, amount NUMERIC(10,2) NOT NULL, "
                        + "notes VARCHAR(255), PRIMARY KEY (id))");

        StructureCheckResult result = DataMobilityStructureCheck.check(source, target, "h2", false);

        assertEquals(StructureVerdict.EQUAL, result.verdict());
    }

    @Test
    void targetPrecisionNarrowerThanSource_isIncompatible() throws SQLException {
        CurrentSchema source = readSchema("jdbc:h2:mem:dmsc-source-narrow-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "CREATE TABLE orders (id UUID NOT NULL, amount NUMERIC(10,2) NOT NULL, PRIMARY KEY (id))");
        // Same scale (2), smaller precision (5 < 10) -- TypeChangeMatrix classifies this NARROWING.
        CurrentSchema target = readSchema("jdbc:h2:mem:dmsc-target-narrow-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "CREATE TABLE orders (id UUID NOT NULL, amount NUMERIC(5,2) NOT NULL, PRIMARY KEY (id))");

        StructureCheckResult result = DataMobilityStructureCheck.check(source, target, "h2", false);

        assertEquals(StructureVerdict.INCOMPATIBLE, result.verdict());
        assertTrue(result.incompatibleReasons().stream().anyMatch(r -> r.contains("orders.amount")),
                "expected an orders.amount reason, got: " + result.incompatibleReasons());
    }

    @Test
    void targetMissingColumnFromLiveSource_isIncompatibleWithoutDdl() throws SQLException {
        CurrentSchema source = readSchema("jdbc:h2:mem:dmsc-source-col-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "CREATE TABLE orders (id UUID NOT NULL, amount NUMERIC(10,2) NOT NULL, "
                        + "legacy_code VARCHAR(30), PRIMARY KEY (id))");
        CurrentSchema target = readSchema("jdbc:h2:mem:dmsc-target-col-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1",
                "CREATE TABLE orders (id UUID NOT NULL, amount NUMERIC(10,2) NOT NULL, PRIMARY KEY (id))");

        StructureCheckResult result = DataMobilityStructureCheck.check(source, target, "h2", false);

        assertEquals(StructureVerdict.INCOMPATIBLE, result.verdict());
        assertTrue(result.incompatibleReasons().stream().anyMatch(r -> r.contains("legacy_code")),
                "expected a legacy_code reason, got: " + result.incompatibleReasons());
    }

    private static CurrentSchema readSchema(String jdbcUrl, String... ddl) throws SQLException {
        DataSource dataSource = new SingleConnectionUrlDataSource(jdbcUrl);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String statementText : ddl) {
                statement.execute(statementText);
            }
        }
        return new CurrentSchemaReader().read(dataSource, H2_SYSTEM_SCHEMAS);
    }

    /** Minimal single-URL {@link DataSource}, same shape as {@code JdbcBusinessConceptStorePredicateV2Test}'s
     *  helper: a fresh physical connection per {@code getConnection()} call, kept alive across them by the
     *  H2 URL's own {@code DB_CLOSE_DELAY=-1} rather than by pinning one JDBC {@link Connection}. */
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
