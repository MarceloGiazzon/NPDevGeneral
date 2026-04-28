package com.finalexec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.npdev.model.RuntimePluginPackageDescriptor;
import com.finalexec.npdev.service.PluginPackageSchemaValidator;
import com.finalexec.npdev.service.RuntimePluginPackageAdmissionEvaluator;
import com.finalexec.npdev.service.RuntimePluginPackageDescriptorLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginPackageAdmissionEvaluatorTest {

    private final RuntimePluginPackageDescriptorLoader loader = new RuntimePluginPackageDescriptorLoader(
            new ObjectMapper(),
            new PluginPackageSchemaValidator()
    );

    @Test
    void admitsCompatiblePackage() {
        RuntimePluginPackageDescriptor descriptor =
                loader.load("npdev/plugin-packages/notification-inproc.package.json");
        RuntimePluginPackageAdmissionEvaluator evaluator =
                new RuntimePluginPackageAdmissionEvaluator(
                        new com.finalexec.npdev.service.RuntimePluginPackageCompatibilityEvaluator(
                                "1.0",
                                "1.0.0",
                                "0.1.0",
                                "1.0",
                                "npdev/plugins/default.plugin-manifest.json"
                        ),
                        new com.finalexec.npdev.service.RuntimePluginPackageTrustEvaluator(
                                java.util.List.of("internal", "local-dev"),
                                true
                        )
                );

        RuntimePluginPackageAdmissionEvaluator.AdmissionDecision decision = evaluator.evaluate(descriptor);

        assertTrue(decision.admitted());
        assertEquals("admitted", decision.status());
        assertEquals("compatible", decision.compatibilityEvaluation().status());
        assertEquals("trusted", decision.trustEvaluation().status());
    }

    @Test
    void rejectsPackageThatRequiresNewerNpdevVersion() {
        RuntimePluginPackageDescriptor descriptor =
                loader.load("npdev/plugin-packages/notification-incompatible.package.json");
        RuntimePluginPackageAdmissionEvaluator evaluator =
                new RuntimePluginPackageAdmissionEvaluator(
                        new com.finalexec.npdev.service.RuntimePluginPackageCompatibilityEvaluator(
                                "1.0",
                                "1.0.0",
                                "0.1.0",
                                "1.0",
                                "npdev/plugins/default.plugin-manifest.json"
                        ),
                        new com.finalexec.npdev.service.RuntimePluginPackageTrustEvaluator(
                                java.util.List.of("internal", "local-dev"),
                                true
                        )
                );

        RuntimePluginPackageAdmissionEvaluator.AdmissionDecision decision = evaluator.evaluate(descriptor);

        assertFalse(decision.admitted());
        assertEquals("rejected", decision.status());
        assertEquals("compatibility", decision.rejectionCategory());
        assertEquals("NPDEV_VERSION_TOO_LOW", decision.reasonCode());
    }

    @Test
    void rejectsUnsupportedTrustModePackage() {
        RuntimePluginPackageDescriptor descriptor =
                loader.load("npdev/plugin-packages/notification-untrusted.package.json");
        RuntimePluginPackageAdmissionEvaluator evaluator =
                new RuntimePluginPackageAdmissionEvaluator(
                        new com.finalexec.npdev.service.RuntimePluginPackageCompatibilityEvaluator(
                                "1.0",
                                "1.0.0",
                                "0.1.0",
                                "1.0",
                                "npdev/plugins/default.plugin-manifest.json"
                        ),
                        new com.finalexec.npdev.service.RuntimePluginPackageTrustEvaluator(
                                java.util.List.of("internal", "local-dev"),
                                true
                        )
                );

        RuntimePluginPackageAdmissionEvaluator.AdmissionDecision decision = evaluator.evaluate(descriptor);

        assertFalse(decision.admitted());
        assertEquals("trust", decision.rejectionCategory());
        assertEquals("UNSUPPORTED_TRUST_MODE", decision.reasonCode());
    }

    @Test
    void admitsStep1TrustedFilesystemManifest(@TempDir Path tempDir) throws IOException {
        Path descriptorPath = tempDir.resolve("notification-valid.package.json");
        Files.writeString(descriptorPath, """
                {
                  "packageId": "notification-valid",
                  "version": "1.0.0",
                  "trustLevel": "trusted",
                  "compatibility": {
                    "npdevMinVersion": "0.1.0",
                    "npdevMaxVersion": "0.9.999"
                  },
                  "capabilities": [
                    {
                      "capability": "notification",
                      "adapterId": "notification-warning-external",
                      "operations": ["send"]
                    }
                  ]
                }
                """);

        RuntimePluginPackageDescriptor descriptor = loader.load(descriptorPath.toString());
        RuntimePluginPackageAdmissionEvaluator evaluator =
                new RuntimePluginPackageAdmissionEvaluator(
                        new com.finalexec.npdev.service.RuntimePluginPackageCompatibilityEvaluator(
                                "1.0",
                                "1.0.0",
                                "0.1.0",
                                "1.0",
                                "npdev/plugins/default.plugin-manifest.json"
                        ),
                        new com.finalexec.npdev.service.RuntimePluginPackageTrustEvaluator(
                                java.util.List.of("internal", "local-dev"),
                                true
                        )
                );

        RuntimePluginPackageAdmissionEvaluator.AdmissionDecision decision = evaluator.evaluate(descriptor);

        assertTrue(decision.admitted());
        assertEquals("admitted", decision.status());
        assertEquals("compatible", decision.compatibilityEvaluation().status());
        assertEquals("trusted", decision.trustEvaluation().status());
    }
}
