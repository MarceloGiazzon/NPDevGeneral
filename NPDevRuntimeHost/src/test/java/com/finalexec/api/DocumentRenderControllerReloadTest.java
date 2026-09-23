package com.finalexec.api;

import com.finalexec.config.ModelHolder;
import com.finalexec.npdev.service.AggregateRuntime;
import com.npdev.dsl.v1.compiled.CompiledDocument;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.ports.DocumentRenderContract;
import com.npdev.kernel.ports.FileStoreContract;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * REG-242 (B28 residual): {@link DocumentRenderController} used to inject {@code
 * NPDevModelProvider} directly and resolve {@code documents[]} against the model the JVM booted
 * with, forever -- a {@code documents[]} entry added by a {@code /model-reload} kept 404ing no
 * matter how many reloads followed. Against that shape this test's second half would have thrown
 * the same 404 the first half asserts. Proves the controller now reads through the LIVE {@link
 * ModelHolder} instead: a document absent at construction time but present after {@link
 * ModelHolder#swap} is resolvable on the very next request, without reconstructing the controller.
 */
class DocumentRenderControllerReloadTest {

    private static CompiledModel modelWithDocuments(CompiledDocument... documents) {
        return new CompiledModel(
                "demo", "1.0.0", "v1", Map.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(documents));
    }

    private static CompiledDocument invoiceDocument() {
        return new CompiledDocument("Invoice", "Invoice", "Invoice", "A4", 20.0, Map.of());
    }

    @Test
    void aDocumentAddedByAReloadIsResolvableWithoutReconstructingTheController() throws Exception {
        ModelHolder modelHolder = new ModelHolder(modelWithDocuments());

        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("tenant-a", "test-actor"));

        ConceptGateway conceptGateway = Mockito.mock(ConceptGateway.class);
        when(conceptGateway.query(any(), any())).thenReturn(new ConceptPage(List.of(), 0, false));

        DocumentRenderContract documentRenderer = Mockito.mock(DocumentRenderContract.class);
        when(documentRenderer.render(any(), any())).thenReturn(new byte[] {1, 2, 3});

        DocumentRenderController controller = new DocumentRenderController(
                modelHolder,
                runtimeContextService,
                conceptGateway,
                documentRenderer,
                Mockito.mock(AggregateRuntime.class),
                Mockito.mock(FileStoreContract.class)
        );

        MockHttpServletRequest requestBeforeReload =
                new MockHttpServletRequest("GET", "/api/documents/Invoice/render.pdf");
        MockHttpServletResponse responseBeforeReload = new MockHttpServletResponse();
        ResponseStatusException notFound = assertThrows(ResponseStatusException.class,
                () -> controller.renderPdf(requestBeforeReload, responseBeforeReload, "Invoice"),
                "a document not yet declared in the model must 404, not render stale content");
        assertEquals(404, notFound.getStatusCode().value());

        modelHolder.swap(modelWithDocuments(invoiceDocument()));

        MockHttpServletRequest requestAfterReload =
                new MockHttpServletRequest("GET", "/api/documents/Invoice/render.pdf");
        MockHttpServletResponse responseAfterReload = new MockHttpServletResponse();
        controller.renderPdf(requestAfterReload, responseAfterReload, "Invoice");

        assertEquals(200, responseAfterReload.getStatus(),
                "a document ADDED by a reload must render on the next request, without "
                        + "reconstructing DocumentRenderController");
        assertEquals("application/pdf", responseAfterReload.getContentType());
        assertEquals("inline; filename=\"Invoice.pdf\"", responseAfterReload.getHeader("Content-Disposition"));
    }
}
