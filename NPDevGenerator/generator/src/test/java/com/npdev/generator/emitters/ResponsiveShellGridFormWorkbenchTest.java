package com.npdev.generator.emitters;

import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R7.10 (Responsive/mobile pass on grid, forms, and workbench).
 *
 * <p>Asserts that the four generated surfaces this item covers actually EMIT the responsive rules
 * the roadmap item describes, at the one shared breakpoint (768px) shell.js.mustache,
 * business-ui-style.mustache and workbench-page.html.mustache all key off. This does not replace a
 * live browser routine at 390px/768px (out of scope for a template-source test -- see the item's
 * own "done when"), but it does fail the moment a future edit silently drops one of these rules
 * from the template that emits it, which a browser routine run once at closing time would not
 * catch on its own.
 */
public class ResponsiveShellGridFormWorkbenchTest {

    private static final TemplateEngine TEMPLATES = new TemplateEngine("npdev-templates/");

    @Test
    @DisplayName("shell.js.mustache: the sidebar drawer is fully wired -- backdrop, dismiss, Escape, resize")
    void shellDrawerIsFullyWired() {
        String js = TEMPLATES.render("shell.js.mustache", Map.of());

        assertTrue(js.contains("var MOBILE_BREAKPOINT_PX = 768;"),
                "expected the one shared small-viewport threshold to stay 768:\n(not found)");

        // The backdrop element must actually be created and appended, not just styled in the
        // injected CSS -- a previous pass left CSS for `.npdev-shell-sidebar-backdrop` with no
        // matching DOM element anywhere, so the rule matched nothing.
        assertTrue(js.contains("sidebarBackdrop.className = \"npdev-shell-sidebar-backdrop\""),
                "expected paintSkeleton() to create the backdrop element:\n" + excerpt(js, "function paintSkeleton"));
        assertTrue(js.contains("bodyRow.appendChild(sidebarBackdrop)"),
                "expected the backdrop to actually be mounted into the body row");
        assertTrue(js.contains("sidebarBackdrop.addEventListener(\"click\", closeMobileSidebar)"),
                "expected clicking the backdrop to dismiss the drawer");

        // closeMobileSidebar existed in a previous pass but was dead code (defined, never called).
        // It must now have real callers beyond its own definition.
        int calls = countOccurrences(js, "closeMobileSidebar(") - 1; // exclude the `function closeMobileSidebar(` declaration
        assertTrue(calls >= 3,
                "expected closeMobileSidebar() to be called from the backdrop, Escape handler, resize "
                        + "listener and nav-link click (found " + calls + " call sites beyond its declaration)");

        assertTrue(js.contains("event.key === \"Escape\""),
                "expected an Escape key handler to dismiss the mobile drawer");
        assertTrue(js.contains("window.addEventListener(\"resize\""),
                "expected a resize listener so a drawer left open can't survive a resize back to desktop width");

        assertTrue(js.contains("sidebarToggle.setAttribute(\"aria-controls\", \"sideNav\")"),
                "expected the sidebar toggle to declare aria-controls for the drawer it operates");
    }

    @Test
    @DisplayName("business-ui-style.mustache: grid collapses to cards, forms stack single-column, touch targets grow, at 768px")
    void businessUiStyleHasMobileRules() {
        String css = TEMPLATES.render("business-ui-style.mustache", Map.of());

        assertTrue(css.contains("@media (max-width: 768px)"),
                "expected the single shared 768px breakpoint (roadmap's premise of exactly one "
                        + "breakpoint at 760px was true before this item -- it is now 768px, matching "
                        + "shell.js.mustache and workbench-page.html.mustache)");
        assertFalse(css.contains("@media (max-width: 760px)"),
                "the old 760px breakpoint must not linger alongside the new 768px one");

        // Grid -> cards: the header row is hidden (except the bulk-select column, R7.8) and each
        // data cell recovers its column label via data-label.
        assertTrue(css.contains(".records tbody td::before") && css.contains("content: attr(data-label)"),
                "expected the card-collapse rule to read each cell's data-label attribute");
        assertTrue(css.contains(".records thead th:not(.bulk-select-col)"),
                "expected the select-all checkbox to stay reachable while field headers hide");

        // Forms stack single column, including against the inline gridTemplateColumns a
        // model-declared formColumns>1 sets in business-ui-app.mustache's openForm().
        assertTrue(css.contains(".form-grid") && css.contains("grid-template-columns: 1fr !important"),
                "expected .form-grid to force single-column even against an inline multi-column style");

        // Touch targets: >=44px on the controls that declare a smaller min-height via a class
        // selector (which would otherwise outrank a bare `button`/`input` rule on specificity).
        assertTrue(css.contains("min-height: 44px"), "expected a 44px touch-target rule in the mobile block");
        assertTrue(css.contains(".form-tab,") || css.contains(".form-tab "),
                "expected .form-tab to be named explicitly in the touch-target override (class beats tag on specificity)");
    }

    @Test
    @DisplayName("business-ui-app.mustache: renderTable() labels each cell for the mobile card view")
    void renderTableEmitsDataLabel() {
        String app = TEMPLATES.render("business-ui-app.mustache", Map.of());
        int at = app.indexOf("function renderTable(concept, panel)");
        assertTrue(at >= 0, "expected renderTable() to still exist");
        // Bounded at the next top-level function (renderPager immediately follows renderTable in
        // source), not a fixed character window -- renderTable itself runs to roughly 6.5KB
        // including the R7.8 bulk-select-column block ahead of the per-field loop, which a
        // narrower fixed window silently cut off before reaching the data-label line at all.
        int nextFn = app.indexOf("function renderPager(", at);
        assertTrue(nextFn > at, "expected renderPager() to still immediately follow renderTable()");
        String region = app.substring(at, nextFn);
        assertTrue(region.contains("td.setAttribute(\"data-label\""),
                "expected renderTable() to stamp each field cell with data-label for the CSS "
                        + "card-collapse rule to read:\n" + region);
    }

    @Test
    @DisplayName("workbench-page.html.mustache: bands scroll independently and stack on mobile")
    void workbenchBandsScrollIndependently() {
        String page = TEMPLATES.render("workbench-page.html.mustache", Map.of());

        assertTrue(page.contains(".gwrap-scroll{"),
                "expected a per-band scroll container so a tall band no longer grows the whole page");
        assertTrue(page.contains("max-height:280px;overflow:auto"),
                "expected the scroll container to actually bound height and scroll");
        assertTrue(page.contains("var gtWrap = el('<div class=\"gwrap-scroll\"></div>'); gtWrap.append(gt); g.append(gtWrap);"),
                "expected the band table to actually be wrapped in the scroll container, not just styled in CSS with nothing using it");

        assertTrue(page.contains("@media (max-width:768px)"),
                "expected the workbench to share the platform's 768px breakpoint");
        assertTrue(page.contains(".grids{flex-direction:column"),
                "expected side-by-side bands to stack vertically on mobile");
        assertTrue(page.contains("form.grid2{grid-template-columns:auto 1fr}"),
                "expected the header form to collapse from its 4-column desktop layout to single-column on mobile");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }

    private static String excerpt(String source, String marker) {
        int at = source.indexOf(marker);
        if (at < 0) {
            return "(marker not found: " + marker + ")";
        }
        return source.substring(at, Math.min(source.length(), at + 600));
    }
}
