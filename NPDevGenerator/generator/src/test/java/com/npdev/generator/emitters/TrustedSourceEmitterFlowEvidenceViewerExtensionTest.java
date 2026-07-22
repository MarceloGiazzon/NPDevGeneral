package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedSourceEmitterFlowEvidenceViewerExtensionTest {
    private static String trustedSourceEmitterSource() throws IOException {
        List<Path> candidates = List.of(
                Path.of("src/main/java/com/npdev/generator/emitters/TrustedSourceEmitter.java"),
                Path.of("generator/src/main/java/com/npdev/generator/emitters/TrustedSourceEmitter.java"),
                Path.of("NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters/TrustedSourceEmitter.java")
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }

        throw new IOException("Could not locate TrustedSourceEmitter.java. Tried: " + candidates);
    }

    @Test
    void generatedTrustedSourceControllerExposesFlowEvidenceViewerAliases() throws Exception {
        String source = trustedSourceEmitterSource();

        assertTrue(source.contains("/generated/flows/executions/{executionId}"),
                "flow execution evidence viewer endpoint must be generated");
        assertTrue(source.contains("/generated/flows/instances/{flowInstanceId}"),
                "flow instance evidence viewer endpoint must be generated");
        assertTrue(source.contains("/generated/flows/correlations/{correlationId}"),
                "flow correlation evidence viewer endpoint must be generated");
        assertTrue(source.contains("flowExecutionEvidence"),
                "flow execution evidence handler must be generated");
        assertTrue(source.contains("flowInstanceEvidence"),
                "flow instance evidence handler must be generated");
        assertTrue(source.contains("flowCorrelationEvidence"),
                "flow correlation evidence handler must be generated");
        assertTrue(source.contains("viewerType"),
                "flow evidence response must expose viewerType");
        assertTrue(source.contains("flow-execution"),
                "flow execution response must identify viewer type");
        assertTrue(source.contains("flow-instance"),
                "flow instance response must identify viewer type");
        assertTrue(source.contains("flow-correlation"),
                "flow correlation response must identify viewer type");
        assertTrue(source.contains("sourceEvidenceEndpoint"),
                "flow evidence aliases must expose delegated source endpoint");
        assertTrue(source.contains("sourceEvidenceStatus"),
                "flow evidence aliases must expose source evidence status");
        assertTrue(source.contains("truth"),
                "flow evidence aliases must truthfully describe alias behavior");
    }

    @Test
    void item16AndItem17SurfacesRemainPresent() throws Exception {
        String source = trustedSourceEmitterSource();

        assertTrue(source.contains("/generated/flows/{flowName}/start"),
                "Item 16 generated flow start endpoint must remain present");
        assertTrue(source.contains("/generated/flows/{flowName}/events/{eventName}"),
                "Item 16 generated flow event endpoint must remain present");
        assertTrue(source.contains("kernelFacade.resumeExecution(executionId, safeContext)"),
                "Item 16 resume path must remain present");
        assertTrue(source.contains("window.NPDev.startFlow"),
                "Item 17 startFlow bridge must remain present");
        assertTrue(source.contains("window.NPDev.resumeFlow"),
                "Item 17 resumeFlow bridge must remain present");
        assertTrue(source.contains("window.NPDev.renderFlowResultHtml"),
                "Item 17 flow renderer must remain present");
        assertTrue(source.contains("data-npdev-flow-evidence-link"),
                "Item 17 flow evidence hook must remain present");
        assertTrue(source.contains("data-npdev-flow-correlation-evidence-link"),
                "Item 17 flow correlation evidence hook must remain present");
    }
}