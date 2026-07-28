package com.finalexec.db;

import com.finalexec.db.schemastate.SafetyClass;
import com.finalexec.db.schemastate.SchemaDiffItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SER-P8.2: {@link ProposedConversionSql} is a pure function -- no DB, no clock -- so every case is
 * asserted as an exact string, deterministically. One test per narrowing family per the plan: varchar
 * shrink (SUBSTRING), numeric precision (CAST), and an incompatible cross-family cast (omitted).
 */
class ProposedConversionSqlTest {

    @Test
    void varcharShrinkUsesSubstringToTruncateRatherThanError() {
        SchemaDiffItem item = SchemaDiffItem.of("NARROW_TYPE:widgets:name:VARCHAR(50):VARCHAR(10)",
                "widgets", "name", SafetyClass.DESTRUCTIVE_NARROW_TYPE, "VARCHAR(50)", "VARCHAR(10)");

        ProposedConversionSql.Proposal proposal = ProposedConversionSql.forNarrowing(item);

        assertEquals("""
                ALTER TABLE widgets ADD COLUMN name__new VARCHAR(10);
                UPDATE widgets SET name__new = SUBSTRING(name, 1, 10);
                ALTER TABLE widgets DROP COLUMN name;
                ALTER TABLE widgets RENAME COLUMN name__new TO name;""", proposal.sql());
        assertEquals("SELECT COUNT(*) FROM widgets WHERE name IS NOT NULL AND name__new IS NULL",
                proposal.verifySql());
    }

    @Test
    void numericPrecisionNarrowingUsesCast() {
        SchemaDiffItem item = SchemaDiffItem.of("NARROW_TYPE:invoices:total:NUMERIC(10,2):NUMERIC(5,2)",
                "invoices", "total", SafetyClass.DESTRUCTIVE_NARROW_TYPE, "NUMERIC(10,2)", "NUMERIC(5,2)");

        ProposedConversionSql.Proposal proposal = ProposedConversionSql.forNarrowing(item);

        assertEquals("""
                ALTER TABLE invoices ADD COLUMN total__new NUMERIC(5,2);
                UPDATE invoices SET total__new = CAST(total AS NUMERIC(5,2));
                ALTER TABLE invoices DROP COLUMN total;
                ALTER TABLE invoices RENAME COLUMN total__new TO total;""", proposal.sql());
        assertEquals("SELECT COUNT(*) FROM invoices WHERE total IS NOT NULL AND total__new IS NULL",
                proposal.verifySql());
    }

    @Test
    void numericScaleChangeNarrowingAlsoUsesCast() {
        SchemaDiffItem item = SchemaDiffItem.of("NARROW_TYPE:invoices:total:NUMERIC(10,2):NUMERIC(10,4)",
                "invoices", "total", SafetyClass.DESTRUCTIVE_NARROW_TYPE, "NUMERIC(10,2)", "NUMERIC(10,4)");

        ProposedConversionSql.Proposal proposal = ProposedConversionSql.forNarrowing(item);

        assertEquals("CAST(total AS NUMERIC(10,4))",
                proposal.sql().split("\n")[1].replace("UPDATE invoices SET total__new = ", "").replace(";", ""));
    }

    @Test
    void incompatibleCrossFamilyCastIsOmitted() {
        SchemaDiffItem item = SchemaDiffItem.of("NARROW_TYPE:widgets:id:VARCHAR(20):INTEGER",
                "widgets", "id", SafetyClass.DESTRUCTIVE_NARROW_TYPE, "VARCHAR(20)", "INTEGER");

        assertNull(ProposedConversionSql.forNarrowing(item),
                "VARCHAR -> INTEGER is a type-family mismatch (INCOMPARABLE) -- no safe generic conversion");
    }

    @Test
    void unparseableTypeIsOmitted() {
        SchemaDiffItem item = SchemaDiffItem.of("NARROW_TYPE:widgets:id:BIGINT:SOME_CUSTOM_TYPE",
                "widgets", "id", SafetyClass.DESTRUCTIVE_NARROW_TYPE, "BIGINT", "SOME_CUSTOM_TYPE");

        assertNull(ProposedConversionSql.forNarrowing(item));
    }

    @Test
    void nonNarrowingSafetyClassIsAlwaysNull() {
        SchemaDiffItem dropColumn = SchemaDiffItem.of("DROP_COLUMN:widgets:legacy:BOOLEAN", "widgets",
                "legacy", SafetyClass.DESTRUCTIVE_DROP_COLUMN, "BOOLEAN", null);
        assertNull(ProposedConversionSql.forNarrowing(dropColumn));

        SchemaDiffItem safe = SchemaDiffItem.of("ADD_COLUMN:widgets:note", "widgets", "note",
                SafetyClass.SAFE_ADDITIVE, null, "VARCHAR(20)");
        assertNull(ProposedConversionSql.forNarrowing(safe));
    }
}
