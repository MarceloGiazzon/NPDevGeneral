package com.npdev.adapters.persistence.postgres;

import java.lang.reflect.Proxy;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

/**
 * The JDBC plumbing every fake DataSource in this package needs, in ONE place.
 *
 * <p>WHY THIS EXISTS. Five test classes here each hand-rolled the same {@link Proxy}-based
 * {@code Connection}/{@code PreparedStatement} doubles, and four of the five never implemented
 * {@code Connection.getMetaData()} while all five never implemented
 * {@code PreparedStatement.getConnection()}. That cost nothing until STOR-10 (d7f05b2b) made the
 * adapter ask the connection which engine it is talking to, at two call sites:
 *
 * <pre>
 *   buildUpsertPlan -> SqlDialects.forConnection(connection)          // needs getMetaData()
 *   bindable        -> SqlDialects.forConnection(stmt.getConnection()) // needs getConnection()
 * </pre>
 *
 * <p>A real JDBC driver answers both. A {@code Proxy} whose handler falls through to a default
 * returns {@code null}, so the dialect lookup refused — correctly, per its X0 rule — and 8 of the
 * 12 tests in this module have asserted nothing since. Production was never affected: the same
 * suite passes against real Postgres/MySQL/SQL Server containers, which is why this stayed a red
 * CI step rather than a user-visible bug.
 *
 * <p>The duplication WAS the defect: one shared copy of this plumbing could not have been missing
 * from four files. Bespoke behaviour (which bind index a test captures, what SQL it records) stays
 * in each test, where it belongs.
 */
final class FakeJdbc {

    private FakeJdbc() {
    }

    /** A {@code getColumns(...)} result with no rows — the shape these tests already relied on. */
    static ResultSet emptyColumns() {
        return (ResultSet) Proxy.newProxyInstance(
                ResultSet.class.getClassLoader(),
                new Class[]{ResultSet.class},
                (proxy, method, args) -> {
                    if ("next".equals(method.getName())) {
                        return false;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    /**
     * Metadata reporting {@code productName}, which is what {@code SqlDialects.forConnection}
     * matches on — "PostgreSQL", "H2", "MySQL", "Microsoft SQL Server".
     */
    static DatabaseMetaData metaDataFor(String productName) {
        ResultSet emptyColumns = emptyColumns();
        return (DatabaseMetaData) Proxy.newProxyInstance(
                DatabaseMetaData.class.getClassLoader(),
                new Class[]{DatabaseMetaData.class},
                (proxy, method, args) -> {
                    if ("getDatabaseProductName".equals(method.getName())) {
                        return productName;
                    }
                    if ("getColumns".equals(method.getName())) {
                        return emptyColumns;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    /** Zero/false/null per the method's return type, so an unstubbed call cannot throw. */
    static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) return null;
        if (returnType == Boolean.TYPE) return false;
        if (returnType == Byte.TYPE) return (byte) 0;
        if (returnType == Short.TYPE) return (short) 0;
        if (returnType == Integer.TYPE) return 0;
        if (returnType == Long.TYPE) return 0L;
        if (returnType == Float.TYPE) return 0f;
        if (returnType == Double.TYPE) return 0d;
        if (returnType == Character.TYPE) return '\0';
        return null;
    }
}
