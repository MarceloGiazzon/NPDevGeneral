package com.npdev.generator;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TRUTH-1: the Box Inspector's truth rollup lives in the carved {@code views/admin-panels.js} module.
 * The browser behaviour is proven by {@code truth-rollup-verify-routine.json} against a booted app; this
 * pins what a static check can: the rollup is fetched as display-only data (a failure never fails the
 * panel), every value reaches the DOM through textContent (no markup injection from evidence text), and
 * each evidence row keeps the hooks the routine drives.
 */
class AdminPanelsTruthRollupAssetTest {

    private static String adminPanels() throws IOException {
        try (InputStream in = AdminPanelsTruthRollupAssetTest.class.getClassLoader()
                .getResourceAsStream("npdev-templates/static-assets/business-ui/views/admin-panels.js")) {
            assertNotNull(in, "admin-panels.js must ship with the generator");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void truthRollupIsFetchedAsDisplayOnlyData() throws IOException {
        String js = adminPanels();
        assertTrue(js.contains("\"/api/admin/box/truth\""), "the panel reads the rollup endpoint");
        Matcher fetch = Pattern.compile("\"/api/admin/box/truth\"[^;]*?\\.catch\\(function \\(\\) \\{ return null; \\}\\)").matcher(js);
        assertTrue(fetch.find(), "a failed rollup fetch must degrade to null (unknown), never fail the panel");
        assertTrue(js.contains("Truth rollup unavailable: every level is unknown."));
        assertTrue(js.contains("separate S0-S8 ladder"), "the promotion stage is shown as its own ladder, evidence only");
    }

    @Test
    void evidenceTextNeverReachesTheDomAsMarkup() throws IOException {
        String js = adminPanels();
        // the only innerHTML write in the truth code clears a container; evidence text goes through textContent
        Matcher writes = Pattern.compile("innerHTML\\s*=\\s*([^;]+);").matcher(js);
        int truthSection = js.indexOf("function openEvidence");
        while (writes.find()) {
            if (writes.start() > truthSection && writes.start() < js.indexOf("function renderBoxViewPanel")) {
                assertTrue(writes.group(1).trim().equals("\"\""),
                        "innerHTML may only be cleared in the truth rollup code, got: " + writes.group());
            }
        }
        assertFalse(js.contains("insertAdjacentHTML"), "no markup injection");
    }

    @Test
    void rowsKeepTheHooksTheBrowserRoutineDrives() throws IOException {
        String js = adminPanels();
        for (String hook : new String[] {"truth-rollup", "truth-headline", "truth-promotion", "data-concept",
                "data-evidence-id", "data-evidence-status", "evidence-group", "truth-evidence-detail",
                "Supports level", "Lowest level in this app"}) {
            assertTrue(js.contains(hook), "admin-panels.js must keep the hook: " + hook);
        }
    }
}
