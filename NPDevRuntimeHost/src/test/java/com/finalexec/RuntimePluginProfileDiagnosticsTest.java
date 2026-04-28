package com.finalexec;

import com.finalexec.npdev.service.RuntimePluginProfileDiagnostics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginProfileDiagnosticsTest {

    @Test
    void allowsCoherentProfileSelection() {
        RuntimePluginProfileDiagnostics diagnostics = new RuntimePluginProfileDiagnostics(
                "default",
                "profile-fallback",
                "npdev/bindings/dev.bindings.json",
                "npdev/plugins/default.plugin-manifest.json",
                "default",
                List.of("notification-inproc", "memory"),
                Map.of("notification", "notification-inproc", "persistence", "memory"),
                List.of(),
                List.of(),
                Map.of("runtimeEnvironment", "default")
        );

        assertDoesNotThrow(diagnostics::assertCoherent);
        assertEquals("default", diagnostics.toSummary().get("activeProfile"));
    }

    @Test
    void rejectsMismatchedProfileSelectionWithReadableError() {
        RuntimePluginProfileDiagnostics diagnostics = new RuntimePluginProfileDiagnostics(
                "warning",
                "explicit",
                "npdev/bindings/alt.bindings.json",
                "npdev/plugins/default.plugin-manifest.json",
                "default",
                List.of("notification-inproc", "memory"),
                Map.of("notification", "notification-warning-inproc"),
                List.of(),
                List.of("notification-warning-inproc"),
                Map.of("runtimeEnvironment", "default")
        );

        IllegalStateException failure = assertThrows(IllegalStateException.class, diagnostics::assertCoherent);

        assertTrue(failure.getMessage().contains("Runtime plugin deployment mismatch"));
        assertTrue(failure.getMessage().contains("deploymentProfile"));
        assertTrue(failure.getMessage().contains("npdev.runtime.deployment-profile"));
        assertTrue(failure.getMessage().contains("missingAdapterIds"));
    }
}
