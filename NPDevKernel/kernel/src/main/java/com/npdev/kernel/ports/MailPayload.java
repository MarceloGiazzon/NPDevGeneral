package com.npdev.kernel.ports;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LNCH-11: parses the untyped {@code Map} payload a flow/procedure capability-call step passes
 * (e.g. {@code {"to":"a@b.com","subject":"Hi ${name}","body":"...","templateVars":{"name":"Ada"}}})
 * into a {@link MailMessage}. Shared by mail-inproc and mail-smtp so the payload shape only needs
 * to be defined once.
 */
public final class MailPayload {

    private MailPayload() {
    }

    /**
     * A flow's capabilityCall step can only pass positional value-refs (no inline string
     * templating in the DSL itself) -- so a flow author who wants literal to/subject/body values
     * pulled from prior-step fields uses this positional form (to, subject, body[, templateVars])
     * instead of building a single payload map, which the DSL genuinely cannot construct inline.
     */
    public static MailMessage parse(List<Object> args) {
        if (args == null || args.isEmpty()) {
            return new MailMessage(List.of(), "", "", Map.of());
        }
        if (args.size() == 1) {
            return parse(args.get(0));
        }
        List<String> to = toStringList(args.get(0));
        String subject = args.size() > 1 && args.get(1) != null ? String.valueOf(args.get(1)) : "";
        String body = args.size() > 2 && args.get(2) != null ? String.valueOf(args.get(2)) : "";
        Map<String, Object> templateVars = args.size() > 3 && args.get(3) instanceof Map<?, ?> varsMap
                ? normalizeVars(varsMap)
                : Map.of();
        return new MailMessage(to, subject, body, templateVars);
    }

    public static MailMessage parse(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return new MailMessage(List.of(), "", "", Map.of());
        }
        List<String> to = toStringList(map.get("to"));
        String subject = map.get("subject") == null ? "" : String.valueOf(map.get("subject"));
        String body = map.get("body") == null ? "" : String.valueOf(map.get("body"));
        Map<String, Object> templateVars = map.get("templateVars") instanceof Map<?, ?> varsMap
                ? normalizeVars(varsMap)
                : Map.of();
        return new MailMessage(to, subject, body, templateVars);
    }

    private static List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
            return out;
        }
        return List.of(String.valueOf(value));
    }

    private static Map<String, Object> normalizeVars(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() != null) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}
