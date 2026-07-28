package com.npdev.generator.emitters;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TrustedSourceEmitterFlowUiVisibilityTest {
    // 2.B.2: TrustedSourceEmitter.java was split into several sibling files under this same
    // package (trusted-source manifest/policy/template classes) -- the generated-source text this
    // test greps for now lives across that whole family, not in TrustedSourceEmitter.java alone.
    private static String trustedSourceEmitterSource() throws IOException {
        List<Path> candidates = List.of(
                Path.of("src/main/java/com/npdev/generator/emitters"),
                Path.of("generator/src/main/java/com/npdev/generator/emitters"),
                Path.of("NPDevGenerator/generator/src/main/java/com/npdev/generator/emitters")
        );

        for (Path candidate : candidates) {
            if (Files.isDirectory(candidate)) {
                StringBuilder combined = new StringBuilder();
                try (Stream<Path> files = Files.list(candidate)) {
                    List<Path> javaFiles = files
                            .filter(path -> path.getFileName().toString().endsWith(".java"))
                            .sorted()
                            .toList();
                    for (Path javaFile : javaFiles) {
                        combined.append(Files.readString(javaFile)).append('\n');
                    }
                }
                return combined.toString();
            }
        }

        throw new IOException("Could not locate the com.npdev.generator.emitters source directory. Tried: " + candidates);
    }

    @Test
    void generatedPanelRuntimeExposesFlowUiApisAndHooks() throws Exception {
        String source = trustedSourceEmitterSource();

        assertTrue(source.contains("window.NPDev.startFlow = async function"),
                "generated panel runtime must expose window.NPDev.startFlow");
        assertTrue(source.contains("window.NPDev.resumeFlow = async function"),
                "generated panel runtime must expose window.NPDev.resumeFlow");
        assertTrue(source.contains("window.NPDev.renderFlowResultHtml = function"),
                "generated panel runtime must expose renderFlowResultHtml");
        assertTrue(source.contains("window.NPDev.renderFlowResult = function"),
                "generated panel runtime must expose renderFlowResult");

        assertTrue(source.contains("'/generated/flows/' + encodeURIComponent(flowName) + '/start'"),
                "startFlow must call the generated flow start endpoint");
        assertTrue(source.contains("'/generated/flows/' + encodeURIComponent(flowName) + '/events/' + encodeURIComponent(eventName)"),
                "resumeFlow must call the generated flow event/resume endpoint");

        assertHook(source, "data-npdev-flow-result");
        assertHook(source, "data-npdev-flow-name");
        assertHook(source, "data-npdev-flow-instance-id");
        assertHook(source, "data-npdev-flow-status");
        assertHook(source, "data-npdev-execution-id");
        assertHook(source, "data-npdev-correlation-id");
        assertHook(source, "data-npdev-waiting-status");
        assertHook(source, "data-npdev-resume-status");
        assertHook(source, "data-npdev-capability-id");
        assertHook(source, "data-npdev-dispatch-status");
        assertHook(source, "data-npdev-event-status");
        assertHook(source, "data-npdev-trace-status");
        assertHook(source, "data-npdev-audit-status");
        assertHook(source, "data-npdev-idempotency-status");
        assertHook(source, "data-npdev-correlation-status");
        assertHook(source, "data-npdev-created-count");
        assertHook(source, "data-npdev-side-effect-before");
        assertHook(source, "data-npdev-side-effect-after");
        assertHook(source, "data-npdev-flow-message");
        assertHook(source, "data-npdev-flow-error");
        assertHook(source, "data-npdev-flow-evidence-link");
        assertHook(source, "data-npdev-flow-correlation-evidence-link");
        assertHook(source, "data-npdev-flow-evidence-link-status");

        assertTrue(source.contains("unavailable: not returned by runtime"),
                "missing fields must render truthful unavailable text");
        assertTrue(source.contains("unavailable: runtime returned null"),
                "null fields must render truthful unavailable text");
    }

    @Test
    void existingActionProcedureAndWaitingResumeSurfacesRemainPresent() throws Exception {
        String source = trustedSourceEmitterSource();

        assertTrue(source.contains("window.NPDev.callProcedure"),
                "existing procedure bridge must remain available");
        assertTrue(source.contains("renderActionResult"),
                "existing action result renderer must remain available");
        assertTrue(source.contains("/generated/flows/{flowName}/start"),
                "generated flow start endpoint must remain available");
        assertTrue(source.contains("/generated/flows/{flowName}/events/{eventName}"),
                "generated flow event endpoint must remain available");
        assertTrue(source.contains("/generated/flows/{flowName}/resume"),
                "generated flow resume endpoint must remain available");
        assertTrue(source.contains("kernelFacade.resumeExecution(executionId, safeContext)"),
                "generated waiting/resume path must continue to call KernelFacade resume");
    }

    private static void assertHook(String source, String hook) {
        assertTrue(source.contains(hook), "expected generated flow UI hook: " + hook);
    }
}
