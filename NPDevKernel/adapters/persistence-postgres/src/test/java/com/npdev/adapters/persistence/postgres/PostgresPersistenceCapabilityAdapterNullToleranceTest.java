package com.npdev.adapters.persistence.postgres;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PostgresPersistenceCapabilityAdapterNullToleranceTest {

    @Test
    void saveReturnsMapEvenWhenRecordContainsNullValues() {
        PostgresPersistenceCapabilityAdapter adapter = new PostgresPersistenceCapabilityAdapter(fakeDataSource());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", UUID.randomUUID().toString());
        input.put("status", "Scheduled");
        input.put("notes", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) adapter.save("appointment", input);

        assertEquals("Scheduled", saved.get("status"));
        assertNull(saved.get("notes"));
    }

    @Test
    void saveAppliesDeclaredFieldDefaultWhenOmitted() {
        // ARCH-8b: flow-compiled createConcept/updateConcept steps dispatch straight to save(),
        // bypassing the ConceptGatewaySemanticPolicy defaults pass generic CRUD create goes through.
        PostgresPersistenceCapabilityAdapter adapter =
                new PostgresPersistenceCapabilityAdapter(fakeDataSource(), widgetModel());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", UUID.randomUUID().toString());
        input.put("name", "Gadget");

        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) adapter.save("Widget", input);

        assertEquals("Draft", saved.get("status"), "Omitted field with a declared default should be defaulted");
    }

    @Test
    void saveDoesNotOverrideAnExplicitlySuppliedValue() {
        PostgresPersistenceCapabilityAdapter adapter =
                new PostgresPersistenceCapabilityAdapter(fakeDataSource(), widgetModel());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("id", UUID.randomUUID().toString());
        input.put("name", "Gadget");
        input.put("status", "Published");

        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) adapter.save("Widget", input);

        assertEquals("Published", saved.get("status"), "Caller-supplied value must win over the declared default");
    }

    private static CompiledModel widgetModel() {
        CompiledSchema statusSchema = new CompiledSchema(
                "string", Map.of(), null, List.of(), List.of(), "Draft", null, null, null, null, null, null);
        CompiledField statusField = new CompiledField(
                "status", "string", "String", false, false, false, List.of(), null, statusSchema);
        CompiledField nameField = new CompiledField("name", "string", "String", false, true, false);
        CompiledConcept widget = new CompiledConcept("Widget", "Widget", "widgets", List.of(nameField, statusField));
        return new CompiledModel(
                "arch8b.test", "1.0.0", "1.0.0",
                Map.of("Widget", widget),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static DataSource fakeDataSource() {
        Connection connection = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class[]{Connection.class},
                (proxy, method, args) -> {
                    if ("prepareStatement".equals(method.getName())) {
                        return fakePreparedStatement();
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                }
        );

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

    private static PreparedStatement fakePreparedStatement() {
        return (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class[]{PreparedStatement.class},
                (proxy, method, args) -> {
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