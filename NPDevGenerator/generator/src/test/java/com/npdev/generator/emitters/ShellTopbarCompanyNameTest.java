package com.npdev.generator.emitters;

import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WMS-9 N4 (company-name half; the clock half shipped earlier as
 * {@code BusinessUiEmitterShellVersionTest.shellTopbarCarriesALiveClock}). The platform has no
 * generic notion of "the one company the user is logged into" -- tenancy is row-level (tenant_id),
 * not a SaaS-style one-org-per-session model -- so the shell cannot hard-code a concept/field name.
 * Opt-in is a RESERVED query name, "ShellCompanyName", the same idiom {@code workspace::Menu}
 * already uses for reserved-name platform overlays: an app that declares this query gets its first
 * row rendered in the topbar; an app that doesn't gets nothing, silently.
 */
class ShellTopbarCompanyNameTest {

    private static final TemplateEngine TEMPLATES = new TemplateEngine("npdev-templates/");

    @Test
    @DisplayName("shell.js.mustache: renders a topbar element fed by the reserved ShellCompanyName query, best-effort")
    void shellFetchesAndRendersShellCompanyNameQuery() {
        String js = TEMPLATES.render("shell.js.mustache", Map.of());

        assertTrue(js.contains("company.className = \"npdev-shell-company\";"),
                "expected buildTopbar() to create the company-name element");
        assertTrue(js.contains("bar.appendChild(company);"),
                "expected the company-name element to actually be mounted into the topbar");

        int fnStart = js.indexOf("function applyCompanyName()");
        assertTrue(fnStart >= 0, "expected an applyCompanyName() function");
        int fnEnd = js.indexOf("\n  }", fnStart);
        String fnBody = js.substring(fnStart, fnEnd);

        assertTrue(fnBody.contains("/api/queries/ShellCompanyName"),
                "expected the reserved query name ShellCompanyName to be fetched:\n" + fnBody);
        assertTrue(fnBody.contains(".catch("),
                "expected the fetch to be best-effort -- a 404 (query not declared) or any other "
                        + "failure must not surface as a visible error, since this is optional cosmetic chrome");

        int identityCall = js.indexOf("applyIdentity();");
        int companyCall = js.indexOf("applyCompanyName();");
        assertTrue(identityCall >= 0 && companyCall > identityCall && companyCall - identityCall < 40,
                "expected applyCompanyName() to run immediately alongside applyIdentity() once auth state is known");
    }
}
