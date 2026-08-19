package com.npdev.adapters.documentrender.stub;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.DocumentRenderContract;
import com.npdev.kernel.ports.DocumentRenderContract.RenderOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The stub adapter must fail loudly and immediately, never silently produce empty/fake PDF bytes. */
class DocumentRenderStubAdapterTest {

    @Test
    void alwaysRefusesToRender() {
        DocumentRenderStubAdapter adapter = new DocumentRenderStubAdapter();

        DocumentRenderContract.DocumentRenderException exception = assertThrows(
                DocumentRenderContract.DocumentRenderException.class,
                () -> adapter.render("<html><body>irrelevant</body></html>", RenderOptions.defaults()));

        assertTrue(exception.getMessage().contains("stub"), exception.getMessage());
    }

    /**
     * R6.3 (RUN-18): the capability dispatch path must fail exactly as loudly as the direct
     * {@link DocumentRenderContract#render} call always has -- a deliberately-disabled adapter must
     * never silently succeed with empty/fake PDF bytes just because it was reached through a flow's
     * capabilityCall step instead of the REST controller.
     */
    @Test
    void invokeViaCapabilityCallAlsoRefusesToRender() {
        DocumentRenderStubAdapter adapter = new DocumentRenderStubAdapter();
        CapabilityCall call = new CapabilityCall(
                "documentRender", "DocumentRenderCapability", "document-render-stub", "render",
                List.of("<html><body>irrelevant</body></html>")
        );

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(!result.ok());
        assertEquals("DOCUMENT_RENDER_FAILED", result.error().code());
        assertTrue(result.error().message().contains("stub"), result.error().message());
    }

    @Test
    void invokeRejectsUnsupportedOperation() {
        DocumentRenderStubAdapter adapter = new DocumentRenderStubAdapter();
        CapabilityCall call = new CapabilityCall(
                "documentRender", "DocumentRenderCapability", "document-render-stub", "delete", List.of()
        );

        CapabilityResult result = adapter.invoke(call, Map.of());

        assertTrue(!result.ok());
        assertEquals("DOCUMENT_RENDER_OPERATION_UNSUPPORTED", result.error().code());
    }
}
