package com.finalexec.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SER closure-plan G4: {@link ConversionHookRunner#splitStatements} (via the package-private test seam
 * {@code splitStatementsForTest}) must not split a {@code ;} that appears inside a line comment, a block
 * comment, a single-quoted literal, a double-quoted identifier, or Postgres dollar-quoting -- while two
 * ordinary statements still split into two. Comment/quote text is preserved verbatim in the output (not
 * stripped) -- assertions below check the split COUNT and that each statement's real content survived,
 * not that comment text vanished.
 */
class ConversionHookSqlSplitterTest {

    @Test
    void semicolonInsideALineCommentDoesNotSplit() {
        List<String> statements = ConversionHookRunner.splitStatementsForTest(
                "SELECT 1; -- a comment; with a fake terminator\nSELECT 2;");
        assertEquals(2, statements.size(), statements.toString());
        assertEquals("SELECT 1", statements.get(0));
        assertTrue(statements.get(1).contains("-- a comment; with a fake terminator"), statements.get(1));
        assertTrue(statements.get(1).trim().endsWith("SELECT 2"), statements.get(1));
    }

    @Test
    void semicolonInsideABlockCommentDoesNotSplit() {
        List<String> statements = ConversionHookRunner.splitStatementsForTest(
                "SELECT 1; /* a comment; with a fake terminator */ SELECT 2;");
        assertEquals(2, statements.size(), statements.toString());
        assertEquals("SELECT 1", statements.get(0));
        assertTrue(statements.get(1).contains("/* a comment; with a fake terminator */"), statements.get(1));
        assertTrue(statements.get(1).trim().endsWith("SELECT 2"), statements.get(1));
    }

    @Test
    void semicolonInsideASingleQuotedLiteralStillWorks() {
        List<String> statements = ConversionHookRunner.splitStatementsForTest(
                "UPDATE t SET note = 'a;b'; SELECT 2;");
        assertEquals(2, statements.size(), statements.toString());
        assertEquals("UPDATE t SET note = 'a;b'", statements.get(0));
        assertEquals("SELECT 2", statements.get(1).trim());
    }

    @Test
    void semicolonInsideADoubleQuotedIdentifierDoesNotSplit() {
        List<String> statements = ConversionHookRunner.splitStatementsForTest(
                "SELECT \"weird;column\" FROM t; SELECT 2;");
        assertEquals(2, statements.size(), statements.toString());
        assertEquals("SELECT \"weird;column\" FROM t", statements.get(0));
        assertEquals("SELECT 2", statements.get(1).trim());
    }

    @Test
    void semicolonInsideDollarQuotingDoesNotSplit() {
        List<String> statements = ConversionHookRunner.splitStatementsForTest(
                "CREATE FUNCTION f() RETURNS void AS $$ BEGIN UPDATE t SET x = 1; END; $$ LANGUAGE plpgsql;"
                        + " SELECT 2;");
        assertEquals(2, statements.size(), statements.toString());
        assertTrue(statements.get(0).contains("$$ BEGIN UPDATE t SET x = 1; END; $$"), statements.get(0));
        assertEquals("SELECT 2", statements.get(1).trim());
    }

    @Test
    void semicolonInsideTaggedDollarQuotingDoesNotSplit() {
        List<String> statements = ConversionHookRunner.splitStatementsForTest(
                "CREATE FUNCTION f() RETURNS void AS $tag$ UPDATE t SET x = 1; $tag$ LANGUAGE sql;"
                        + " SELECT 2;");
        assertEquals(2, statements.size(), statements.toString());
        assertTrue(statements.get(0).contains("$tag$ UPDATE t SET x = 1; $tag$"), statements.get(0));
        assertEquals("SELECT 2", statements.get(1).trim());
    }

    @Test
    void twoOrdinaryStatementsStillSplitIntoTwo() {
        List<String> statements = ConversionHookRunner.splitStatementsForTest(
                "ALTER TABLE t ADD COLUMN c INTEGER; UPDATE t SET c = 1;");
        assertEquals(2, statements.size(), statements.toString());
        assertEquals("ALTER TABLE t ADD COLUMN c INTEGER", statements.get(0));
        assertEquals("UPDATE t SET c = 1", statements.get(1).trim());
    }

    @Test
    void aTrailingLineCommentWithNoFinalSemicolonIsPreserved() {
        List<String> statements = ConversionHookRunner.splitStatementsForTest(
                "SELECT 1 -- trailing comment, no terminator");
        assertEquals(1, statements.size(), statements.toString());
        assertEquals("SELECT 1 -- trailing comment, no terminator", statements.get(0));
    }
}
