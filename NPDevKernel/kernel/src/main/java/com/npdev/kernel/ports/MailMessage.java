package com.npdev.kernel.ports;

import java.util.List;
import java.util.Map;

/**
 * LNCH-11: a normalized email send request. {@code templateVars} substitutes {@code ${name}}
 * placeholders in {@code subject}/{@code body}/{@code htmlBody} via {@link MailTemplateRenderer} --
 * adapters render before dispatch, so both mail-inproc's recorded delivery and mail-smtp's actual
 * outbound message carry the already-substituted text.
 *
 * <p>R6.3 (RUN-18): {@code htmlBody} and {@code attachments} are optional MIME extensions, additive
 * to the original plain-text-only shape. Both default to "absent" ({@code null} / empty list), and
 * an adapter that sees neither must behave byte-for-byte as before -- {@code mail-smtp} still calls
 * the single {@code setText(...)} it always did. The 4-arg constructor below is preserved so every
 * pre-R6.3 caller (mail-inproc, mail-smtp, and both their test suites) keeps compiling unchanged.</p>
 */
public record MailMessage(
        List<String> to,
        String subject,
        String body,
        Map<String, Object> templateVars,
        String htmlBody,
        List<MailAttachment> attachments
) {
    public MailMessage {
        to = to == null ? List.of() : List.copyOf(to);
        templateVars = templateVars == null ? Map.of() : Map.copyOf(templateVars);
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
    }

    /** Pre-R6.3 shape: plain-text-only, no attachments. */
    public MailMessage(List<String> to, String subject, String body, Map<String, Object> templateVars) {
        this(to, subject, body, templateVars, null, List.of());
    }

    public boolean hasHtmlBody() {
        return htmlBody != null && !htmlBody.isBlank();
    }

    public MailMessage withRenderedTemplate() {
        return new MailMessage(
                to,
                MailTemplateRenderer.render(subject, templateVars),
                MailTemplateRenderer.render(body, templateVars),
                templateVars,
                hasHtmlBody() ? MailTemplateRenderer.render(htmlBody, templateVars) : htmlBody,
                attachments
        );
    }
}
