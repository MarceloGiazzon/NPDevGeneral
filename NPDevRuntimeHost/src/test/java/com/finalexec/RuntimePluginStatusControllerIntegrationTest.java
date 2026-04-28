package com.finalexec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuntimePluginStatusControllerIntegrationTest {

    private static final String API_KEY = "dev-key";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesCanonicalPluginRuntimeStatusSummary() throws Exception {
        mockMvc.perform(get("/api/admin/runtime/plugin-status")
                        .header("X-Api-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deploymentProfile").value("default"))
                .andExpect(jsonPath("$.discoveryMode").value("projected-resource"))
                .andExpect(jsonPath("$.discoveredPackages").isArray())
                .andExpect(jsonPath("$.admittedPackages").isArray())
                .andExpect(jsonPath("$.discoveryOperationalMode.demonstratedProfile").value("filesystem"))
                .andExpect(jsonPath("$.statusAudit.governance.admittedPackages").isArray())
                .andExpect(jsonPath("$.externalMediumDemo.pathId").value("filesystem-governed-external-package-demo"))
                .andExpect(jsonPath("$.rejectedPackages").isArray())
                .andExpect(jsonPath("$.trust.policy.allowedModes").isArray())
                .andExpect(jsonPath("$.compatibility.runtime.supportedPackageFormatVersion").value("1.0"))
                .andExpect(jsonPath("$.artifactRealizationStrategies").isArray())
                .andExpect(jsonPath("$.traceability.adapterExecutionTrace").isArray())
                .andExpect(jsonPath("$.selectedAdapterIds").isMap())
                .andExpect(jsonPath("$.selectedPackageIds", hasItem("notification-inproc-package")))
                .andExpect(jsonPath("$.discoveredPackages[0].packageId").value("custom-procedure-package"))
                .andExpect(jsonPath("$.artifactRealizationBoundary.boundaryKind").value("packaged-artifact-runtime-ref-bridge"))
                .andExpect(jsonPath("$.realizationBoundary.boundaryKind").value("runtimeref-provider-catalog"))
                .andExpect(jsonPath("$.resourceOwnership.projectedRuntimeResources").isArray());
    }
}
