package com.npdev.adapters.persistence.postgres;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresPersistenceCapabilityAdapterH2CompatibilityTest {

    @Test
    void saveUsesMergeSyntaxWhenRunningAgainstH2ProofDatabase() {
        AtomicReference<String> sqlRef = new AtomicReference<>();
        PostgresPersistenceCapabilityAdapter adapter = new PostgresPersistenceCapabilityAdapter(fakeDataSource(sqlRef, "H2"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", UUID.randomUUID().toString());
        input.put("email", "mixed@example.test");
        input.put("name", "Mixed Path");

        adapter.save("contact", input);

        // Lower-cased before matching: these assertions are about which SYNTAX the dialect
        // chose (MERGE vs ON CONFLICT), never about keyword casing. SqlDialect (STOR-1)
        // emits uppercase keywords; the lowercase expectations below predate that seam and
        // failed on case alone once the dialect lookup started working again.
        String sql = sqlRef.get().toLowerCase(java.util.Locale.ROOT);
        assertTrue(sql.startsWith("merge into contacts"));
        assertTrue(sql.contains("key(id)"));
    }

    @Test
    void saveKeepsOnConflictSyntaxForPostgresConnections() {
        AtomicReference<String> sqlRef = new AtomicReference<>();
        PostgresPersistenceCapabilityAdapter adapter = new PostgresPersistenceCapabilityAdapter(fakeDataSource(sqlRef, "PostgreSQL"));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", UUID.randomUUID().toString());
        input.put("status", "Scheduled");

        adapter.save("appointment", input);

        // Lower-cased before matching: these assertions are about which SYNTAX the dialect
        // chose (MERGE vs ON CONFLICT), never about keyword casing. SqlDialect (STOR-1)
        // emits uppercase keywords; the lowercase expectations below predate that seam and
        // failed on case alone once the dialect lookup started working again.
        String sql = sqlRef.get().toLowerCase(java.util.Locale.ROOT);
        assertTrue(sql.startsWith("insert into appointments"));
        assertTrue(sql.contains("on conflict (id) do update set"));
        assertEquals(-1, sql.indexOf("merge into"));
    }

    private static DataSource fakeDataSource(AtomicReference<String> sqlRef, String productName) {
        ResultSet emptyColumns = (ResultSet) Proxy.newProxyInstance(
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

        DatabaseMetaData metaData = (DatabaseMetaData) Proxy.newProxyInstance(
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

        AtomicReference<Connection> connectionRef = new AtomicReference<>();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return metaData;
                    }
                    if ("prepareStatement".equals(method.getName())) {
                        sqlRef.set((String) args[0]);
                        return fakePreparedStatement(connectionRef);
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
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
                    return defaultValue(method.getReturnType());
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
                    if ("executeUpdate".equals(method.getName())) {
                        return 1;
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
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
