package com.npdev.dsl.v1.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "&lt;owner&gt; requires &lt;x&gt;" / "&lt;owner&gt; must define &lt;x&gt;" name the missing thing AFTER the verb.
 * The derived suggestedFix used to quote the word BEFORE it, telling the author to add what was
 * already there ("Add 'scope' here", "Add 'updateconcept step' here").
 */
class RequiredObjectSuggestedFixTest {

    private static String fix(String message) {
        return ValidationDiagnosticNormalizer.semanticDiagnostic(message, ValidationSeverity.ERROR).getSuggestedFix();
    }

    @Test
    void scopeRowRequiresDataSourceSuggestsDataSource() {
        String fix = fix("Panel orders action approve: scope \"row\" requires dataSource");
        assertTrue(fix.startsWith("Add 'dataSource' to the declaration this message names"), fix);
        assertFalse(fix.contains("'scope'"), fix);
    }

    @Test
    void stepMustDefineInputSuggestsInput() {
        String fix = fix("Flow approveOrder step save: updateConcept step must define input");
        assertTrue(fix.startsWith("Add 'input' to the declaration this message names"), fix);
        assertFalse(fix.toLowerCase().contains("updateconcept"), fix);
    }

    @Test
    void aClauseAfterTheVerbFallsBackToTheGenericSentence() {
        String fix = fix("Lifecycle on concept Order must have at least one state that is initial and reachable");
        assertEquals("Add the declaration this message says is required -- it is not optional, and the model "
                + "stays rejected until it is present.", fix);
    }

    @Test
    void subjectFirstGrammarStillQuotesTheSubject() {
        String fix = fix("Webhook inbound: secret is required");
        assertTrue(fix.startsWith("Add 'secret' here"), fix);
    }
}
