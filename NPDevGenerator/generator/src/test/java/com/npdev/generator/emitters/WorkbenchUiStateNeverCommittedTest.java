package com.npdev.generator.emitters;

import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 11 W6 (C1), and specifically its hard rule: <b>{@code $ui.*} state must never reach the
 * commit payload.</b>
 *
 * <p>UI state is presentation-only -- the same rule {@code visibleWhen} already carries. A surface
 * hidden by a {@code $ui.*} predicate whose rows exist in the draft still commits them unchanged;
 * anything stronger would silently delete data through the reconcile path. And the toggle's own
 * value is not a field of anything, so writing it into the draft would post an undeclared key to the
 * aggregate commit endpoint.
 *
 * <p>The guarantee is structural rather than defensive: the client holds UI state in a module-level
 * {@code uiStateValues} object that {@code store}/{@code draft} cannot see, so {@code toDraft()}
 * physically cannot include it. This test asserts that structure holds, because the cheap way to
 * break it later is one line inside {@code seedUiState} writing to {@code draft} instead.
 */
public class WorkbenchUiStateNeverCommittedTest {

    private static final TemplateEngine TEMPLATES = new TemplateEngine("npdev-templates/");

    @Test
    @DisplayName("$ui state is held outside the store, so it cannot reach toDraft() or a commit payload")
    void uiStateIsNeverPartOfTheDraft() {
        String page = TEMPLATES.render("workbench-page.html.mustache", Map.of());

        assertTrue(page.contains("var uiStateValues = {}"),
                "expected UI state to live in a module-level object outside the store");

        // The store's draft-producing surface must never mention it.
        String toDraft = region(page, "toDraft: function", 900);
        assertFalse(toDraft.contains("uiState"),
                "toDraft() must not know UI state exists -- that is what keeps it out of every commit:\n" + toDraft);

        // Nor may the seeder or the control renderer write into the draft/store.
        for (String fn : new String[]{"function seedUiState(descriptor)", "function renderUiStateControls(descriptor)"}) {
            String body = region(page, fn, 1200);
            assertFalse(Pattern.compile("\\bdraft\\s*\\[").matcher(body).find(),
                    fn + " must not write into the draft:\n" + body);
            assertFalse(body.contains("store.edit"),
                    fn + " must not edit the store:\n" + body);
        }
    }

    @Test
    @DisplayName("one grammar, two roots: evaluateVisibleWhen resolves $ui.<name> and still resolves $root.<field>, "
            + "now AND/OR-composable (BOUNDARY_LIFT_PLAN_2026-09-02.md Wave 4 4.3/B16 Step 1)")
    void visibleWhenResolvesBothRoots() {
        String page = TEMPLATES.render("workbench-page.html.mustache", Map.of());
        String evaluator = region(page, "function evaluateVisibleWhen(root, expression)", 2200);
        String resolver = region(page, "function resolveVisibleWhenIdent(token, root)", 1000);

        assertTrue(evaluator.contains("resolveVisibleWhenIdent"),
                "the AND/OR-composable evaluator must delegate identifier resolution to "
                        + "resolveVisibleWhenIdent so a $ui/$root reference works inside ANY clause, "
                        + "not just as the whole expression:\n" + evaluator);
        assertTrue(resolver.contains("ui.") && resolver.contains("uiStateValues["),
                "expected a $ui.<name> branch reading the presentation-only uiStateValues store:\n" + resolver);
        assertTrue(resolver.contains("root."),
                "the pre-existing $root.<field> resolution (dotted-path walk against `root`) must "
                        + "still be reachable:\n" + resolver);
    }

    private static String region(String source, String marker, int length) {
        int at = source.indexOf(marker);
        assertTrue(at >= 0, "marker not found in the rendered template: " + marker);
        return source.substring(at, Math.min(source.length(), at + length));
    }
}
