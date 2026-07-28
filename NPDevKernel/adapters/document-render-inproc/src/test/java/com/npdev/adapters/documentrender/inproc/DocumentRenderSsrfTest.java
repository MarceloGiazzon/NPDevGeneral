package com.npdev.adapters.documentrender.inproc;

import com.npdev.kernel.ports.DocumentRenderContract.RenderOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-16-resid Round 6, finding R6-F3 — the PDF renderer must not fetch anything.
 *
 * <p>OpenHTMLtoPDF resolves external resources by default. Rendering happens <b>inside the server</b>,
 * so every such fetch is a server-side request: an SSRF that reaches internal hosts and cloud metadata
 * endpoints, and with {@code file:} URIs a local-file read.</p>
 *
 * <p>Nothing exploitable reaches this today — {@code DocumentRenderController} is the only caller, it
 * composes the HTML itself, and it HTML-escapes every record value. But this is a public adapter
 * behind a general {@code render(html, options)} contract, so the first feature that renders
 * author-supplied or templated HTML would get SSRF for free unless the policy lives here, where the
 * fetch would happen.</p>
 *
 * <p>The test drives a <b>real local HTTP server</b> rather than asserting on configuration: a
 * mis-wired resolver still looks correctly configured, and only a request that never arrives proves
 * anything.</p>
 */
class DocumentRenderSsrfTest {

    private final DocumentRenderInProcAdapter adapter = new DocumentRenderInProcAdapter();

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void startCollaborator() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "not-an-image".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopCollaborator() {
        server.stop(0);
    }

    private String collaboratorUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/pinged";
    }

    @Test
    void anImageReferenceNeverReachesTheNetwork() {
        String html = "<html><head><title>t</title></head><body>"
                + "<img src=\"" + collaboratorUrl() + "\" />"
                + "</body></html>";

        adapter.render(html, RenderOptions.defaults());

        assertEquals(0, hits.get(),
                "rendering must not issue an outbound request -- that is SSRF from inside the server");
    }

    @Test
    void aRemoteStylesheetNeverReachesTheNetwork() {
        // The <img> case is the obvious one; a remote stylesheet or @import is the one people forget,
        // and it is equally a server-side fetch.
        String html = "<html><head><title>t</title>"
                + "<link rel=\"stylesheet\" href=\"" + collaboratorUrl() + "\" />"
                + "<style>@import url(\"" + collaboratorUrl() + "\");</style>"
                + "</head><body><p>hello</p></body></html>";

        adapter.render(html, RenderOptions.defaults());

        assertEquals(0, hits.get(), "remote stylesheets and @import are outbound requests too");
    }

    @Test
    void aBlockedResourceStillProducesAValidPdf() {
        // Denying must degrade, not break: a document that references something external should
        // render without it rather than fail, or the policy becomes a denial-of-service of its own.
        String html = "<html><head><title>t</title></head><body>"
                + "<p>before</p><img src=\"" + collaboratorUrl() + "\" /><p>after</p>"
                + "</body></html>";

        byte[] pdf = adapter.render(html, RenderOptions.defaults());

        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
        assertEquals(0, hits.get());
    }

    @Test
    void aFileUriCannotReadFromDisk(@org.junit.jupiter.api.io.TempDir Path tempDir) throws IOException {
        // The other half of the same hole: with no resolver, file: URIs turn a render request into a
        // local-file read. Asserted by rendering and confirming the secret never appears in the PDF
        // bytes -- weaker than the network assertion, but it is the only observable channel here.
        Path secret = tempDir.resolve("secret.txt");
        Files.writeString(secret, "TOPSECRET-CANARY-VALUE");
        String fileUri = secret.toUri().toString();

        byte[] pdf = adapter.render(
                "<html><head><title>t</title><style>@import url(\"" + fileUri + "\");</style></head>"
                        + "<body><p>x</p></body></html>",
                RenderOptions.defaults());

        assertFalse(new String(pdf, StandardCharsets.ISO_8859_1).contains("TOPSECRET-CANARY-VALUE"),
                "a file: URI must not be resolvable from inside a render");
        assertTrue(pdf.length > 0);
    }
}
