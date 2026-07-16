package com.npdev.adapters.mail.smtp;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.MailMessage;
import com.npdev.kernel.ports.MailSendResult;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

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
}
