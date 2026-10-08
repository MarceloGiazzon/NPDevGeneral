package com.npdev.kernel.ports;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mail port's own value types (LNCH-11 / R6.3): null-safe defensive copies, template rendering
 * across subject/body/htmlBody, and the attachment invariants both mail-inproc and mail-smtp rely on.
 */
class MailMessageTest {

    @Test
    void legacyConstructorDefaultsToPlainTextWithNoAttachments() {
        MailMessage message = new MailMessage(null, "s", "b", null);

        assertTrue(message.to().isEmpty());
        assertTrue(message.templateVars().isEmpty());
        assertTrue(message.attachments().isEmpty());
        assertNull(message.htmlBody());
        assertFalse(message.hasHtmlBody());
    }

    @Test
    void recipientsAreCopiedNotAliased() {
        List<String> to = new ArrayList<>(List.of("a@example.test"));
        MailMessage message = new MailMessage(to, "s", "b", Map.of());
        to.add("b@example.test");

        assertEquals(List.of("a@example.test"), message.to());
        assertThrows(UnsupportedOperationException.class, () -> message.to().add("c@example.test"));
    }

    @Test
    void withRenderedTemplateSubstitutesSubjectBodyAndHtml() {
        MailMessage message = new MailMessage(
                List.of("a@example.test"),
                "Trade ${tradeId}",
                "Hi ${name}, missing=[${absent}]",
                Map.of("tradeId", 42, "name", "Tito $1"),
                "<p>${name}</p>",
                null);

        MailMessage rendered = message.withRenderedTemplate();

        assertEquals("Trade 42", rendered.subject());
        assertEquals("Hi Tito $1, missing=[]", rendered.body());
        assertEquals("<p>Tito $1</p>", rendered.htmlBody());
        assertEquals(message.to(), rendered.to());
    }

    @Test
    void blankHtmlBodyIsNotRenderedAndNotCountedAsHtml() {
        MailMessage message = new MailMessage(List.of(), "${x}", "${x}", Map.of("x", "y"), "  ", List.of());

        MailMessage rendered = message.withRenderedTemplate();

        assertFalse(message.hasHtmlBody());
        assertEquals("  ", rendered.htmlBody());
        assertEquals("y", rendered.subject());
    }

    @Test
    void renderWithoutVarsReturnsTemplateUnchanged() {
        String template = "Hello ${name}";
        assertSame(template, MailTemplateRenderer.render(template, Map.of()));
        assertNull(MailTemplateRenderer.render(null, Map.of("name", "x")));
    }

    @Test
    void attachmentRejectsBlankFilenameOrContentTypeAndNullBytes() {
        byte[] bytes = {1, 2};
        assertThrows(IllegalArgumentException.class, () -> new MailAttachment(" ", "text/plain", bytes));
        assertThrows(IllegalArgumentException.class, () -> new MailAttachment(null, "text/plain", bytes));
        assertThrows(IllegalArgumentException.class, () -> new MailAttachment("a.txt", "", bytes));
        assertThrows(IllegalArgumentException.class, () -> new MailAttachment("a.txt", null, bytes));
        assertThrows(NullPointerException.class, () -> new MailAttachment("a.txt", "text/plain", null));

        MailAttachment ok = new MailAttachment("a.txt", "text/plain", bytes);
        MailMessage message = new MailMessage(List.of(), "s", "b", Map.of(), null, List.of(ok));
        assertEquals(List.of(ok), message.attachments());
    }
}
