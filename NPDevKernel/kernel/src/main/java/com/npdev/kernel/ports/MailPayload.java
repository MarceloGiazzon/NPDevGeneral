package com.npdev.kernel.ports;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LNCH-11: parses the untyped {@code Map} payload a flow/procedure capability-call step passes
 * (e.g. {@code {"to":"a@b.com","subject":"Hi ${name}","body":"...","templateVars":{"name":"Ada"}}})
 * into a {@link MailMessage}. Shared by mail-inproc and mail-smtp so the payload shape only needs
 * to be defined once.
 *
 * <p>R6.3 (RUN-18): also reads the optional {@code htmlBody} string and {@code attachments} entries
 * ({@code {"filename":...,"contentType":...,"contentBase64":...}}) -- both additive, both absent by
 * default. {@code attachments} accepts either a list of such maps, or (the common single-attachment
 * "email me this report" shape) ONE such map directly, auto-wrapped as a one-element list -- this is
 * exactly the shape the {@code documentRender} capability's own output map already has, so its
 * result ref can be passed straight through as the attachments arg with no reshaping step, which the
 * DSL cannot express inline anyway (see the positional-args note below).</p>
 */
public final class MailPayload {

    private MailPayload() {
    }

    /**
     * A flow's capabilityCall step can only pass positional value-refs (no inline string
     * templating in the DSL itself) -- so a flow author who wants literal to/subject/body values
     * pulled from prior-step fields uses this positional form (to, subject, body, templateVars[,
     * htmlBody, attachments]) instead of building a single payload map, which the DSL genuinely
     * cannot construct inline.
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
        String htmlBody = args.size() > 4 && args.get(4) != null ? String.valueOf(args.get(4)) : null;
        List<MailAttachment> attachments = args.size() > 5 ? toAttachmentList(args.get(5)) : List.of();
        return new MailMessage(to, subject, body, templateVars, htmlBody, attachments);
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
        String htmlBody = map.get("htmlBody") == null ? null : String.valueOf(map.get("htmlBody"));
        List<MailAttachment> attachments = toAttachmentList(map.get("attachments"));
        return new MailMessage(to, subject, body, templateVars, htmlBody, attachments);
    }

    private static List<MailAttachment> toAttachmentList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<MailAttachment> out = new ArrayList<>();
            for (Object item : list) {
                MailAttachment attachment = toAttachment(item);
                if (attachment != null) {
                    out.add(attachment);
                }
            }
            return out;
        }
        MailAttachment single = toAttachment(value);
        return single == null ? List.of() : List.of(single);
    }

    private static MailAttachment toAttachment(Object item) {
        if (!(item instanceof Map<?, ?> map)) {
            return null;
        }
        Object base64 = map.get("contentBase64");
        if (base64 == null) {
            return null;
        }
        String filename = map.get("filename") == null ? "attachment" : String.valueOf(map.get("filename"));
        String contentType = map.get("contentType") == null
                ? "application/octet-stream"
                : String.valueOf(map.get("contentType"));
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(String.valueOf(base64));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "mail attachment 'contentBase64' is not valid base64 for filename '" + filename + "'", e);
        }
        return new MailAttachment(filename, contentType, bytes);
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
