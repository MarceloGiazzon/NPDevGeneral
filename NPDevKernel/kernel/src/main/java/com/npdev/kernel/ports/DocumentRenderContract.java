package com.npdev.kernel.ports;

/**
 * REG-12 Slice 3: adapter-neutral HTML-to-PDF rendering port, mirroring the platform's existing
 * {@code *-inproc}/{@code *-<lib>} adapter-pair pattern (see {@link FileStoreContract}). Rendering a
 * document is I/O- and library-heavy, not a pure function, so it lives behind a port/adapter pair
 * rather than a capability (the register documents capabilities as pure functions).
 *
 * <p>Input is self-contained HTML+CSS -- the exact shape LNCH-10 Slice 2's print template/stylesheet
 * produces (title/meta/table/footer, inlinable, no external assets) -- so the same markup renders
 * identically in a browser's print dialog and here, server-side.
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
