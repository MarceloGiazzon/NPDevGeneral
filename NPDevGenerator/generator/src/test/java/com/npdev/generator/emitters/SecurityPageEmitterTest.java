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

/** SEC-12: the account security screen is emitted only for jwt-mode apps and drives the MFA endpoints. */
class SecurityPageEmitterTest {

    @Test
    void jwtModeEmitsSecurityPage(@TempDir Path tempDir) throws Exception {
        Path out = emitPage(tempDir, "Security Demo", true);
        Path page = out.resolve("src/main/resources/static/security.html");
        assertTrue(Files.isRegularFile(page));
        String html = Files.readString(page);
        assertTrue(html.contains("Security Demo"), "the page must carry the app name");
        assertTrue(html.contains("/api/auth/mfa/status"));
        assertTrue(html.contains("/api/auth/mfa/enroll/start"));
        assertTrue(html.contains("/api/auth/mfa/enroll/confirm"));
        assertTrue(html.contains("/api/auth/mfa/disable"));
        assertTrue(html.contains("/api/auth/mfa/recovery-codes"));
        assertTrue(html.contains("X-NPDev-Request"), "state-changing calls must carry the CSRF header");
        assertTrue(html.contains("Two-step sign-in is not available in this app."));
        assertFalse(html.contains("sessionStorage"), "no session storage on the security page");
        assertTrue(html.indexOf("localStorage.setItem") == html.lastIndexOf("localStorage.setItem")
                        && html.contains("function storeToken(token)"),
                "the only web-storage write is the replacement session token; recovery codes and "
                        + "secrets must never be written to web storage");
        assertTrue(html.contains("/api/auth/sessions/revoke-others"), "sign out of all other devices");
        assertTrue(html.contains("Sign out of all other devices"));
        assertFalse(html.contains("<script src"), "no external scripts: generated apps work offline");
    }

    @Test
    void securityPageListsWhereYouAreSignedInWithARevokePerDevice(@TempDir Path tempDir) throws Exception {
        // SESS-2: the per-device list and its Revoke, driven by the sessions endpoints.
        String html = Files.readString(emitPage(tempDir, "Security Demo", true)
                .resolve("src/main/resources/static/security.html"));

        assertTrue(html.contains("Where you're signed in"), "the section heading");
        assertTrue(html.contains("api(\"GET\", \"/api/auth/sessions\")"), "the list is loaded from the sessions endpoint");
        assertTrue(html.contains("api(\"DELETE\", \"/api/auth/sessions/\" + encodeURIComponent(s.id))"),
                "a device is ended through the sessions endpoint, by its short handle");
        assertTrue(html.contains("(this device)") && html.contains("if (!s.current)"),
                "the current device is marked and offers no Revoke");
        assertFalse(html.contains("innerHTML"), "device names and addresses come from the server: textContent only");
        assertTrue(html.contains("X-NPDev-Request"), "the DELETE carries the CSRF header via the shared helper");
    }

    @Test
    void apiKeyModeEmitsNoSecurityPage(@TempDir Path tempDir) throws Exception {
        Path out = emitPage(tempDir, "Security Demo", false);
        assertFalse(Files.exists(out.resolve("src/main/resources/static/security.html")));
    }

    @Test
    void blankAppNameFallsBackToPlatformName(@TempDir Path tempDir) throws Exception {
        Path out = emitPage(tempDir, "   ", true);
        assertTrue(Files.readString(out.resolve("src/main/resources/static/security.html"))
                .contains("NPDev Generated App"));
    }

    private static Path emitPage(Path tempDir, String appName, boolean jwtMode) throws Exception {
        Path out = Files.createTempDirectory(tempDir, "npdev-security-page-out-");
        new SecurityPageEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(appName, jwtMode);
        return out;
    }
}
