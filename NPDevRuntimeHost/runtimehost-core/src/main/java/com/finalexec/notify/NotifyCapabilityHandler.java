package com.finalexec.notify;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.MailTemplateRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * PROC-4: {@code notify.send} -- a flow or procedure step writes an in-app notification (shown by the
 * shell bell) for one or more users. Platform-served like {@code fileStore}; model binding
 * {@code { "capability": "notify", "adapter": "notify" }}.
 *
 * <pre>
 *   flow step  : { "type": "capabilityCall", "capability": "notify", "operation": "send",
 *                  "args": ["$saved.recipientUsername", "New trade ${code}", "From ${proposerUsername}", "$saved", "/npdev-business-ui/collab/tasks.html"] }
 *   procedure  : "args": { "to": "$recipient.username", "title": "...", "body": "...", "templateVars": "$trade", "link": "/..." }
 * </pre>
 *
 * Positional order {@code (to, title, body, templateVars, link)} mirrors {@code MailPayload.parse}
 * ({@code to, subject, body, templateVars, ...}); a single Map argument is read by key. {@code title} and
 * {@code body} may use {@code ${var}} placeholders resolved from {@code templateVars}. Links must be
 * site-relative (start with a single '/'); anything else is dropped, so the bell can never become an
 * open redirect. The tenant comes from the flow state, never from the arguments.
 */
public final class NotifyCapabilityHandler implements CapabilityAdapter {

    private final NotificationStore store;
    private final LongSupplier clock;

    public NotifyCapabilityHandler(NotificationStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public String adapterId() {
        return "notify";
    }

    @Override
    public String capability() {
        return "notify";
    }

    @Override
    public String capabilityType() {
        return "NotifyCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        if (!"send".equals(call.operation())) {
            return CapabilityResult.failure("NOTIFY_OPERATION_UNSUPPORTED",
                    "Unsupported notify operation: " + call.operation() + " (use 'send')",
                    CapabilityErrorKind.CONTRACT, Map.of("operation", String.valueOf(call.operation())));
        }
        List<Object> args = call.args() == null ? List.of() : call.args();
        Map<?, ?> named = args.size() == 1 && args.get(0) instanceof Map<?, ?> m ? m : null;
        Object to = named != null ? named.get("to") : arg(args, 0);
        String title = str(named != null ? named.get("title") : arg(args, 1));
        String body = str(named != null ? named.get("body") : arg(args, 2));
        Object vars = named != null ? named.get("templateVars") : arg(args, 3);
        String link = str(named != null ? named.get("link") : arg(args, 4));
        List<String> recipients = recipients(to);
        if (recipients.isEmpty() || title == null || title.isBlank()) {
            return CapabilityResult.failure("NOTIFY_ARGS_INVALID", "notify.send needs a recipient and a title",
                    CapabilityErrorKind.CONTRACT, Map.of());
        }
        if (link != null && !isSiteRelative(link)) {
            link = null;
        }
        Map<String, Object> templateVars = new LinkedHashMap<>();
        if (vars instanceof Map<?, ?> v) {
            v.forEach((k, value) -> templateVars.put(String.valueOf(k), value));
        }
        String tenant = contextState == null || contextState.get("tenantId") == null
                ? null : String.valueOf(contextState.get("tenantId"));
        if (tenant == null || tenant.isBlank()) {
            return CapabilityResult.failure("NOTIFY_TENANT_MISSING", "notify.send has no tenant in the flow context",
                    CapabilityErrorKind.CONTRACT, Map.of());
        }
        long now = clock.getAsLong();
        String renderedTitle = MailTemplateRenderer.render(title, templateVars);
        String renderedBody = body == null ? null : MailTemplateRenderer.render(body, templateVars);
        String source = sourceOf(contextState);
        for (String recipient : recipients) {
            store.insert(new NotificationStore.Notification(UUID.randomUUID().toString(), tenant, recipient,
                    renderedTitle, renderedBody, link, source, now, null));
        }
        return CapabilityResult.success(Map.of("notified", recipients.size()));
    }

    private static Object arg(List<Object> args, int index) {
        return args.size() > index ? args.get(index) : null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** "/x" yes; "//evil.com", "/\\evil.com", "https://evil", "javascript:..." no. */
    static boolean isSiteRelative(String link) {
        return link.startsWith("/") && !link.startsWith("//") && !link.startsWith("/\\");
    }

    /** A username, a list of usernames, or nothing. Blank entries dropped, duplicates removed. */
    private static List<String> recipients(Object to) {
        List<String> out = new ArrayList<>();
        if (to instanceof List<?> list) {
            for (Object entry : list) {
                addRecipient(out, entry);
            }
        } else {
            addRecipient(out, to);
        }
        return out;
    }

    private static void addRecipient(List<String> out, Object entry) {
        if (entry == null) {
            return;
        }
        String name = String.valueOf(entry).trim();
        if (!name.isEmpty() && !out.contains(name)) {
            out.add(name);
        }
    }

    /** Best effort: the kernel stamps {@code causationId = "flow:<name>"} into the flow state. */
    private static String sourceOf(Map<String, Object> contextState) {
        Object causation = contextState == null ? null : contextState.get("causationId");
        return causation != null && String.valueOf(causation).startsWith("flow:") ? String.valueOf(causation) : null;
    }
}
