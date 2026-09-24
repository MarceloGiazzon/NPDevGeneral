package com.finalexec.db;

import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** H2-backed: {@link DataTransferIntrospection} reads live JDBC metadata through {@link SqlDialect},
 *  so this pins {@link H2Dialect#INSTANCE} active per the known "SqlDialects defaults to Postgres"
 *  gotcha, matching {@link SurplusDropFlowH2Test}'s own setup idiom. */
class DataTransferIntrospectionTest {

    private String url;

    @BeforeEach
    void setUp() throws SQLException {
        SqlDialects.setActive(H2Dialect.INSTANCE);
        url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=false";
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE widgets (id BIGINT PRIMARY KEY, name VARCHAR(64), notes VARCHAR(255))");
            statement.execute("CREATE TABLE npdev_event_store (id BIGINT PRIMARY KEY)");
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    void listTablesFindsEveryBaseTable() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url)) {
            List<String> tables = DataTransferIntrospection.listTables(connection, H2Dialect.INSTANCE);
            assertTrue(tables.stream().anyMatch(t -> t.equalsIgnoreCase("widgets")));
            assertTrue(tables.stream().anyMatch(t -> t.equalsIgnoreCase("npdev_event_store")));
        }
    }

    @Test
    void listColumnsReadsNameTypeNullabilityAndDefault() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url)) {
            List<DataTransferManifest.ColumnMeta> columns = DataTransferIntrospection.listColumns(connection, H2Dialect.INSTANCE, "widgets");
            assertEquals(3, columns.size());
            assertTrue(columns.stream().anyMatch(c -> c.name().equalsIgnoreCase("id") && !c.nullable()));
            assertTrue(columns.stream().anyMatch(c -> c.name().equalsIgnoreCase("notes") && c.nullable()));
        }
    }

    @Test
    void businessScopeExcludesInternalTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url)) {
            List<String> tables = DataTransferIntrospection.resolveScope(
                    connection, H2Dialect.INSTANCE, DataTransferIntrospection.Scope.BUSINESS, List.of());
            assertTrue(tables.stream().anyMatch(t -> t.equalsIgnoreCase("widgets")));
            assertFalse(tables.stream().anyMatch(t -> t.equalsIgnoreCase("npdev_event_store")));
        }
    }

    @Test
    void allScopeIncludesInternalTables() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url)) {
            List<String> tables = DataTransferIntrospection.resolveScope(
                    connection, H2Dialect.INSTANCE, DataTransferIntrospection.Scope.ALL, List.of());
            assertTrue(tables.stream().anyMatch(t -> t.equalsIgnoreCase("npdev_event_store")));
        }
    }

    @Test
    void explicitTablesOverrideScope() throws SQLException {
        try (Connection connection = DriverManager.getConnection(url)) {
            List<String> tables = DataTransferIntrospection.resolveScope(
                    connection, H2Dialect.INSTANCE, DataTransferIntrospection.Scope.BUSINESS, List.of("npdev_event_store"));
            assertEquals(List.of("npdev_event_store"), tables);
        }
    }
}
