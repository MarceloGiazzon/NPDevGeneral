package com.npdev.adapters.mail.smtp;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetup;
import com.npdev.adapters.documentrender.inproc.DocumentRenderInProcAdapter;
import com.npdev.adapters.flowcompiled.ModelBackedKernelRuntimeFactory;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityRegistry;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.ExecutionStatus;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.RegistryCapabilityDispatcher;
import jakarta.mail.BodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R6.3 (RUN-18) end-to-end proof: "nightly report emailed as PDF" composed from its real pieces, not
 * asserted piecewise. A real {@code model.json} -- declaring the {@code documentRender}/{@code mail}
 * capabilities, their bindings, and a cron-scheduled flow -- is parsed, semantically validated, and
 * compiled through the SAME production path a generated app uses ({@link
 * ModelBackedKernelRuntimeFactory}, which a real RuntimeHost boot also calls). The compiled flow is
 * then executed by a real {@link KernelRunner} against a real {@link DocumentRenderInProcAdapter}
 * (OpenHTMLtoPDF, real PDF bytes) and a real {@link SmtpMailCapabilityAdapter} pointed at a local
 * GreenMail SMTP sink -- never a mock of either capability.
 *
 * <p><b>What this does NOT re-prove:</b> the cron TIMER firing itself. {@code NpdevCronSchedulerService}
 * (RuntimeHost, RUN-15/R2.7) is the component that polls {@code schedule.cron} and calls {@code
 * KernelRunner.executeFlow(flowName, ...)} on a tick -- already covered by its own tests, outside
 * this module's ownership. This test drives {@code executeFlow} directly, i.e. exactly what that
 * poller does the moment a schedule fires, and proves what happens next: the declared capability
 * chain for a model that {@code schedule.cron} marks as scheduled.</p>
 */
final class ScheduledReportEndToEndTest {

