package com.npdev.adapters.documentrender.inproc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.DocumentRenderContract.DocumentRenderException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5.7 (Roadmap Wave 1 2026-08-19): the canonical ERP document shape -- a header band, a
 * multi-row line-item band, and a logo -- rendered through the {@code documentRender} capability's
 * new {@code renderAggregate} operation. Asserts REAL PDF bytes with the header/line-item text
 * actually present (via PDFBox text extraction), not just "a PDF-shaped blob came back": a renderer
 * that silently dropped every row would still pass a byte-count-only check.
 */
class AggregateDocumentRenderTest {

    private final DocumentRenderInProcAdapter adapter = new DocumentRenderInProcAdapter();

    /** A 1x1 transparent PNG, the smallest real image byte sequence -- proves the logo is embedded
     *  as genuine image bytes, not a placeholder string. */
    private static final String ONE_PIXEL_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=";

    @Test
    void rendersAHeaderPlusMultiLineLineItemBandToARealPdfWithAllRowsPresent() throws IOException {
        Map<String, Object> tree = Map.of(
                "orderNumber", "PO-1042",
                "customerEmail", "buyer@example.com",
                "lines", List.of(
                        Map.of("sku", "WID-0001", "qty", 3, "unitPrice", "12.50"),
                        Map.of("sku", "WID-0002", "qty", 7, "unitPrice", "4.00"),
                        Map.of("sku", "WID-0003", "qty", 1, "unitPrice", "99.99")
                )
        );

        CapabilityCall call = new CapabilityCall(
                "documentRender", "DocumentRenderCapability", "document-render-inproc", "renderAggregate",
                List.of(Map.of(
                        "title", "Widget order invoice",
                        "filename", "invoice.pdf",
                        "logoDataUri", "data:image/png;base64," + ONE_PIXEL_PNG_BASE64,
                        "tree", tree,
                        "bands", List.of(
                                Map.of("name", "header", "kind", "header", "label", "Order",
                                        "fields", List.of(
                                                Map.of("field", "orderNumber", "label", "Order number"),
                                                Map.of("field", "customerEmail", "label", "Customer")
                                        )),
                                Map.of("name", "lines", "kind", "lineItems", "collection", "lines",
                                        "label", "Line items",
                                        "fields", List.of(
                                                Map.of("field", "sku", "label", "SKU"),
                                                Map.of("field", "qty", "label", "Qty"),
                                                Map.of("field", "unitPrice", "label", "Unit price")
                                        ))
                        )
                ))
        );

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(result.ok(), () -> "expected success, got: " + result);
        Map<?, ?> value = (Map<?, ?>) result.value();
        assertEquals("application/pdf", value.get("contentType"));
        assertEquals("invoice.pdf", value.get("filename"));
        byte[] pdfBytes = Base64.getDecoder().decode((String) value.get("contentBase64"));
        assertEquals("%PDF-", new String(pdfBytes, 0, 5, StandardCharsets.US_ASCII));

        String text = extractText(pdfBytes);
        // Header band content.
        assertTrue(text.contains("Widget order invoice"), "title missing: " + text);
        assertTrue(text.contains("PO-1042"), "header field value missing: " + text);
        assertTrue(text.contains("buyer@example.com"), "header field value missing: " + text);
        // Every line-item row's data must be present -- this is the "multi-line" proof: if the
        // renderer only emitted headers or a single row, these would not all be found.
        assertTrue(text.contains("WID-0001") && text.contains("WID-0002") && text.contains("WID-0003"),
                "not all 3 line-item rows rendered: " + text);
        assertTrue(text.contains("12.50") && text.contains("4.00") && text.contains("99.99"),
                "not all line-item values rendered: " + text);
    }

    @Test
    void aBandWithNoFieldsIsRefusedAtRenderTimeRatherThanRenderingBlank() {
        Map<String, Object> tree = Map.of("orderNumber", "PO-1");
        CapabilityCall call = new CapabilityCall(
                "documentRender", "DocumentRenderCapability", "document-render-inproc", "renderAggregate",
                List.of(Map.of(
                        "title", "t", "tree", tree,
                        "bands", List.of(Map.of("name", "header", "kind", "header", "fields", List.of()))
                ))
        );

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(!result.ok());
        assertEquals("DOCUMENT_RENDER_FAILED", result.error().code());
        assertTrue(result.error().message().contains("no fields"), result.error().message());
    }

    @Test
    void aLineItemsBandNamingACollectionAbsentFromTheTreeFailsRatherThanRenderingAnEmptyTable() {
        Map<String, Object> tree = Map.of("orderNumber", "PO-1"); // no "lines" key at all
        CapabilityCall call = new CapabilityCall(
                "documentRender", "DocumentRenderCapability", "document-render-inproc", "renderAggregate",
                List.of(Map.of(
                        "title", "t", "tree", tree,
                        "bands", List.of(Map.of(
                                "name", "lines", "kind", "lineItems", "collection", "lines",
                                "fields", List.of(Map.of("field", "sku"))))
                ))
        );

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(!result.ok());
        assertEquals("DOCUMENT_RENDER_FAILED", result.error().code());
        assertTrue(result.error().message().contains("absent from the supplied aggregate tree"),
                result.error().message());
    }

    @Test
    void aGenuinelyEmptyLineItemsCollectionRendersAnHonestHeadersOnlyTableNotAFailure() throws IOException {
        // Distinguishes "misconfigured band" (previous test, must fail) from "this record legitimately
        // has zero line items yet" (must NOT fail -- an empty order is not an authoring error).
        Map<String, Object> tree = Map.of("orderNumber", "PO-1", "lines", List.of());
        CapabilityCall call = new CapabilityCall(
                "documentRender", "DocumentRenderCapability", "document-render-inproc", "renderAggregate",
                List.of(Map.of(
                        "title", "t", "tree", tree,
                        "bands", List.of(Map.of(
                                "name", "lines", "kind", "lineItems", "collection", "lines", "label", "Line items",
                                "fields", List.of(Map.of("field", "sku", "label", "SKU"))))
                ))
        );

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(result.ok(), () -> "expected success, got: " + result);
        Map<?, ?> value = (Map<?, ?>) result.value();
        byte[] pdfBytes = Base64.getDecoder().decode((String) value.get("contentBase64"));
        String text = extractText(pdfBytes);
        assertTrue(text.contains("SKU"), "the empty table's header row must still render: " + text);
    }

    @Test
    void aLogoThatIsNotAnInlineDataUriIsRefusedRatherThanSilentlyDroppedOrFetched() {
        Map<String, Object> tree = Map.of("orderNumber", "PO-1");
        CapabilityCall call = new CapabilityCall(
                "documentRender", "DocumentRenderCapability", "document-render-inproc", "renderAggregate",
                List.of(Map.of(
                        "title", "t", "tree", tree,
                        "logoDataUri", "http://169.254.169.254/latest/meta-data/",
                        "bands", List.of(Map.of(
                                "name", "header", "kind", "header",
                                "fields", List.of(Map.of("field", "orderNumber"))))
                ))
        );

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(!result.ok());
        assertEquals("DOCUMENT_RENDER_FAILED", result.error().code());
        assertTrue(result.error().message().contains("data:"), result.error().message());
    }

    @Test
    void directHtmlBuilderThrowsDocumentRenderExceptionForAnUnsupportedPayload() {
        assertThrows(DocumentRenderException.class, () -> AggregateDocumentPayload.parse(List.of()));
    }

    private static String extractText(byte[] pdfBytes) throws IOException {
        try (PDDocument document = PDDocument.load(pdfBytes)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
