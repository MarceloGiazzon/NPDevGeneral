package com.npdev.adapters.mail.smtp;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.MailAttachment;
import com.npdev.kernel.ports.MailMessage;
import com.npdev.kernel.ports.MailSendResult;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmtpMailCapabilityAdapterTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Test
    void sendRendersTemplateAndDeliversOverRealSmtp() throws Exception {
        SmtpMailCapabilityAdapter adapter = new SmtpMailCapabilityAdapter(
                "127.0.0.1", ServerSetupTest.SMTP.getPort(), null, null, "no-reply@npdev.test", false
        );

        MailSendResult result = adapter.send(new MailMessage(
                List.of("ada@example.com"),
                "Welcome, ${name}",
                "Hello ${name}, your order ${orderId} shipped.",
                Map.of("name", "Ada", "orderId", "A-42")
        ));

        assertEquals("sent", result.status());
        assertEquals("mail-smtp", result.provider());

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertEquals(1, received.length);
        assertEquals("Welcome, Ada", received[0].getSubject());
        assertEquals("Hello Ada, your order A-42 shipped.", GreenMailUtil.getBody(received[0]).trim());
        assertEquals("ada@example.com", received[0].getAllRecipients()[0].toString());
    }

    @Test
    void invokeViaCapabilityCallSendsOverSmtp() throws Exception {
        SmtpMailCapabilityAdapter adapter = new SmtpMailCapabilityAdapter(
                "127.0.0.1", ServerSetupTest.SMTP.getPort(), null, null, "no-reply@npdev.test", false
        );
        CapabilityCall call = new CapabilityCall(
                "mail",
                "EmailCapability",
                "mail-smtp",
                "send",
                List.of(Map.of("to", "bob@example.com", "subject", "Hi", "body", "Body"))
        );

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(result.ok());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
    }

    @Test
    void constructorRejectsBlankHost() {
        try {
            new SmtpMailCapabilityAdapter("", 25, null, null, "no-reply@npdev.test", false);
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // expected
        }
    }

    /**
     * R8d (RUN-4) live proof: a connect/read deadline that actually fires, and a bounded retry that
     * actually happens -- against a REAL local server that accepts the TCP connection and then never
     * sends the SMTP "220" greeting, so the client blocks waiting for it until {@code mail.smtp
     * .timeout} fires. No external network dependency: the "stuck SMTP server" is a loopback
     * {@link ServerSocket} this test owns, never a mocked timeout.
     */
    @Test
    void sendTimesOutAndRetriesTheConfiguredNumberOfTimesAgainstAHangingServer() throws Exception {
        try (ServerSocket hangingServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger acceptedConnections = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Socket socket = hangingServer.accept();
                        acceptedConnections.incrementAndGet();
                        // Deliberately never write the "220 ..." greeting -- the client must hit its
                        // own mail.smtp.timeout, not a server-side close.
                    } catch (IOException e) {
                        return;
                    }
                }
            }, "hanging-smtp-server-acceptor");
            acceptor.setDaemon(true);
            acceptor.start();

            // connectTimeout=300ms, ioTimeout=300ms, maxRetries=1 (2 total attempts),
            // retryBackoff=50ms -- short enough that the whole test resolves in well under a second
            // if the deadline is real, and would hang the JUnit run (previously: forever) if not.
            SmtpMailCapabilityAdapter adapter = new SmtpMailCapabilityAdapter(
                    "127.0.0.1", hangingServer.getLocalPort(), null, null, "no-reply@npdev.test", false,
                    Duration.ofMillis(300), Duration.ofMillis(300), 1, Duration.ofMillis(50));

            long startedAt = System.nanoTime();
            SmtpMailCapabilityAdapter.MailSendException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    SmtpMailCapabilityAdapter.MailSendException.class,
                    () -> adapter.send(new MailMessage(
                            List.of("ada@example.com"), "Hi", "Body", Map.of())));
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

            acceptor.interrupt();
            hangingServer.close();

            assertTrue(elapsedMs < 5000,
                    "expected the adapter's own connect/io timeout to bound the call well under 5s, took " + elapsedMs + "ms");
            assertEquals(2, acceptedConnections.get(),
                    "expected exactly maxRetries+1 = 2 attempts (2 real TCP connections) against the hanging server");
            assertTrue(thrown.getMessage().contains("attempt 2/2"), thrown.getMessage());
        }
    }

    /**
     * R6.3 (RUN-18): the first half of "MIME mail" -- an HTML alternative body, no attachments,
     * delivered as a real {@code multipart/alternative} message over a real (loopback) SMTP session,
     * never asserted against the adapter's internal state.
     */
    @Test
    void sendWithHtmlBodyDeliversMultipartAlternative() throws Exception {
        SmtpMailCapabilityAdapter adapter = new SmtpMailCapabilityAdapter(
                "127.0.0.1", ServerSetupTest.SMTP.getPort(), null, null, "no-reply@npdev.test", false
        );

        adapter.send(new MailMessage(
                List.of("ada@example.com"),
                "Weekly report",
                "Plain text body",
                Map.of(),
                "<p>HTML body</p>",
                List.of()
        ));

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertTrue(received.isMimeType("multipart/alternative"),
                "expected multipart/alternative, got " + received.getContentType());
        MimeMultipart multipart = (MimeMultipart) received.getContent();
        assertEquals(2, multipart.getCount());
        BodyPart textPart = multipart.getBodyPart(0);
        BodyPart htmlPart = multipart.getBodyPart(1);
        assertTrue(String.valueOf(textPart.getContent()).trim().equals("Plain text body"));
        assertTrue(htmlPart.isMimeType("text/html"), "expected text/html, got " + htmlPart.getContentType());
        assertTrue(String.valueOf(htmlPart.getContent()).contains("<p>HTML body</p>"));
    }

    /**
     * R6.3 (RUN-18): the second half -- a binary attachment (standing in for a rendered PDF) rides
     * through {@code multipart/mixed} and arrives at the SMTP sink with byte-identical content. This
     * is the exact wire shape the scheduled-report end-to-end proof depends on: whatever
     * {@code documentRender} renders must survive MIME transport unmodified.
     */
    @Test
    void sendWithAttachmentDeliversByteIdenticalContentOverMime() throws Exception {
        SmtpMailCapabilityAdapter adapter = new SmtpMailCapabilityAdapter(
                "127.0.0.1", ServerSetupTest.SMTP.getPort(), null, null, "no-reply@npdev.test", false
        );
        byte[] attachmentBytes = "%PDF-1.4 not a real pdf but binary-shaped %%EOF"
                .getBytes(StandardCharsets.UTF_8);

        adapter.send(new MailMessage(
                List.of("ada@example.com"),
                "Your report",
                "See attached",
                Map.of(),
                null,
                List.of(new MailAttachment("report.pdf", "application/pdf", attachmentBytes))
        ));

        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertTrue(received.isMimeType("multipart/mixed"), "expected multipart/mixed, got " + received.getContentType());
        MimeMultipart multipart = (MimeMultipart) received.getContent();
        assertEquals(2, multipart.getCount(), "expected a text part plus one attachment part");
        BodyPart attachmentPart = multipart.getBodyPart(1);
        assertEquals("report.pdf", attachmentPart.getFileName());
        assertTrue(attachmentPart.isMimeType("application/pdf"),
                "expected application/pdf, got " + attachmentPart.getContentType());
        byte[] receivedBytes = attachmentPart.getInputStream().readAllBytes();
        assertTrue(java.util.Arrays.equals(attachmentBytes, receivedBytes),
                "attachment bytes must survive MIME transport byte-identical");
    }

    /**
     * R6.3 (RUN-18): drives the SAME shape a flow's capabilityCall step would -- a single map ref
     * whose {@code attachments} entry is ONE map (not a list), exactly what the {@code
     * documentRender} capability's own output looks like, auto-wrapped by {@code MailPayload} into a
     * one-element attachment list with no reshaping step in between (the DSL cannot build one).
     */
    @Test
    void invokeViaCapabilityCallAttachesASingleDocumentRenderShapedMap() throws Exception {
        SmtpMailCapabilityAdapter adapter = new SmtpMailCapabilityAdapter(
                "127.0.0.1", ServerSetupTest.SMTP.getPort(), null, null, "no-reply@npdev.test", false
        );
        byte[] pdfBytes = "%PDF-fake-report-bytes%%EOF".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> documentRenderShapedOutput = Map.of(
                "contentBase64", Base64.getEncoder().encodeToString(pdfBytes),
                "contentType", "application/pdf",
                "filename", "nightly-report.pdf"
        );
        CapabilityCall call = new CapabilityCall(
                "mail",
                "EmailCapability",
                "mail-smtp",
                "send",
                List.of(Map.of(
                        "to", "ops@example.com",
                        "subject", "Nightly report",
                        "body", "Attached is the nightly report.",
                        "attachments", documentRenderShapedOutput
                ))
        );

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(result.ok());
        assertTrue(greenMail.waitForIncomingEmail(5000, 1));
        MimeMessage received = greenMail.getReceivedMessages()[0];
        MimeMultipart multipart = (MimeMultipart) received.getContent();
        BodyPart attachmentPart = multipart.getBodyPart(1);
        assertEquals("nightly-report.pdf", attachmentPart.getFileName());
        assertTrue(java.util.Arrays.equals(pdfBytes, attachmentPart.getInputStream().readAllBytes()));
    }
}
