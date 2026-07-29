package com.npdev.adapters.documentrender.stub;

import com.npdev.kernel.ports.DocumentRenderContract;

/**
 * REG-12 Slice 3 (P1): the second half of the {@link DocumentRenderContract} adapter pair --
 * honestly a stub, not an external-service adapter (no production PDF-rendering service is wired up
 * in v1; see `docs/archive/programme-history/REG12_DOCUMENT_EXPORT_PLAN.md` Q1). Exists so the port has a genuine second
 * implementation (the platform's {@code *-inproc}/{@code *-<lib>} convention), and so an app can
 * deliberately opt OUT of PDF rendering (e.g. an environment where pulling in PDFBox's footprint is
 * unwanted) by selecting this adapter instead of {@code document-render-inproc} -- it fails loudly
 * and immediately rather than silently, which is the correct behavior for a deliberately-disabled
 * capability.
 */
public final class DocumentRenderStubAdapter implements DocumentRenderContract {

    @Override
    public byte[] render(String html, RenderOptions options) {
        throw new DocumentRenderException(
                "document-render-stub is a deliberate no-op adapter (PDF rendering disabled for this "
                        + "app) -- select npdev.documentrender.provider=inproc to render real PDFs",
                null);
    }
}
