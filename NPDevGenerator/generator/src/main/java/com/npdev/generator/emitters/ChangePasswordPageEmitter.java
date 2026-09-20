package com.npdev.generator.emitters;

import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.Map;

/**
 * WMS-9 N6 (NPDEV_MEGA_ROADMAP.md-adjacent platform-shell gap, not app-specific): emits
 * {@code static/change-password.html} for every jwt-mode app -- the logged-in self-service
 * "change my password" screen the shell topbar's "Alterar senha" link (see {@code
 * shell.js.mustache}'s {@code applyIdentity()}) points at. Mirrors {@link LoginPageEmitter}
 * exactly: same jwt-mode gating, same fixed-content-so-deterministic-generation-is-unaffected
 * shape, same {@code appName} view field.
 *
 * <p>{@link com.finalexec.auth.PasswordResetController} (NPDevRuntimeHost) is a logged-OUT
 * forgot-password/reset-token flow with no page of its own; this is the separate logged-in
 * counterpart, backed by a new {@code ChangePasswordController} taking the current password
 * instead of a mailed token.</p>
 */
public final class ChangePasswordPageEmitter extends AbstractEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/static/change-password.html";

    public ChangePasswordPageEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    /** Emit the page. {@code jwtMode == false} is a no-op (apiKey apps have no user session to
     * change the password of via this flow). */
    public void emit(String appName, boolean jwtMode) {
        if (!jwtMode) {
            return;
        }
        writer.writeRelative(RELATIVE_PATH, render(Map.of(
                "appName", appName == null || appName.isBlank() ? "NPDev Generated App" : appName.trim()
        )));
    }

    private String render(Map<String, Object> view) {
        return templates.render("change-password-page.html.mustache", view);
    }
}
