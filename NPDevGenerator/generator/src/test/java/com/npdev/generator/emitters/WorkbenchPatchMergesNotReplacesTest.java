package com.npdev.generator.emitters;

import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-225: a workbench action with neither {@code applyTo} nor {@code afterAction} declared (every
 * "propose" import wizard -- ParseNfeProcedure, ParseRomaneioProcedure, ImportarContagemProcedure,
 * AnalisarArquivoRecebimentoDraftProcedure, none of which declare either) falls through to
 * {@code store.patch(res.b)}. The old implementation did {@code draft = clone(newData || {})} --
 * a WHOLESALE replace -- which silently wiped every header field the procedure's small ad hoc
 * result didn't happen to name (id, entidadeId, situacao, an already-picked reference), and left
 * every freshly-parsed row with no {@code id} at all, rendering a broken
 * {@code data-fkey="...undefined..."} cell that could never save. Found live on
 * RomaneioAggregateWorkbench (WMS-9), reproduced against the same class of action platform-wide.
 * This asserts the fixed template actually merges instead of replacing, and actually assigns an id
 * to any row (or band row) that arrives without one.
 */
class WorkbenchPatchMergesNotReplacesTest {

    private static final TemplateEngine TEMPLATES = new TemplateEngine("npdev-templates/");

    @Test
    @DisplayName("workbench-page.html.mustache: store.patch merges a procedure result onto the draft instead of replacing it")
    void patchMergesOntoExistingDraft() {
        String js = TEMPLATES.render("workbench-page.html.mustache", Map.of());

        assertTrue(js.contains("draft = Object.assign({}, draft, clone(newData || {}));"),
                "expected patch() to merge the incoming result onto the existing draft, not replace it wholesale");
        assertFalse(js.contains("draft = clone(newData || {});"),
                "the old wholesale-replace assignment must be gone, not merely supplemented");
    }

    @Test
    @DisplayName("workbench-page.html.mustache: store.patch assigns a fresh id to any row that arrives without one")
    void patchAssignsIdsToRowsMissingThem() {
        String js = TEMPLATES.render("workbench-page.html.mustache", Map.of());

        // Locate the patch() function body specifically, so this doesn't just match addRow's own
        // pre-existing `row.id = genId()` (a different code path, already correct).
        int patchStart = js.indexOf("patch: function (newData)");
        assertTrue(patchStart >= 0, "expected to find the patch() method definition");
        int patchEnd = js.indexOf("\n      },", patchStart);
        assertTrue(patchEnd > patchStart, "expected to find the end of the patch() method body");
        String patchBody = js.substring(patchStart, patchEnd);

        assertTrue(patchBody.contains("row.id = genId();"),
                "expected patch() to assign a fresh client-side id to a top-level row missing one:\n" + patchBody);
        assertTrue(patchBody.contains("br.id = genId();"),
                "expected patch() to assign a fresh client-side id to a band row missing one:\n" + patchBody);
    }
}
