package com.finalexec.config;

import com.npdev.adapters.documentrender.inproc.DocumentRenderInProcAdapter;
import com.npdev.adapters.documentrender.stub.DocumentRenderStubAdapter;
import com.npdev.kernel.ports.DocumentRenderContract;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LNCH-10 Slice 3 (REG-12): wires the {@link DocumentRenderContract} adapter by config --
 * {@code npdev.documentrender.provider: inproc | stub}, defaulting to {@code inproc} (pure-JVM
 * OpenHTMLtoPDF, no native/display deps) so an unconfigured app renders real PDFs out of the box.
 * {@code stub} lets an app deliberately opt out (see {@link DocumentRenderStubAdapter}'s javadoc)
 * without carrying the PDFBox dependency's runtime cost. Mirrors {@link NpdevFileStoreConfig}'s
 * provider-select pattern exactly.
 */
@Configuration
public class NpdevDocumentRenderConfig {

    @Bean
    @ConditionalOnProperty(name = "npdev.documentrender.provider", havingValue = "inproc", matchIfMissing = true)
    public DocumentRenderContract inprocDocumentRenderContract() {
        return new DocumentRenderInProcAdapter();
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.documentrender.provider", havingValue = "stub")
    public DocumentRenderContract stubDocumentRenderContract() {
        return new DocumentRenderStubAdapter();
    }
}
