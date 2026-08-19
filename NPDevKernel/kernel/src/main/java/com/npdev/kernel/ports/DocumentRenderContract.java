package com.npdev.kernel.ports;

/**
 * REG-12 Slice 3: adapter-neutral HTML-to-PDF rendering port, mirroring the platform's existing
 * {@code *-inproc}/{@code *-<lib>} adapter-pair pattern (see {@link FileStoreContract}).
 *
 * <p>Input is self-contained HTML+CSS -- the exact shape LNCH-10 Slice 2's print template/stylesheet
 * produces (title/meta/table/footer, inlinable, no external assets) -- so the same markup renders
 * identically in a browser's print dialog and here, server-side.
 *
 * <p><b>R6.3 (RUN-18) correction:</b> this javadoc used to claim rendering "lives behind a
 * port/adapter pair rather than a capability" because it is I/O-heavy, not a pure function -- true
 * of the ORIGINAL REG-12 Slice 3 scope (its one caller was the REST-only {@code
 * DocumentRenderController}), but "capability" here meant the kernel's {@code CapabilityAdapter}/
 * {@code CapabilityCall} dispatch contract, and {@code EmailCapability} (equally I/O-heavy: a real
 * SMTP send) had already been both a typed port AND a capability since LNCH-11 -- the two are not
 * mutually exclusive, and nothing about "I/O-heavy" actually prevented dispatch through a flow step.
 * The real gap this claim was masking: no flow step COULD call {@link #render}, because no adapter
 * implementing this port also implemented {@code CapabilityAdapter}. Both {@code
 * document-render-inproc} and {@code document-render-stub} now implement both, exactly mirroring
 * {@code EmailCapability}'s own adapters -- this typed port is still the direct-call path {@code
 * DocumentRenderController} uses; {@code CapabilityAdapter} is the additional dispatch path a flow's
 * {@code capabilityCall} step (capability {@code documentRender}, operation {@code render}) uses.
 */
public interface DocumentRenderContract {

    /** Page size for {@link RenderOptions}. Values match CSS {@code @page { size: ... }} keywords. */
    enum PageSize {
        A4,
        LETTER
    }

    /**
     * Rendering options a declared {@code document} can specify. {@code marginMm} is a single
     * uniform margin (all four sides) -- enough for v1's pick-list/packing-slip shape; per-side
     * margins are a straightforward later add if a real use case needs them.
     */
    record RenderOptions(PageSize pageSize, double marginMm) {
        public static RenderOptions defaults() {
            return new RenderOptions(PageSize.A4, 20.0);
        }
    }

    /**
     * Renders {@code html} (a complete, self-contained HTML document -- inline or {@code <style>}
     * CSS, no external stylesheet/image fetches) to PDF bytes.
     *
     * @throws DocumentRenderException if the input cannot be parsed/rendered.
     */
    byte[] render(String html, RenderOptions options);

    /** Wraps a rendering failure (malformed HTML, an adapter-specific library error). */
    class DocumentRenderException extends RuntimeException {
        public DocumentRenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
