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
 * REG-92 (Move 11 W3), and specifically its "so the two surfaces cannot drift apart again" clause.
 *
 * <p>{@code inputFields} is ONE authored declaration rendered by TWO templates: the declared Panel
 * ({@code business-ui-app.mustache}) and the Aggregate Workbench
 * ({@code workbench-page.html.mustache}). REG-76 fixed the Workbench to render a newline-preserving
 * {@code <textarea>}; the Panel side kept {@code <input type="text">}, which silently collapses a
 * pasted multi-line value to one line -- accepted, visibly typed, posted mangled. Two moves and one
 * live-browser investigation later, that was still true, because nothing anywhere compared them.
 *
 * <p>So this test asserts the PROPERTY, on both surfaces, rather than the fix on one of them: the
 * mini-form free-text control must be a {@code textarea}, and no {@code <input type="text">} may be
 * created inside either mini-form builder. It reads the rendered templates, not the source files, so
 * it fails on what an app actually ships.
 */
public class BusinessUiEmitterPanelInputNewlineTest {

    private static final TemplateEngine TEMPLATES = new TemplateEngine("npdev-templates/");

    @Test
    @DisplayName("REG-92: the declared Panel's mini-form control is a newline-preserving textarea")
    void panelMiniFormUsesTextarea() {
        String app = TEMPLATES.render("business-ui-app.mustache", Map.of());

        String factory = withoutLineComments(body(app, "function createDeclaredPanelTextInput(field)"));
        assertTrue(factory.contains("createElement(\"textarea\")"),
                "the shared Panel mini-form control must be a textarea:\n" + factory);
        assertFalse(factory.contains("type = \"text\""),
                "an <input type=\"text\"> collapses pasted newlines -- that IS REG-92:\n" + factory);

        // Both call sites must go through it. Asserting the factory alone would pass while a caller
        // quietly kept building its own <input>, which is the exact shape of the original bug.
        for (String caller : new String[]{
                "function renderDeclaredPanelAddRowForm(panelMeta, entry, dataSourceName, columns)",
                "declared-panel-action-inputs"}) {
            String region = withoutLineComments(body(app, caller));
            assertTrue(region.contains("createDeclaredPanelTextInput("),
                    "mini-form at '" + caller + "' must use the shared control:\n" + region);
            assertFalse(region.contains("input.type = \"text\""),
                    "mini-form at '" + caller + "' still builds a newline-collapsing text input:\n" + region);
        }
    }

    @Test
    @DisplayName("REG-76/REG-92: the Aggregate Workbench renders the same control for the same declaration")
    void workbenchMiniFormUsesTheSameControl() {
        String workbench = TEMPLATES.render("workbench-page.html.mustache", Map.of());
        int inputFieldsAt = workbench.indexOf("a.inputFields || []");
        assertTrue(inputFieldsAt > 0, "expected the workbench action inputFields loop to still exist");
        // Bounded at the mini-form's own last statement, not a fixed character window: the workbench
        // has other, legitimately single-line <input type="text"> controls just past this block, and
        // a window wide enough to swallow one would make this assertion meaningless.
        int formEndsAt = workbench.indexOf("barDiv.append(inlineForm)", inputFieldsAt);
        assertTrue(formEndsAt > inputFieldsAt, "expected the workbench action mini-form to still be built inline");
        String region = workbench.substring(inputFieldsAt, formEndsAt);

        assertTrue(region.contains("<textarea"),
                "the workbench mini-form control must stay a textarea (REG-76):\n" + region);
        // REG-76's own explanatory comment quotes the very markup this asserts is absent, so match
        // on code only. (Caught by this test failing on the comment first -- a reminder that a
        // text-level assertion is only as good as what it excludes.)
        String code = withoutLineComments(region);
        assertFalse(Pattern.compile("<input[^>]*type=\"text\"").matcher(code).find(),
                "the workbench mini-form must not regress to <input type=\"text\">:\n" + code);
    }

    private static String withoutLineComments(String source) {
        StringBuilder kept = new StringBuilder();
        for (String line : source.split("\n")) {
            if (!line.trim().startsWith("//")) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    /** Text from {@code marker} to the end of the brace-balanced block that follows it. */
    private static String body(String source, String marker) {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, "marker not found in the rendered template: " + marker);
        Matcher open = Pattern.compile("\\{").matcher(source);
        if (!open.find(start)) {
            return source.substring(start);
        }
        int depth = 0;
        for (int i = open.start(); i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        return source.substring(start);
    }
}
