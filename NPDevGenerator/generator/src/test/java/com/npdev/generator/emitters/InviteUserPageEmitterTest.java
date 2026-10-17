package com.npdev.generator.emitters;

import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SEC-7: the tenant-admin "Invite user" page is emitted for jwt-mode apps only. */
class InviteUserPageEmitterTest {

    private static final String PAGE = "src/main/resources/static/invite-user.html";

    @Test
    void jwtModeEmitsInviteUserPage() throws Exception {
        Path out = emitPage("Invite Demo", true);
        Path page = out.resolve(PAGE);
        assertTrue(Files.isRegularFile(page));
        String html = Files.readString(page);
        assertTrue(html.contains("Invite Demo"), "the page must carry the app name");
        assertTrue(html.contains("/api/auth/invite"), "the form must post to the invitation endpoint");
        assertTrue(html.contains("/api/auth/assignable-roles"), "role checkboxes must be loaded at runtime");
        assertTrue(html.contains("/api/auth/users/"), "the page must offer the resend endpoint");
        assertTrue(html.contains("Only administrators can invite users."));
        assertFalse(html.contains("{{"), "no unrendered mustache tag may remain");
    }

    @Test
    void apiKeyModeEmitsNoInviteUserPage() throws Exception {
        Path out = emitPage("Invite Demo", false);
        assertFalse(Files.exists(out.resolve(PAGE)), "apiKey apps have no user accounts to invite");
    }

    @Test
    void blankAppNameFallsBackToPlatformName() throws Exception {
        assertTrue(Files.readString(emitPage("   ", true).resolve(PAGE)).contains("NPDev Generated App"));
    }

    private static Path emitPage(String appName, boolean jwtMode) throws Exception {
        Path out = Files.createTempDirectory("npdev-invite-user-page-out-");
        new InviteUserPageEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(appName, jwtMode);
        return out;
    }
}
