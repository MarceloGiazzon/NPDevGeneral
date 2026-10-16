package com.npdev.generator.emitters;

import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.Map;

/**
 * SEC-4: emits {@code static/reset-password.html} for every jwt-mode app -- the logged-OUT
 * "forgot my password" screen the login page's "Forgot password?" link opens. It posts to
 * {@code PasswordResetController}'s request/confirm endpoints (NPDevRuntimeHost): pane A asks for a
 * mailed single-use code, pane B (opened from the mailed link, {@code ?token=...}, or by typing the
 * code) sets the new password. Mirrors {@link ChangePasswordPageEmitter}: same jwt-mode gating, same
 * fixed-content shape so generation stays deterministic, same {@code appName} view field.
 */
public final class ResetPasswordPageEmitter extends AbstractEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/static/reset-password.html";

    public ResetPasswordPageEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    /** Emit the page. {@code jwtMode == false} is a no-op (apiKey apps have no user credentials to reset). */
    public void emit(String appName, boolean jwtMode) {
        if (!jwtMode) {
            return;
        }
        writer.writeRelative(RELATIVE_PATH, render(Map.of(
                "appName", appName == null || appName.isBlank() ? "NPDev Generated App" : appName.trim()
        )));
    }

    private String render(Map<String, Object> view) {
        return templates.render("reset-password-page.html.mustache", view);
    }
}
