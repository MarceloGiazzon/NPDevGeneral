package com.npdev.generator;

import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRUTH-1: pins the contract of the emitted {@code GET /api/admin/box/truth} rollup in
 * {@code business-ui-box-view-controller.mustache}. The behaviour itself is proven live (the
 * {@code truth-declared-levels-reach-the-rollup-and-block-nothing} behaviour probe and the Box Inspector
 * browser routine) and its parity with {@code NPDevCli/npdev_truth.py} by twin-pair rule
 * {@code truth-rollup-java-python}; this guards the template-level promises that no booted app is needed
 * to check: both new endpoints are super-user gated, T6 is never derived, truth never blocks, and the
 * Python twin's vocabulary is present.
 */
class GeneratedBoxViewControllerTruthContractTest {

    private static String rendered() {
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        return templates.render("business-ui-box-view-controller.mustache", Map.of(
                "controllerPackage", "com.npdev.generated.controllers",
                "superUserRole", "SUPERUSER"));
    }

    @Test
    void rendersCleanlyWithTheConfiguredSuperUserRole() {
        String source = rendered();
        assertFalse(source.contains("{{"), "no unrendered mustache tag may survive:\n" + source);
        assertTrue(source.contains("SUPER_USER_ROLE = \"SUPERUSER\""));
    }

    @Test
    void everyEndpointIsSuperUserGatedBeforeAnyWork() {
        String source = rendered();
        Matcher mapping = Pattern.compile("@GetMapping\\(\"([^\"]+)\"\\)\\s+public [^{]+\\{\\s+requireSuperUser\\(request\\);")
                .matcher(source);
        int gated = 0;
        StringBuilder paths = new StringBuilder();
        while (mapping.find()) {
            gated++;
            paths.append(mapping.group(1)).append(' ');
        }
        assertEquals(4, gated, "box, box/graph, box/truth and box/truth/evidence must each call requireSuperUser first; got: " + paths);
        assertTrue(paths.toString().contains("/api/admin/box/truth "), paths.toString());
        assertTrue(paths.toString().contains("/api/admin/box/truth/evidence "), paths.toString());
    }

    @Test
    void releaseApprovedIsNeverDerivedAndFailureCapsAtRunsLocally() {
        String source = rendered().replaceAll("\\s+", "");
        assertTrue(source.contains("best=Math.max(best,5);"), "T5 needs two kinds of test evidence");
        assertFalse(source.contains("Math.max(best,6)"), "T6 (ReleaseApproved) is a human decision and must never be derived");
        assertTrue(source.contains("if(best>3&&failingTest){best=3;}"),
                "a fresh failing test must cap a concept at T3 -- the Python twin does the same");
    }

    @Test
    void truthOnlyDisplaysAndDegradesToUnknownInsteadOfFailing() {
        String source = rendered();
        assertTrue(source.contains("return unavailableTruthRollup();"),
                "an unreadable manifest must answer an empty 'unknown' rollup, not an error");
        assertTrue(source.contains("Display only -- nothing is blocked by this."));
        int forbidden = 0;
        Matcher matcher = Pattern.compile("HttpStatus\\.FORBIDDEN").matcher(source);
        while (matcher.find()) {
            forbidden++;
        }
        assertEquals(1, forbidden, "the ONLY refusal in the controller is the super-user check -- no truth level may refuse anything");
    }

    @Test
    void sharesTheRollupVocabularyWithThePythonTwin() {
        String source = rendered();
        for (String token : new String[] {"npdev-truth-rollup.v1", "known-failure", "undated", "stale", "overclaim",
                "underclaim", "observedStatus", "T5_EVIDENCE_BACKED", "_ops", "truth-rollup.json"}) {
            assertTrue(source.contains(token), "the Java rollup must speak the same vocabulary as npdev_truth.py: " + token);
        }
    }
}
