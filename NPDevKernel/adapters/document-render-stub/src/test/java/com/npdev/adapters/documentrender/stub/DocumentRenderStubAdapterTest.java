package com.npdev.adapters.documentrender.stub;

import com.npdev.kernel.ports.DocumentRenderContract;
import com.npdev.kernel.ports.DocumentRenderContract.RenderOptions;
import org.junit.jupiter.api.Test;

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
}
