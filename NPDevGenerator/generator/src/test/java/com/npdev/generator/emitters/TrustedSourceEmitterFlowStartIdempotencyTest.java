package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedSourceEmitterFlowStartIdempotencyTest {
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

    private static String packagedProofSource() throws IOException {
        List<Path> candidates = List.of(
                Path.of("src/test/java/com/npdev/generator/emitters/TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest.java"),
                Path.of("generator/src/test/java/com/npdev/generator/emitters/TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest.java"),
                Path.of("NPDevGenerator/generator/src/test/java/com/npdev/generator/emitters/TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest.java")
        );

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }

        throw new IOException("Could not locate TrustedSourceEmitterPackagedGeneratedAppRuntimeProofTest.java. Tried: " + candidates);
    }

    @Test
    void generatedFlowStartHasPreDispatchIdempotencyGuard() throws Exception {
        String source = trustedSourceEmitterSource();

        assertTrue(source.contains("flowStartIdempotencyCache"),
                "generated flow runner must keep an idempotency cache for repeated starts");
        assertTrue(source.contains("flowStartIdempotencyKey(descriptor == null ? flowName : descriptor.flowName(), safeRequest)"),
                "generated flow runner must derive a null-safe flow-specific idempotency key");
        assertTrue(source.contains("flowStartIdempotencyCache.get(flowStartIdempotencyKey)"),
                "generated flow runner must check the cache before KernelRunner dispatch");
        assertTrue(source.contains("return cached.asFlowStartIdempotencyReplay();"),
                "duplicate flow start must return a replay response before dispatch");
        assertTrue(source.contains("rememberFlowStartResponse(flowStartIdempotencyKey, response)"),
                "new flow start responses must be recorded for future duplicates");
        assertTrue(source.contains("prevented: generated flow-start idempotency guard reused existing response before KernelRunner dispatch"),
                "duplicate response must truthfully report pre-dispatch prevention");
        assertTrue(source.contains("flowStartIdempotencyStatus"),
                "flow start idempotency status must be visible in API responses");
    }

    @Test
    void item16Item17AndItem18SurfacesRemainPresent() throws Exception {
        String source = trustedSourceEmitterSource();
        String packagedProof = packagedProofSource();

        assertTrue(source.contains("/generated/flows/{flowName}/start"),
                "Item 16 flow start endpoint must remain present");
        assertTrue(source.contains("/generated/flows/{flowName}/events/{eventName}"),
                "Item 16 flow event endpoint must remain present");
        assertTrue(source.contains("kernelFacade.resumeExecution(executionId, safeContext)"),
                "Item 16 resume path must remain present");
        assertTrue(source.contains("window.NPDev.startFlow"),
                "Item 17 flow UI start bridge must remain present");
        assertTrue(source.contains("window.NPDev.renderFlowResultHtml"),
                "Item 17 flow UI renderer must remain present");
        assertTrue(source.contains("/generated/flows/correlations/{correlationId}"),
                "Item 18 flow evidence viewer must remain present");
        assertTrue(packagedProof.contains("flow-evidence-viewer-proof-output.txt"),
                "Item 18 packaged evidence output proof must remain present");
        assertTrue(packagedProof.contains("flow-start-idempotency-proof-output.txt"),
                "Item 19 packaged idempotency proof output must remain present");
    }
}