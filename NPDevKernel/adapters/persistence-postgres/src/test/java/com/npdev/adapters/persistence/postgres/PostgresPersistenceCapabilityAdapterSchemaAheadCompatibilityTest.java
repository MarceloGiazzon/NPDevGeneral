package com.npdev.adapters.persistence.postgres;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 (REAL_LIFT_PLAN_2026-09-03, B5 "real lift"): {@code findById}/{@code query} build an explicit
 * column list from THIS BUILD's compiled model -- never {@code select *} -- so an older build's reads
 * never ask for a column its own compiled model does not declare, mirroring what {@code save} already
 * guarantees for writes (class javadoc: "accepted only when they can be resolved to actual database
 * columns"). The live table stands in for a newer build's own additive migration: it carries an extra
 * {@code bonus_note} column this build's compiled model (below) never declares.
 *
 * <p>Proxy-based, like every other test in this package (see {@link FakeJdbc}'s own javadoc for why
 * -- this module has no real JDBC driver on its test classpath) -- this class asserts the GENERATED
 * SQL TEXT rather than round-tripping real data, the same style {@link
 * PostgresPersistenceCapabilityAdapterColumnNamingTest} already uses.
 */
class PostgresPersistenceCapabilityAdapterSchemaAheadCompatibilityTest {

    @Test
    void findByIdSelectsOnlyTheColumnsThisBuildsCompiledModelDeclares() {
        AtomicReference<String> sqlRef = new AtomicReference<>();
        PostgresPersistenceCapabilityAdapter adapter = new PostgresPersistenceCapabilityAdapter(
                fakeDataSource(sqlRef, List.of("id", "name", "bonus_note")), widgetModel());

        adapter.findById("Widget", UUID.randomUUID().toString());

        String sql = sqlRef.get().toLowerCase(java.util.Locale.ROOT);
        assertFalse(sql.contains("select *"), "must never fall back to select *: " + sql);
        assertFalse(sql.contains("bonus_note"), "must never ask for a column this build's compiled model does not "
                + "declare, even though the live table has it: " + sql);
        assertTrue(sql.contains("name"), sql);
    }

    @Test
    void querySelectsOnlyTheColumnsThisBuildsCompiledModelDeclares() {
        AtomicReference<String> sqlRef = new AtomicReference<>();
        PostgresPersistenceCapabilityAdapter adapter = new PostgresPersistenceCapabilityAdapter(
                fakeDataSource(sqlRef, List.of("id", "name", "bonus_note")), widgetModel());

        adapter.query("Widget", Map.of());

        String sql = sqlRef.get().toLowerCase(java.util.Locale.ROOT);
        assertFalse(sql.contains("select *"), sql);
        assertFalse(sql.contains("bonus_note"), sql);
    }

    @Test
    void aConceptTheCompiledModelDoesNotKnowFallsBackToSelectStarUnchanged() {
        // No compiled model wired in at all -- today's exact prior behavior, preserved deliberately
        // (class javadoc guard: findCompiledConcept returns null when compiledModel is null).
        AtomicReference<String> sqlRef = new AtomicReference<>();
        PostgresPersistenceCapabilityAdapter adapter = new PostgresPersistenceCapabilityAdapter(
                fakeDataSource(sqlRef, List.of("id", "name", "bonus_note")));

        adapter.findById("Widget", UUID.randomUUID().toString());

        assertTrue(sqlRef.get().toLowerCase(java.util.Locale.ROOT).contains("select *"),
                "with no compiled model wired in, behavior must stay exactly what it always was: " + sqlRef.get());
    }

    private static CompiledModel widgetModel() {
        CompiledConcept widget = new CompiledConcept(
                "Widget", "Widget", "widgets",
                List.of(
                        new CompiledField("id", "string", "String", true, true, false),
                        new CompiledField("name", "string", "String", false, true, false)
                )
        );
        return new CompiledModel("test", "1.0.0", "1.0.0", Map.of(widget.getName(), widget));
    }

    private static DataSource fakeDataSource(AtomicReference<String> sqlRef, List<String> liveColumnNames) {
        AtomicReference<Connection> connectionRef = new AtomicReference<>();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metaDataWithColumns(liveColumnNames);
                    }
                    if ("prepareStatement".equals(method.getName())) {
                        sqlRef.set((String) args[0]);
                        return fakePreparedStatement(connectionRef);
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return FakeJdbc.defaultValue(method.getReturnType());
                }
        );
        connectionRef.set(connection);

        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(),
                new Class[]{DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return connection;
                    }
                    return FakeJdbc.defaultValue(method.getReturnType());
                }
        );
    }

    /** Reports {@code productName} "PostgreSQL" and a {@code getColumns(...)} result carrying one row
     *  per name in {@code liveColumnNames} -- the shape {@code TableColumns} reads via {@code
     *  COLUMN_NAME}. */
    private static DatabaseMetaData metaDataWithColumns(List<String> liveColumnNames) {
        return (DatabaseMetaData) Proxy.newProxyInstance(
                DatabaseMetaData.class.getClassLoader(),
                new Class[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getDatabaseProductName".equals(method.getName())) {
                        return "PostgreSQL";
                    }
                    if ("getColumns".equals(method.getName())) {
                        return columnsResultSet(liveColumnNames);
                    }
                    return FakeJdbc.defaultValue(method.getReturnType());
                }
        );
    }

    private static ResultSet columnsResultSet(List<String> columnNames) {
        AtomicInteger index = new AtomicInteger(-1);
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        return index.incrementAndGet() < columnNames.size();
                    }
                    if ("getString".equals(method.getName()) && "COLUMN_NAME".equals(args[0])) {
                        return columnNames.get(index.get());
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return FakeJdbc.defaultValue(method.getReturnType());
                }
        );
    }

    private static PreparedStatement fakePreparedStatement(AtomicReference<Connection> connectionRef) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return connectionRef.get();
                    }
                    if ("setObject".equals(method.getName())) {
                        return null;
                    }
                    if ("executeQuery".equals(method.getName())) {
                        return columnsResultSet(List.of()); // empty result set -- no rows to iterate
                    }
                    if ("executeUpdate".equals(method.getName())) {
                        return 1;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return FakeJdbc.defaultValue(method.getReturnType());
                }
        );
    }
}
