package com.npdev.adapters.documentrender.stub;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.DocumentRenderContract;

import java.util.Map;

/**
 * REG-12 Slice 3 (P1): the second half of the {@link DocumentRenderContract} adapter pair --
 * honestly a stub, not an external-service adapter (no production PDF-rendering service is wired up
 * in v1; see `docs/archive/programme-history/REG12_DOCUMENT_EXPORT_PLAN.md` Q1). Exists so the port has a genuine second
 * implementation (the platform's {@code *-inproc}/{@code *-<lib>} convention), and so an app can
 * deliberately opt OUT of PDF rendering (e.g. an environment where pulling in PDFBox's footprint is
 * unwanted) by selecting this adapter instead of {@code document-render-inproc} -- it fails loudly
 * and immediately rather than silently, which is the correct behavior for a deliberately-disabled
 * capability.
 *
 * <p>R6.3 (RUN-18): also implements {@link CapabilityAdapter} (capability {@code documentRender},
 * operation {@code render}) so it stays a genuine substitutable binding for the capability the same
 * way it already is for {@link DocumentRenderContract} -- {@code invoke} fails exactly the same way
 * {@link #render} always has, never a silent/empty PDF.
 *
 * <p>R5.7 (Roadmap Wave 1 2026-08-19): {@code renderAggregate} (the aggregate/bands/logo document
 * shape {@code document-render-inproc} now renders) fails the identical way -- an app that opts out
 * of PDF rendering opts out of ALL of it, not just the pre-R5.7 flat-grid shape.
 */
public final class DocumentRenderStubAdapter implements CapabilityAdapter, DocumentRenderContract {

    @Override
    public String adapterId() {
        return "document-render-stub";
    }

    @Override
    public String capability() {
        return "documentRender";
    }

    @Override
    public String capabilityType() {
        return "DocumentRenderCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        if (!"render".equals(call.operation()) && !"renderAggregate".equals(call.operation())) {
            return CapabilityResult.failure(
                    "DOCUMENT_RENDER_OPERATION_UNSUPPORTED",
                    "Unsupported documentRender operation: " + call.operation(),
                    CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation())
            );
        }
        try {
            render("", RenderOptions.defaults());
        } catch (DocumentRenderException e) {
            return CapabilityResult.failure("DOCUMENT_RENDER_FAILED", e.getMessage(), CapabilityErrorKind.PERMANENT, Map.of());
        }
        // Unreachable: render() above always throws. No non-exceptional return path exists on this
        // deliberately-disabled adapter.
        throw new IllegalStateException("document-render-stub.render() must always throw");
    }

    @Override
    public byte[] render(String html, RenderOptions options) {
        throw new DocumentRenderException(
                "document-render-stub is a deliberate no-op adapter (PDF rendering disabled for this "
                        + "app) -- select npdev.documentrender.provider=inproc to render real PDFs",
                null);
    }
}
