package com.npdev.adapters.mail.inproc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.EmailCapability;
import com.npdev.kernel.ports.MailAttachment;
import com.npdev.kernel.ports.MailMessage;
import com.npdev.kernel.ports.MailPayload;
import com.npdev.kernel.ports.MailSendResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * LNCH-11: dev/test twin of {@link com.npdev.adapters.mail.smtp.SmtpMailCapabilityAdapter} --
 * renders the template like a real send would, but records the delivery in memory instead of
 * dispatching over the network, so a gate can assert on sent mail without SMTP infrastructure.
 */
public final class InProcMailCapabilityAdapter implements CapabilityAdapter, EmailCapability {

    private final List<Map<String, Object>> deliveries = new ArrayList<>();

    @Override
    public String adapterId() {
        return "mail-inproc";
    }

    @Override
    public String capability() {
        return "mail";
    }

    @Override
    public String capabilityType() {
        return "EmailCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        if (!"send".equals(call.operation())) {
            return CapabilityResult.failure(
                    "MAIL_OPERATION_UNSUPPORTED",
                    "Unsupported mail operation: " + call.operation(),
                    CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation())
            );
        }
        return CapabilityResult.success(sendPayload(call.args()));
    }

    @Override
    public MailSendResult send(MailMessage message) {
        MailMessage rendered = message.withRenderedTemplate();
        String deliveryId = UUID.randomUUID().toString();
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("deliveryId", deliveryId);
        record.put("to", rendered.to());
        record.put("subject", rendered.subject());
        record.put("body", rendered.body());
        // R6.3 (RUN-18): htmlBody is only recorded when present -- Map.copyOf (below) rejects null
        // values, and the pre-R6.3 delivery shape had no such key at all, so this stays additive.
        if (rendered.hasHtmlBody()) {
            record.put("htmlBody", rendered.htmlBody());
        }
        record.put("attachmentCount", rendered.attachments().size());
        record.put("attachmentFilenames", rendered.attachments().stream().map(MailAttachment::filename).toList());
        record.put("status", "sent");
        record.put("adapterId", adapterId());
        record.put("sentAt", Instant.EPOCH.toString());
        deliveries.add(Map.copyOf(record));
        return new MailSendResult(deliveryId, "sent", adapterId());
    }

    public List<Map<String, Object>> deliveries() {
        return List.copyOf(deliveries);
    }

    private Map<String, Object> sendPayload(List<Object> args) {
        send(MailPayload.parse(args));
        return deliveries.get(deliveries.size() - 1);
    }
}
