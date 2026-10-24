package com.npdev.adapters.persistence.postgres;

import com.npdev.kernel.storage.sql.H2Dialect;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-306: a unique-constraint violation on a flow-driven save must say "Unique constraint violated"
 * (which the generated service's requireFlowSucceeded maps to 409), whatever wording the engine uses;
 * any other integrity violation keeps the referential/constraint text.
 */
class PostgresPersistenceCapabilityAdapterUniqueViolationTest {

    @Test
    void duplicateInsertIsNamedAsUniqueConstraintViolationEvenWithH2Wording() {
        // H2's own text for a duplicate carries neither "unique constraint" nor the Postgres wording.
        SQLException duplicate = new SQLException(
                "Duplicate entry for key \"PUBLIC.PRIMARY_KEY_4\"; SQL statement: MERGE INTO contacts", "23505", 23505);
        PostgresPersistenceCapabilityAdapter adapter = adapterFailingWith(duplicate);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> adapter.save("contact", record()));

        assertTrue(thrown.getMessage().contains("Unique constraint violated"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("contacts"), thrown.getMessage());
        assertInstanceOf(SQLException.class, thrown.getCause());
    }

    @Test
    void otherIntegrityViolationKeepsTheReferentialConstraintText() {
        SQLException foreignKey = new SQLException("Referential integrity constraint violation", "23506", 23506);
        PostgresPersistenceCapabilityAdapter adapter = adapterFailingWith(foreignKey);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> adapter.save("contact", record()));

        assertTrue(thrown.getMessage().contains("Referential/constraint violation"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("Unique constraint violated"), thrown.getMessage());
    }

    private static Map<String, Object> record() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", UUID.randomUUID().toString());
        input.put("email", "dup@example.test");
        input.put("name", "Dup");
        return input;
    }

    private static PostgresPersistenceCapabilityAdapter adapterFailingWith(SQLException failure) {
        return new PostgresPersistenceCapabilityAdapter(fakeDataSource(failure), null, H2Dialect.INSTANCE);
    }

    private static DataSource fakeDataSource(SQLException failure) {
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
                        return "H2";
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
                        return failingPreparedStatement(connectionRef, failure);
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

    private static PreparedStatement failingPreparedStatement(
            AtomicReference<Connection> connectionRef, SQLException failure) {
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
                        throw failure;
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
