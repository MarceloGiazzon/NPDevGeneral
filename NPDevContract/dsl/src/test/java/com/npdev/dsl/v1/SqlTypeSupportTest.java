package com.npdev.dsl.v1;

import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledSchema;
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

    /**
     * REG-53: a declared {@code maxLength} must actually reach the SQL type -- previously this class
     * hardcoded {@code VARCHAR(255)} for every string/enum field regardless of {@code
     * CompiledSchema.getMaxLength()}, so the schema diff could never see a narrowing or widening
     * (`docs/REMAINDER_CLOSURE_PLAN.md` §1.2/§3.2).
     */
    @Test
    void honorsADeclaredMaxLengthForStringAndEnumFields() {
        assertEquals("VARCHAR(10)", sqlType("name", "string", "String", 10));
        assertEquals("VARCHAR(2000)", sqlType("bio", "string", "String", 2000));
        assertEquals("VARCHAR(10)", sqlType("status", "enum", "String", 10));
    }

    @Test
    void noDeclaredMaxLengthKeepsTheExistingDefault() {
        assertEquals("VARCHAR(255)", sqlType("name", "string", "String", null));
    }

    private static String sqlType(String name, String dslType, String javaType) {
        return SqlTypeSupport.sqlType(new CompiledField(
                name, dslType, javaType, false, false, false,
                List.of(), null, null, null, null, List.of(), null, null));
    }

    private static String sqlType(String name, String dslType, String javaType, Integer maxLength) {
        CompiledSchema schema = maxLength == null
                ? null
                : new CompiledSchema("string", null, null, null, null, maxLength, null, null, null);
        return SqlTypeSupport.sqlType(new CompiledField(
                name, dslType, javaType, false, false, false, List.of(), null, schema));
    }
}
