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
 * WMS-9 N6: the logged-in self-service "change my password" screen -- emitted only for jwt-mode
 * apps (an apiKey app's token is a static dev key with no per-user credential to change), same
 * gating shape as {@link LoginPageEmitterTest}'s login page. Posts to the new
 * {@code ChangePasswordController} (NPDevRuntimeHost) rather than a mailed-token flow.
 */
class ChangePasswordPageEmitterTest {

    @Test
    void jwtModeEmitsChangePasswordPage(@TempDir Path tempDir) throws Exception {
        Path out = emitPage(tempDir, "Change Password Demo", true);
        Path page = out.resolve("src/main/resources/static/change-password.html");
        assertTrue(Files.isRegularFile(page));
        String html = Files.readString(page);
        assertTrue(html.contains("Change Password Demo"), "the page must carry the app name");
        assertTrue(html.contains("/api/auth/change-password"), "the form must post to the self-service endpoint");
        assertTrue(html.contains("oldPassword") && html.contains("newPassword"),
                "the form must take the current password, not a mailed token");
        assertTrue(html.contains("npdev.shell.token"),
                "a successful change must refresh the shell's own token key with the response's fresh token");
    }

    @Test
    void apiKeyModeEmitsNoChangePasswordPage(@TempDir Path tempDir) throws Exception {
        Path out = emitPage(tempDir, "Change Password Demo", false);
        assertFalse(Files.exists(out.resolve("src/main/resources/static/change-password.html")),
                "apiKey apps have no per-user session/credential for this flow; no page is emitted");
    }

    @Test
    void blankAppNameFallsBackToPlatformName(@TempDir Path tempDir) throws Exception {
        Path out = emitPage(tempDir, "   ", true);
        assertTrue(Files.readString(out.resolve("src/main/resources/static/change-password.html"))
                .contains("NPDev Generated App"));
    }

    private static Path emitPage(Path tempDir, String appName, boolean jwtMode) throws Exception {
        Path out = Files.createTempDirectory("npdev-change-password-page-out-");
        new ChangePasswordPageEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(appName, jwtMode);
        return out;
    }
}
