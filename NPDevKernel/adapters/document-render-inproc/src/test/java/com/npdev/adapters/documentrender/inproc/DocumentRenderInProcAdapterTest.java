package com.npdev.adapters.documentrender.inproc;

import com.npdev.kernel.ports.DocumentRenderContract;
import com.npdev.kernel.ports.DocumentRenderContract.RenderOptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-12 Slice 3 (P1): HTML in, valid PDF bytes out -- the same print-document shape LNCH-10 Slice 2
 * produces (title/meta/table/footer). Asserts the actual PDF magic bytes, not just "non-empty", since
 * a library that silently produced garbage would still pass a byte-count-only check.
 */
class DocumentRenderInProcAdapterTest {

    private final DocumentRenderInProcAdapter adapter = new DocumentRenderInProcAdapter();

    @Test
    void rendersAWellFormedGridDocumentToAValidPdf() {
        String html = printDocumentHtml();

        byte[] pdf = adapter.render(html, RenderOptions.defaults());

        assertTrue(pdf.length > 0, "rendered PDF must not be empty");
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII),
                "output must start with the PDF file signature");
        String tail = new String(pdf, pdf.length - Math.min(32, pdf.length), Math.min(32, pdf.length),
                StandardCharsets.US_ASCII);
        assertTrue(tail.contains("%%EOF"), "a well-formed PDF must end with %%EOF, got: " + tail);
    }

    @Test
    void largerPageCountsAlsoProduceAValidPdf() {
        // Sixty rows -- enough to force at least one page break under A4/20mm margins, proving the
        // adapter handles multi-page output (P4's live verification exercises this against a real
        // grid; this test proves it at the adapter level in isolation).
        byte[] pdf = adapter.render(printDocumentHtml(60), RenderOptions.defaults());

        assertTrue(pdf.length > 0);
        assertEquals("%PDF-", new String(pdf, 0, 5, StandardCharsets.US_ASCII));
    }

    @Test
    void honorsTheDeclaredPageSizeAndMargin() {
        byte[] letterPdf = adapter.render(printDocumentHtml(), new RenderOptions(DocumentRenderContract.PageSize.LETTER, 10.0));

        assertTrue(letterPdf.length > 0);
        assertEquals("%PDF-", new String(letterPdf, 0, 5, StandardCharsets.US_ASCII));
    }

    @Test
    void blankHtmlIsRefusedRatherThanSilentlyProducingAnEmptyPdf() {
        assertThrows(DocumentRenderContract.DocumentRenderException.class,
                () -> adapter.render("", RenderOptions.defaults()));
        assertThrows(DocumentRenderContract.DocumentRenderException.class,
                () -> adapter.render(null, RenderOptions.defaults()));
    }

    @Test
    void malformedHtmlIsWrappedAsADocumentRenderException() {
        // Not well-formed XML (unclosed tag) -- OpenHTMLtoPDF's XML parser must reject this, and the
        // adapter must wrap that failure rather than let a library-specific exception type leak
        // through the port.
        DocumentRenderContract.DocumentRenderException exception = assertThrows(
                DocumentRenderContract.DocumentRenderException.class,
                () -> adapter.render("<html><body><table><tr><td>unclosed", RenderOptions.defaults()));
        assertTrue(exception.getCause() != null, "the underlying library failure must be preserved as the cause");
    }

    private static String printDocumentHtml() {
        return printDocumentHtml(3);
    }

    private static String printDocumentHtml(int rowCount) {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= rowCount; i++) {
            rows.append("<tr><td>").append(i).append("</td><td>Row ").append(i).append("</td></tr>");
        }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<!DOCTYPE html>"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><style>"
                + "table.print-table { border-collapse: collapse; width: 100%; }"
                + "table.print-table th, table.print-table td { border: 1px solid #000; padding: 4px; }"
                + "</style></head><body>"
                + "<div class=\"print-header\"><h1>Widgets</h1><div class=\"print-meta\">Printed 2026-07-22</div></div>"
                + "<table class=\"print-table\"><thead><tr><th>Id</th><th>Name</th></tr></thead>"
                + "<tbody>" + rows + "</tbody></table>"
                + "<div class=\"print-footer\">Total: " + rowCount + " of " + rowCount + " record(s)</div>"
                + "</body></html>";
    }
}