    // A dynamic (OS-assigned) port, not ServerSetupTest.SMTP's fixed 3025 -- SmtpMailCapabilityAdapterTest
    // in this same module already owns that fixed port for its own GreenMailExtension lifecycle, and
    // two independent GreenMail servers racing to bind the same fixed port across test classes in one
    // Gradle test JVM is exactly the collision this avoids.
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));

    private static final String MODEL_JSON = """
            {
              "namespace": "demo",
              "dslVersion": "1.0.0",
              "version": "v1",
              "concepts": [
                {
                  "name": "Report",
                  "ui": { "label": "Report" },
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true }
                  ]
                }
              ],
              "capabilities": [
                { "name": "documentRender", "type": "DocumentRenderCapability", "operations": ["render"] },
                { "name": "mail", "type": "EmailCapability", "operations": ["send"] }
              ],
              "bindings": [
                { "capability": "documentRender", "adapter": "document-render-inproc" },
                { "capability": "mail", "adapter": "mail-smtp" }
              ],
              "flows": [
                {
                  "name": "NightlyReportEmail",
                  "concept": "Report",
                  "schedule": { "cron": "0 0 3 * * *" },
                  "steps": [
                    {
                      "name": "render-report",
                      "type": "capabilityCall",
                      "capability": "documentRender",
                      "operation": "render",
                      "args": [ "$input.html" ],
                      "output": "$rendered"
                    },
                    {
                      "name": "email-report",
                      "type": "capabilityCall",
                      "capability": "mail",
                      "operation": "send",
                      "args": [
                        "$input.to",
                        "$input.subject",
                        "$input.body",
                        "$input.templateVars",
                        "$input.htmlBody",
                        "$rendered"
                      ],
                      "output": "$delivery"
                    },
                    {
                      "name": "return-delivery",
                      "type": "return",
                      "value": "$delivery"
                    }
                  ]
                }
              ]
            }
            """;

    @Test
    void aCronScheduledFlowRendersAConceptListToPdfAndEmailsItWithTheAttachment(@TempDir Path tempDir) throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, MODEL_JSON, StandardCharsets.UTF_8);

        // 1. Parse + semantically validate + compile the SAME model.json through the real DSL
        //    pipeline a generated app's build uses -- proves the declared documentRender/mail
        //    capabilities, their bindings, and the schedule.cron flow are all schema/semantic-valid
        //    with zero DSL/schema changes (both are free-form strings, see ledger/items/RUN-14.yml's
        //    identical finding for webhook-http).
        CompiledModel compiledModel = ModelBackedKernelRuntimeFactory.compileModel(modelPath);
        assertEquals(1, compiledModel.getFlows().size());

        // 2. Wire the real production adapters -- the exact classes NpdevCapabilityBindingConfig
        //    would construct in a booted RuntimeHost -- against the compiled model's own bindings.
        DocumentRenderInProcAdapter documentRenderAdapter = new DocumentRenderInProcAdapter();
        SmtpMailCapabilityAdapter mailAdapter = new SmtpMailCapabilityAdapter(
                "127.0.0.1", greenMail.getSmtp().getPort(), null, null, "reports@npdev.test", false
        );
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("documentRender", "DocumentRenderCapability", "document-render-inproc", documentRenderAdapter);
        registry.register("mail", "EmailCapability", "mail-smtp", mailAdapter);
        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);

        KernelRunner runner = ModelBackedKernelRuntimeFactory.createKernelRunner(
                compiledModel,
                event -> { },
                (entityName, payload) -> List.of(),
                dispatcher
        );

        // 3. Drive the flow exactly as NpdevCronSchedulerService would the moment "0 0 3 * * *"
        //    fires -- executeFlow by name, no HTTP request in the loop.
        String reportHtml = "<html><head><style>table{border-collapse:collapse;}"
                + "td,th{border:1px solid #000;padding:4px;}</style></head><body>"
                + "<h1>Nightly Report</h1><table><tr><th>Widget</th><th>Qty</th></tr>"
                + "<tr><td>Bolt</td><td>42</td></tr></table></body></html>";
        ExecutionResult result = runner.executeFlow("NightlyReportEmail", Map.of(
                "html", reportHtml,
                "to", "ops@example.com",
                "subject", "Nightly report for ${day}",
                "body", "See the attached PDF.",
                "templateVars", Map.of("day", "2026-08-19"),
                "htmlBody", "<p>See the <b>attached</b> PDF.</p>"
        ));

        // 4. Prove the WHOLE chain, not the pieces: flow succeeded, real PDF bytes were produced,
        //    and the MIME message that actually left the process (via a local SMTP sink, never a
        //    real network send) carries those exact bytes as its attachment plus the HTML part.
        assertEquals(ExecutionStatus.OK, result.getStatus(), () -> "flow failed: " + result.getError());

        assertTrue(greenMail.waitForIncomingEmail(5000, 1), "expected exactly one email delivered to the SMTP sink");
        MimeMessage received = greenMail.getReceivedMessages()[0];
        assertEquals("Nightly report for 2026-08-19", received.getSubject(),
                "mail template substitution must have run on the flow-supplied subject");
        assertTrue(received.isMimeType("multipart/mixed"), "expected multipart/mixed, got " + received.getContentType());
        MimeMultipart multipart = (MimeMultipart) received.getContent();
        assertEquals(2, multipart.getCount(), "expected the alternative text/html part plus one PDF attachment part");

        BodyPart alternativePart = multipart.getBodyPart(0);
        assertTrue(alternativePart.isMimeType("multipart/alternative"),
                "expected the first part to be the text+html alternative, got " + alternativePart.getContentType());
        MimeMultipart alternative = (MimeMultipart) alternativePart.getContent();
        assertTrue(String.valueOf(alternative.getBodyPart(1).getContent()).contains("<b>attached</b>"),
                "expected the flow-supplied htmlBody to reach the outbound MIME message");

        BodyPart attachmentPart = multipart.getBodyPart(1);
        assertEquals("document.pdf", attachmentPart.getFileName());
        assertTrue(attachmentPart.isMimeType("application/pdf"),
                "expected application/pdf, got " + attachmentPart.getContentType());
        byte[] deliveredPdfBytes = attachmentPart.getInputStream().readAllBytes();
        assertTrue(deliveredPdfBytes.length > 0, "delivered PDF attachment must not be empty");
        assertEquals("%PDF-", new String(deliveredPdfBytes, 0, 5, StandardCharsets.US_ASCII),
                "the bytes that actually left the process over MIME must be a real, well-formed PDF");
    }
}
