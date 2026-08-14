package com.npdev.adapters.mail.smtp;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.EmailCapability;
import com.npdev.kernel.ports.MailMessage;
import com.npdev.kernel.ports.MailPayload;
import com.npdev.kernel.ports.MailSendResult;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;

/**
 * LNCH-11: real SMTP send via Jakarta Mail (Eclipse Angus implementation). Config (host/port/
 * credentials/from/starttls) is supplied by the caller at construction time -- RuntimeHost wires
 * this from env vars via {@code @Value}, same pattern as the objectstore file-store adapter's
 * bucket/region/endpoint config.
 *
 * <p><b>R8d (RUN-4): this adapter owns its own deadline, on purpose.</b> Before this, neither this
 * class nor {@code external-ai-http} set a connect/read timeout, and {@code CapabilityExecutionPolicy
 * .defaults()} returns zeros for timeout/retry -- so a stuck SMTP transaction (TCP connects, server
 * never answers) hung the calling thread forever. The fix lives entirely here: JavaMail's
 * {@code mail.smtp.connectiontimeout}/{@code mail.smtp.timeout}/{@code mail.smtp.writetimeout}
 * properties, plus a bounded, adapter-local retry loop in {@link #send}, both enforced on the calling
 * thread. This deliberately does NOT flip {@code CapabilityExecutionPolicy.defaults()}'s kernel-wide
 * timeout -- see {@code ledger/items/RUN-4.yml} and {@code HttpExternalAiCapabilityAdapter}'s javadoc
 * for why that is a separate, platform-wide decision.</p>
 */
public final class SmtpMailCapabilityAdapter implements CapabilityAdapter, EmailCapability {

    /** R8d (RUN-4) defaults, used by the legacy 6-arg constructor. */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DEFAULT_IO_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofSeconds(1);

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String from;
    private final boolean startTls;
    private final Duration connectTimeout;
    private final Duration ioTimeout;
    private final int maxRetries;
    private final Duration retryBackoff;

    public SmtpMailCapabilityAdapter(String host, int port, String username, String password, String from, boolean startTls) {
        this(
                host, port, username, password, from, startTls,
                DEFAULT_CONNECT_TIMEOUT, DEFAULT_IO_TIMEOUT, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF
        );
    }

    /**
     * R8d (RUN-4) full constructor: an adapter-owned deadline/retry policy. {@code connectTimeout}
     * bounds the TCP handshake; {@code ioTimeout} bounds every subsequent read/write on the SMTP
     * session (used for both {@code mail.smtp.timeout} and {@code mail.smtp.writetimeout});
     * {@code maxRetries} is the number of retries AFTER the first attempt (0 = try once, never
     * retry); {@code retryBackoff} is the base delay, multiplied by the attempt number between
     * retries.
     */
    public SmtpMailCapabilityAdapter(
            String host, int port, String username, String password, String from, boolean startTls,
            Duration connectTimeout, Duration ioTimeout, int maxRetries, Duration retryBackoff
    ) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("mail-smtp adapter requires a non-blank SMTP host");
        }
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("mail-smtp adapter requires a non-blank from address");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.from = from;
        this.startTls = startTls;
        this.connectTimeout = Objects.requireNonNull(connectTimeout, "connectTimeout");
        this.ioTimeout = Objects.requireNonNull(ioTimeout, "ioTimeout");
        this.maxRetries = maxRetries;
        this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
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

    /**
     * R8d (RUN-4): bounded retry loop, entirely local to this adapter. Retries only a transport-level
     * failure (a {@link MessagingException} whose cause is an {@link IOException} -- connection
     * refused, connection reset, or the connect/read/write timeout set in {@link #session()} firing)
     * -- never an {@link AuthenticationFailedException} or {@link SendFailedException}, which a retry
     * cannot fix. Rebuilds the {@link MimeMessage} fresh on every attempt rather than resending one
     * that already failed partway through a write.
     */
    @Override
    public MailSendResult send(MailMessage message) {
        MailMessage rendered = message.withRenderedTemplate();
        if (rendered.to().isEmpty()) {
            throw new MailSendException("mail-smtp send requires at least one 'to' address");
        }
        MessagingException lastFailure = null;
        int totalAttempts = maxRetries + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
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
                if (!isRetryable(e) || attempt >= totalAttempts) {
                    throw new MailSendException(
                            "SMTP send failed on attempt " + attempt + "/" + totalAttempts + ": " + e.getMessage(), e);
                }
                lastFailure = e;
                backoff(attempt);
            }
        }
        // Unreachable in practice (the loop above always returns or throws on its last iteration),
        // but the compiler cannot prove that from a non-constant loop bound.
        throw new MailSendException(
                "SMTP send exhausted retries: " + (lastFailure == null ? "unknown failure" : lastFailure.getMessage()),
                lastFailure);
    }

    private static boolean isRetryable(MessagingException e) {
        if (e instanceof AuthenticationFailedException || e instanceof SendFailedException) {
            return false;
        }
        return e.getCause() instanceof IOException;
    }

    private void backoff(int attempt) {
        long delayMs = retryBackoff.toMillis() * attempt;
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailSendException("SMTP retry backoff interrupted", e);
        }
    }

    private Session session() {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        // R8d (RUN-4): the deadline this call cannot escape. connectiontimeout bounds the TCP
        // handshake; timeout bounds a read once connected (this is what fires when a server accepts
        // the connection and then never answers); writetimeout is Angus Mail's socket-write deadline,
        // supported since 2.0.3 (the version this module depends on).
        props.put("mail.smtp.connectiontimeout", String.valueOf(connectTimeout.toMillis()));
        props.put("mail.smtp.timeout", String.valueOf(ioTimeout.toMillis()));
        props.put("mail.smtp.writetimeout", String.valueOf(ioTimeout.toMillis()));
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
