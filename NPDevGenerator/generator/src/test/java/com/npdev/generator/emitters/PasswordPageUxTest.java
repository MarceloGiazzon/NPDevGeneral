package com.npdev.generator.emitters;

import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sign-in UX on the password pages: show-password toggle, Caps Lock hint, in-flight double-submit
 * guard, and (login only) the same-origin-relative {@code ?next=} return path.
 */
class PasswordPageUxTest {

    private static final String LOGIN = "src/main/resources/static/login.html";
    private static final String RESET = "src/main/resources/static/reset-password.html";
    private static final String CHANGE = "src/main/resources/static/change-password.html";

    private static Path out() throws Exception {
        Path out = Files.createTempDirectory("npdev-password-ux-out-");
        TemplateEngine engine = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new LoginPageEmitter(engine, writer).emit("Ux Demo", true);
        new ResetPasswordPageEmitter(engine, writer).emit("Ux Demo", true);
        new ChangePasswordPageEmitter(engine, writer).emit("Ux Demo", true);
        return out;
    }

    private static void assertCommonUx(String html, String page) {
        assertTrue(html.contains("enhancePasswordField") && html.contains("input[type=\"password\"]"),
                page + ": every password input is enhanced");
        assertTrue(html.contains("aria-controls") && html.contains("aria-pressed") && html.contains("pw-toggle"),
                page + ": show-password toggle is an accessible button");
        assertTrue(html.contains("input.type = reveal ? \"text\" : \"password\""), page + ": toggle flips the input type");
        assertTrue(html.contains("getModifierState(\"CapsLock\")") && html.contains("aria-live"),
                page + ": polite Caps Lock hint");
        assertTrue(html.contains("function setBusy") && html.contains("aria-busy"),
                page + ": in-flight guard sets aria-busy");
        assertTrue(html.contains("if (btn.disabled) { return; }") || html.contains("if (submitBtn.disabled) { return; }")
                        || html.contains("if (signinBtn.disabled) { return; }"),
                page + ": a second submit while busy is ignored");
        assertTrue(html.contains("setBusy(") && html.contains(", false)"), page + ": busy state is restored");
        assertFalse(html.contains("{{{") || html.contains("{{ "), page + ": no stray mustache in script");
    }

    @Test
    void loginPage() throws Exception {
        String html = Files.readString(out().resolve(LOGIN));
        assertCommonUx(html, "login");
        assertTrue(html.contains("Signing in\\u2026"));
        assertTrue(html.contains("autocomplete=\"current-password\""), "autocomplete is kept");
        assertTrue(html.contains("params.get(\"next\")") && html.contains("value.charAt(0) !== \"/\"")
                && html.contains("value.charAt(1) === \"/\""), "?next= only honours same-origin relative paths");
        assertTrue(html.contains("window.location.href = returnUrl;"));
    }

    @Test
    void resetPage() throws Exception {
        String html = Files.readString(out().resolve(RESET));
        assertCommonUx(html, "reset");
        assertTrue(html.contains("Saving\\u2026"));
        assertTrue(html.contains("autocomplete=\"new-password\""));
    }

    @Test
    void changePage() throws Exception {
        String html = Files.readString(out().resolve(CHANGE));
        assertCommonUx(html, "change");
        assertTrue(html.contains("Salvando\\u2026"));
        assertTrue(html.contains("autocomplete=\"current-password\""));
    }
}
