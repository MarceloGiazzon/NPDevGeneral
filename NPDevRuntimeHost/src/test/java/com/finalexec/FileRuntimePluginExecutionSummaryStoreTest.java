package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.service.FileRuntimePluginExecutionSummaryStore;
import com.finalexec.npdev.service.SandboxedPluginExecutionResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileRuntimePluginExecutionSummaryStoreTest {

    @Test
    void persistsExecutionSummariesAcrossStoreInstances() throws Exception {
        Path tempFile = Files.createTempFile("npdev-plugin-executions-", ".jsonl");
        Files.deleteIfExists(tempFile);

        FileRuntimePluginExecutionSummaryStore store = new FileRuntimePluginExecutionSummaryStore(
                new ObjectMapper(),
                tempFile
        );
        store.append(new SandboxedPluginExecutionResult.Summary(
                "SUCCESS",
                "notification-inproc-plugin",
                "notification-inproc",
                "notification",
                "send",
                "notificationInProcCapabilityAdapter",
                "notification-inproc-package",
                "1.0.0",
                "npdev/plugin-packages/notification-inproc.package.json",
                "runtimerefbundle",
                "built-in://notification-inproc",
                "classpath-artifact-provider",
                "classpath-artifact",
                "runtimeRefBundle",
                Map.of("adapterId", "notification-inproc"),
                "corr-1",
                "",
                "",
                1000,
                10
        ));

        FileRuntimePluginExecutionSummaryStore reloaded = new FileRuntimePluginExecutionSummaryStore(
                new ObjectMapper(),
                tempFile
        );
        List<SandboxedPluginExecutionResult.Summary> summaries = reloaded.recent(10);

        assertEquals(1, summaries.size());
        assertEquals("notification-inproc", summaries.get(0).adapterId());
        assertEquals("notification-inproc-package", summaries.get(0).selectedPackageId());
        assertEquals("1.0.0", summaries.get(0).selectedPackageVersion());
        assertEquals("npdev/plugin-packages/notification-inproc.package.json", summaries.get(0).selectedPackagePath());
        assertEquals("file", reloaded.diagnostics().get("storageKind"));
        assertTrue(String.valueOf(reloaded.diagnostics().get("storePath")).contains("npdev-plugin-executions-"));
    }
}
