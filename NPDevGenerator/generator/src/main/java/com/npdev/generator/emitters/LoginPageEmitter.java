package com.npdev.generator.emitters;

import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.util.Map;

/**
 * SEC-11 (NPDEV_MEGA_ROADMAP.md Session 3b): emits {@code static/login.html} for every jwt-mode
 * app -- the "Sign in / Create account" screen the shell's {@code auth.loginPath} redirect sends
 * unauthenticated visitors to, offering both the existing username/password login and a
 * "Continue with ..." button per currently-configured external identity provider (Google, GitHub).
 *
 * <p>This is the platform's own answer to the roadmap's "a generated sample app offers 'Continue
 * with Google' on both signup and login", delivered as an emitted artifact rather than per-app
 * hand-written HTML: the page is fixed content (only the app name differs), so it stays under the
 * deterministic-generation gate. The page never knows at generation time which providers a
 * deployment will configure -- it renders one button per entry {@code GET /api/auth/oauth/config}
 * reports at RUNTIME, so an app that never provisioned any OAuth client gets the same login page
 * with the credential store untouched and no buttons at all.
 *
 * <p>The page stores a successful session under the same localStorage keys {@code shell.js}'s
 * {@code findToken()} reads, so a login lands directly in the SPA's own session. Any {@code ?error=}
 * a provider callback redirected with is mapped to provider-neutral business language, since the
 * error code alone never says which provider triggered it.
 *
 * <p>No network secrets ever reach this page: it only ever calls {@code /api/auth/oauth/config}
 * (provider id/label/authorizePath, no secret) and the standard {@code /api/auth/login}.
 */
public final class LoginPageEmitter extends AbstractEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/static/login.html";

    public LoginPageEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    /** Emit the page. {@code jwtMode == false} is a no-op (apiKey apps have no login screen). */
    public void emit(String appName, boolean jwtMode) {
        if (!jwtMode) {
            return;
        }
        writer.writeRelative(RELATIVE_PATH, render(Map.of(
                "appName", appName == null || appName.isBlank() ? "NPDev Generated App" : appName.trim()
        )));
    }

    private String render(Map<String, Object> view) {
        return templates.render("login-page.html.mustache", view);
    }
}