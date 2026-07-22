package com.npdev.adapters.mail.smtp;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.EmailCapability;
import com.npdev.kernel.ports.MailMessage;
import com.npdev.kernel.ports.MailPayload;
import com.npdev.kernel.ports.MailSendResult;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * LNCH-11: real SMTP send via Jakarta Mail (Eclipse Angus implementation). Config (host/port/
 * credentials/from/starttls) is supplied by the caller at construction time -- RuntimeHost wires
 * this from env vars via {@code @Value}, same pattern as the objectstore file-store adapter's
 * bucket/region/endpoint config.
 */
public final class SmtpMailCapabilityAdapter implements CapabilityAdapter, EmailCapability {

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final boolean startTls;

    public SmtpMailCapabilityAdapter(String host, int port, String username, String password, String from, boolean startTls) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("mail-smtp adapter requires a non-blank SMTP host");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("mail-smtp adapter requires a non-blank from address");
        }
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.from = from;
        this.startTls = startTls;
    }

    @Override
    public String adapterId() {
        return "mail-smtp";
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
        try {
            MailSendResult result = send(MailPayload.parse(call.args()));
            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("deliveryId", result.deliveryId());
            resultMap.put("status", result.status());
            resultMap.put("adapterId", result.provider());
            return CapabilityResult.success(resultMap);
        } catch (MailSendException e) {
            return CapabilityResult.failure(
                    "MAIL_SEND_FAILED",
                    e.getMessage(),
                    CapabilityErrorKind.TRANSIENT,
                    Map.of()
            );
        }
    }

    @Override
    public MailSendResult send(MailMessage message) {
        MailMessage rendered = message.withRenderedTemplate();
        if (rendered.to().isEmpty()) {
            throw new MailSendException("mail-smtp send requires at least one 'to' address");
        }
        try {
            Session session = session();
            MimeMessage mimeMessage = new MimeMessage(session);
            mimeMessage.setFrom(new InternetAddress(from));
            for (String recipient : rendered.to()) {
                mimeMessage.addRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
            }
            mimeMessage.setSubject(rendered.subject());
            mimeMessage.setText(rendered.body());
            Transport.send(mimeMessage);
            return new MailSendResult(UUID.randomUUID().toString(), "sent", adapterId());
        } catch (MessagingException e) {
            throw new MailSendException("SMTP send failed: " + e.getMessage(), e);
        }
    }

    private Session session() {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        boolean hasCredentials = username != null && !username.isBlank() && password != null && !password.isBlank();
        props.put("mail.smtp.auth", String.valueOf(hasCredentials));
        if (hasCredentials) {
            return Session.getInstance(props, new jakarta.mail.Authenticator() {
                @Override
                protected jakarta.mail.PasswordAuthentication getPasswordAuthentication() {
                    return new jakarta.mail.PasswordAuthentication(username, password);
                }
            });
        }
        return Session.getInstance(props);
    }

    public static final class MailSendException extends RuntimeException {
        public MailSendException(String message) {
            super(message);
        }

        public MailSendException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
