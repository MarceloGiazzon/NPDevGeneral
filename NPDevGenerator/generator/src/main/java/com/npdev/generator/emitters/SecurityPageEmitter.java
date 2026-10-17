package com.npdev.generator.emitters;

import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.Map;

/**
 * SEC-12: emits {@code static/security.html} for every jwt-mode app -- the signed-in "account
 * security" screen where a user turns the authenticator-app second factor on or off and views or
 * regenerates recovery codes (backed by {@code MfaController}'s {@code /api/auth/mfa/**}). Same
 * shape as {@link ChangePasswordPageEmitter}: jwt-mode gating, fixed content plus the
 * {@code appName} view field, so deterministic generation is unaffected.
 */
public final class SecurityPageEmitter extends AbstractEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/static/security.html";

    public SecurityPageEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    /** Emit the page. {@code jwtMode == false} is a no-op (apiKey apps have no user accounts). */
    public void emit(String appName, boolean jwtMode) {
        if (!jwtMode) {
            return;
        }
        writer.writeRelative(RELATIVE_PATH, templates.render("security-page.html.mustache", Map.of(
                "appName", appName == null || appName.isBlank() ? "NPDev Generated App" : appName.trim()
        )));
    }
}
