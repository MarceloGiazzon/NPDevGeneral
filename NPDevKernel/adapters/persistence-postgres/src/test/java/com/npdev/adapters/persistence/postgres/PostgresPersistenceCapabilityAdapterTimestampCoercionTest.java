package com.npdev.adapters.persistence.postgres;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresPersistenceCapabilityAdapterTimestampCoercionTest {

    @Test
    void saveCoercesScheduledAtFromIsoStringToTemporalValue() {
        AtomicReference<Object> scheduledAtBound = new AtomicReference<>();

        PostgresPersistenceCapabilityAdapter adapter = new PostgresPersistenceCapabilityAdapter(
                fakeDataSource(scheduledAtBound)
        );

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", UUID.randomUUID().toString());
        input.put("scheduledAt", "2026-03-18T19:25:46");
        input.put("status", "Scheduled");

        adapter.save("Appointment", input);

        Object bound = scheduledAtBound.get();
        assertTrue(bound instanceof Timestamp || bound instanceof OffsetDateTime);
    }

    private static DataSource fakeDataSource(AtomicReference<Object> scheduledAtBound) {
        AtomicReference<Connection> connectionRef = new AtomicReference<>();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return FakeJdbc.metaDataFor("PostgreSQL");
                    }
                    if ("prepareStatement".equals(method.getName())) {
                        return fakePreparedStatement(scheduledAtBound, connectionRef);
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

    private static PreparedStatement fakePreparedStatement(AtomicReference<Object> scheduledAtBound, AtomicReference<Connection> connectionRef) {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return connectionRef.get();
                    }
                    if ("setObject".equals(method.getName())) {
                        int index = (Integer) args[0];
                        Object value = args[1];
                        if (index == 2) {
                            scheduledAtBound.set(value);
                        }
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