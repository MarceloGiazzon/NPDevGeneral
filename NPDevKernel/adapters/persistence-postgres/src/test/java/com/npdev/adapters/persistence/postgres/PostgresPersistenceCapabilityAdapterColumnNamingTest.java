package com.npdev.adapters.persistence.postgres;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresPersistenceCapabilityAdapterColumnNamingTest {

    @Test
    void saveUsesSnakeCaseColumnsForCamelCaseRuntimeKeys() {
        AtomicReference<String> sqlRef = new AtomicReference<>();
        PostgresPersistenceCapabilityAdapter adapter = new PostgresPersistenceCapabilityAdapter(fakeDataSource(sqlRef));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", UUID.randomUUID().toString());
        input.put("durationMinutes", 30);
        input.put("patientId", UUID.randomUUID().toString());
        input.put("requiresPreauth", false);
        input.put("scheduledAt", "2026-03-18T18:48:05");

        adapter.save("Appointment", input);

        String sql = sqlRef.get();
        assertEquals(true, sql.contains("duration_minutes"));
        assertEquals(true, sql.contains("patient_id"));
        assertEquals(true, sql.contains("requires_preauth"));
        assertEquals(true, sql.contains("scheduled_at"));
    }

    private static DataSource fakeDataSource(AtomicReference<String> sqlRef) {
        AtomicReference<Connection> connectionRef = new AtomicReference<>();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return FakeJdbc.metaDataFor("PostgreSQL");
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
