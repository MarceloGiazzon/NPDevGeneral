package com.npdev.generator.emitters;

import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-4: the logged-out "forgot password" screen -- emitted only for jwt-mode apps (an apiKey app
 * has no user credentials to reset) and wired to PasswordResetController's request/confirm endpoints.
 */
class ResetPasswordPageEmitterTest {

    private static final String PAGE = "src/main/resources/static/reset-password.html";

    @Test
    void jwtModeEmitsResetPasswordPage(@TempDir Path tempDir) throws Exception {
        Path out = emitPage("Reset Demo", true);
        Path page = out.resolve(PAGE);
        assertTrue(Files.isRegularFile(page));
        String html = Files.readString(page);
        assertTrue(html.contains("Reset Demo"), "the page must carry the app name");
        assertTrue(html.contains("/api/auth/password-reset/request"), "pane A must post the username request");
        assertTrue(html.contains("/api/auth/password-reset/confirm"), "pane B must post the code and new password");
        assertTrue(html.contains("URLSearchParams") && html.contains("token"),
                "the mailed link's ?token= must open the new-password pane");
        assertTrue(html.contains("X-NPDev-Request"), "first-party calls send the CSRF header (SEC-1)");
        assertTrue(html.contains("password_too_short") && html.contains("invalid_or_expired_token"),
                "server error codes must map to readable messages");
    }

    @Test
    void apiKeyModeEmitsNoResetPasswordPage(@TempDir Path tempDir) throws Exception {
        Path out = emitPage("Reset Demo", false);
        assertFalse(Files.exists(out.resolve(PAGE)), "apiKey apps have no user credentials to reset");
    }

    @Test
    void blankAppNameFallsBackToPlatformName(@TempDir Path tempDir) throws Exception {
        Path out = emitPage("   ", true);
        assertTrue(Files.readString(out.resolve(PAGE)).contains("NPDev Generated App"));
    }

    private static Path emitPage(String appName, boolean jwtMode) throws Exception {
        Path out = Files.createTempDirectory("npdev-reset-password-page-out-");
        new ResetPasswordPageEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(appName, jwtMode);
        return out;
    }
}
