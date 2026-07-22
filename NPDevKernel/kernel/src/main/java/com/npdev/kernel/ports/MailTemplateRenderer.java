package com.npdev.kernel.ports;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LNCH-11: substitutes {@code ${varName}} placeholders in an email subject/body with values from
 * a flat vars map. Deliberately its own (tiny) implementation rather than reusing
 * {@code SeedDataService}'s {@code $varName} substitution -- that mechanism replaces a JSON node's
 * entire value when it equals a bare "$varName" token, which is a different shape of problem than
 * interpolating named placeholders inside a larger natural-language string.
 */
public final class MailTemplateRenderer {
    private static final Pattern VAR = Pattern.compile("\\$\\{([a-zA-Z_][a-zA-Z0-9_.]*)\\}");

    private MailTemplateRenderer() {
    }

    public static String render(String template, Map<String, Object> vars) {
        if (template == null || template.isEmpty() || vars == null || vars.isEmpty()) {
            return template;
        }
        Matcher matcher = VAR.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            Object value = vars.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
