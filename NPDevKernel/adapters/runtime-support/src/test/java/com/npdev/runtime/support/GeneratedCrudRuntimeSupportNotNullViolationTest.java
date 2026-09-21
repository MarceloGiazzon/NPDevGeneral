package com.npdev.runtime.support;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-232: {@code GeneratedCrudRuntimeSupport.isNotNullViolation} classifies a NOT NULL constraint
 * failure as a business-level "required field missing" condition rather than a generic
 * infrastructure fault -- the fix that lets {@code executeCreateOrchestrationAction} return
 * {@code required_field_missing} and log at INFO instead of dumping the raw JDBC stack trace at
 * WARNING. Private static method, no prior test of any kind (the bug shipped unnoticed for exactly
 * that reason -- see REG-232.yml, found only because REG-231's fix let a request reach this code
 * path for the first time). Reflection is the same pattern
 * {@link GeneratedCrudRuntimeSupportOrchestrationConditionTest} already uses for this class's other
 * private methods.
 */
class GeneratedCrudRuntimeSupportNotNullViolationTest {

    private static boolean isNotNullViolation(Throwable exception) throws Exception {
        Method method = GeneratedCrudRuntimeSupport.class.getDeclaredMethod(
                "isNotNullViolation", Throwable.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, exception);
    }

    @Test
    void postgresAndH2ReportSqlState23502() throws Exception {
        assertTrue(isNotNullViolation(new SQLException(
                "null value in column \"movimento_id\" violates not-null constraint", "23502")));
    }

    @Test
    void mySqlReportsGenericClassPlusVendorErrorCode1048() throws Exception {
        assertTrue(isNotNullViolation(new SQLException(
                "Column 'movimento_id' cannot be null", "23000", 1048)));
    }

    @Test
    void sqlServerReportsGenericClassPlusVendorErrorCode515() throws Exception {
        assertTrue(isNotNullViolation(new SQLException(
                "Cannot insert the value NULL into column 'movimento_id'", "23000", 515)));
    }

    @Test
    void aBareMessageMentioningNotNullIsRecognizedWithNoSqlStateAtAll() throws Exception {
        assertTrue(isNotNullViolation(new RuntimeException(
                "NULL not allowed for column \"MOVIMENTO_ID\"")));
    }

    @Test
    void wrappedCauseIsUnwrapped() throws Exception {
        SQLException notNull = new SQLException("null value in column violates not-null constraint",
                "23502");
        assertTrue(isNotNullViolation(new RuntimeException("insert failed", notNull)));
    }

    @Test
    void uniqueAndForeignKeyViolationsAreNotMisclassifiedAsNotNull() throws Exception {
        assertFalse(isNotNullViolation(new SQLException("duplicate key value violates unique constraint",
                "23505")));
        assertFalse(isNotNullViolation(new SQLException("violates foreign key constraint", "23503")));
    }

    @Test
    void unrelatedExceptionsAndNullAreFalse() throws Exception {
        assertFalse(isNotNullViolation(new RuntimeException("connection refused")));
        assertFalse(isNotNullViolation(null));
    }
}
