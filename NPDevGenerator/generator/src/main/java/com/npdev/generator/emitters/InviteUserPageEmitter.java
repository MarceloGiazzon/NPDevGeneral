package com.npdev.generator.emitters;

import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.Map;

/**
 * SEC-7: emits {@code static/invite-user.html} for every jwt-mode app -- the tenant-admin screen
 * for {@code POST /api/auth/invite} (SEC-6): e-mail, optional display name, role checkboxes loaded
 * at runtime from {@code GET /api/auth/assignable-roles}, and a resend form. Same jwt-mode gating
 * and fixed-content (deterministic) shape as {@link ChangePasswordPageEmitter}; the role list is
 * never baked in at generation time.
 */
public final class InviteUserPageEmitter extends AbstractEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/static/invite-user.html";

    public InviteUserPageEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    /** Emit the page. {@code jwtMode == false} is a no-op (apiKey apps have no user accounts to invite). */
    public void emit(String appName, boolean jwtMode) {
        if (!jwtMode) {
            return;
        }
        writer.writeRelative(RELATIVE_PATH, templates.render("invite-user-page.html.mustache", Map.of(
                "appName", appName == null || appName.isBlank() ? "NPDev Generated App" : appName.trim()
        )));
    }
}
