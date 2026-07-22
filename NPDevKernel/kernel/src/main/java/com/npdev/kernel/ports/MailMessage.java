package com.npdev.kernel.ports;

import java.util.List;
import java.util.Map;

/**
 * LNCH-11: a normalized email send request. {@code templateVars} substitutes {@code ${name}}
 * placeholders in {@code subject}/{@code body} via {@link MailTemplateRenderer} -- adapters render
 * before dispatch, so both mail-inproc's recorded delivery and mail-smtp's actual outbound message
 * carry the already-substituted text.
 */
public record MailMessage(
        List<String> to,
        String subject,
        String body,
        Map<String, Object> templateVars
) {
    public MailMessage {
        to = to == null ? List.of() : List.copyOf(to);
        templateVars = templateVars == null ? Map.of() : Map.copyOf(templateVars);
    }

    public MailMessage withRenderedTemplate() {
        return new MailMessage(
                to,
                MailTemplateRenderer.render(subject, templateVars),
                MailTemplateRenderer.render(body, templateVars),
                templateVars
        );
    }
}
