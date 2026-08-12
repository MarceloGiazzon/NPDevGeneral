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
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresPersistenceCapabilityAdapterUuidCoercionTest {

    @Test
    void saveCoercesForeignKeyUuidColumnsFromStringValues() {
        AtomicReference<Object> patientIdBound = new AtomicReference<>();
        AtomicReference<Object> roomIdBound = new AtomicReference<>();
        AtomicReference<Object> providerIdBound = new AtomicReference<>();

        PostgresPersistenceCapabilityAdapter adapter = new PostgresPersistenceCapabilityAdapter(
                fakeDataSource(patientIdBound, roomIdBound, providerIdBound)
        );

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", UUID.randomUUID().toString());
        input.put("patientId", UUID.randomUUID().toString());
        input.put("roomId", UUID.randomUUID().toString());
        input.put("providerId", UUID.randomUUID().toString());
        input.put("status", "Scheduled");

        adapter.save("Appointment", input);

        assertTrue(patientIdBound.get() instanceof UUID);
        assertTrue(roomIdBound.get() instanceof UUID);
        assertTrue(providerIdBound.get() instanceof UUID);
    }

    private static DataSource fakeDataSource(
            AtomicReference<Object> patientIdBound,
            AtomicReference<Object> roomIdBound,
            AtomicReference<Object> providerIdBound
    ) {
        AtomicReference<Connection> connectionRef = new AtomicReference<>();
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("getMetaData".equals(method.getName())) {
                        return FakeJdbc.metaDataFor("PostgreSQL");
                    }
                    if ("prepareStatement".equals(method.getName())) {
                        return fakePreparedStatement(patientIdBound, roomIdBound, providerIdBound, connectionRef);
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

    private static PreparedStatement fakePreparedStatement(
            AtomicReference<Object> patientIdBound,
            AtomicReference<Object> roomIdBound,
            AtomicReference<Object> providerIdBound,
            AtomicReference<Connection> connectionRef
    ) {
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
                            patientIdBound.set(value);
                        } else if (index == 3) {
                            roomIdBound.set(value);
                        } else if (index == 4) {
                            providerIdBound.set(value);
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