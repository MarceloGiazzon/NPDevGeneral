package com.npdev.dsl.v1;

import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.SqlTypeSupport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlTypeSupportTest {

    @Test
    void mapsDslFieldTypesToCanonicalSqlTypes() {
        assertEquals("UUID", sqlType("id", "uuid", "java.util.UUID"));
        assertEquals("UUID", sqlType("productId", "reference", "java.util.UUID"));
        assertEquals("INTEGER", sqlType("quantity", "integer", "Integer"));
        assertEquals("BIGINT", sqlType("sequence", "long", "Long"));
        assertEquals("BOOLEAN", sqlType("active", "boolean", "Boolean"));
        assertEquals("DATE", sqlType("birthDate", "date", "java.time.LocalDate"));
        assertEquals("TIMESTAMP WITH TIME ZONE", sqlType("createdAt", "datetime", "java.time.OffsetDateTime"));
        assertEquals("JSONB", sqlType("details", "object", "Object"));
        assertEquals("JSONB", sqlType("items", "array", "List"));
        assertEquals("VARCHAR(255)", sqlType("status", "enum", "String"));
        assertEquals("VARCHAR(255)", sqlType("name", "string", "String"));
    }

    @Test
    void fallsBackFromJavaTypeWhenDslTypeIsMissing() {
        assertEquals("NUMERIC(19,2)", sqlType("amount", null, "java.math.BigDecimal"));
        assertEquals("DATE", sqlType("day", null, "java.time.LocalDate"));
        assertEquals("TIMESTAMP WITH TIME ZONE", sqlType("instant", null, "java.time.Instant"));
    }

    private static String sqlType(String name, String dslType, String javaType) {
        return SqlTypeSupport.sqlType(new CompiledField(
                name, dslType, javaType, false, false, false,
                List.of(), null, null, null, null, List.of(), null, null));
    }
}
