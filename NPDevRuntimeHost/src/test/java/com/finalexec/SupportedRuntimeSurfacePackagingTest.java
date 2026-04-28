package com.finalexec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SupportedRuntimeSurfacePackagingTest {

    @Test
    void generatedDefaultArtifactPackagesOnlySupportedControllerSurface() {
        assertPackaged("com.finalexec.api.DirectExecutionGatewayController");
        assertPackaged("com.finalexec.api.ExecutionMonitorController");
        assertPackaged("com.finalexec.api.RuntimeMetadataController");
        assertPackaged("com.finalexec.api.RuntimeMetadataValidationController");
        assertPackaged("com.finalexec.api.RuntimePluginStatusController");
        assertPackaged("com.finalexec.api.RuntimeSchedulesController");
        assertPackaged("com.finalexec.api.RuntimeUiMetadataController");
        assertPackaged("com.finalexec.api.SupportDiagnosticsController");

        assertNotPackaged("com.finalexec.HelloController");
        assertNotPackaged("com.finalexec.api.internal.ModelSyncStatusController");
        assertNotPackaged("com.finalexec.api.experimental.FlowBuilderController");
        assertNotPackaged("com.finalexec.api.internal.PublicationExecutorController");
        assertNotPackaged("com.finalexec.api.internal.RuntimePluginPackagesController");
        assertNotPackaged("com.finalexec.api.internal.RuntimeRefreshController");
        assertNotPackaged("com.finalexec.api.internal.SemanticBehaviorWriteBackController");
        assertNotPackaged("com.finalexec.api.internal.RuntimeTopologyExplorerController");
    }

    @Test
    void generatedDefaultArtifactPackagesOnlySupportedRuntimeServices() {
        assertPackaged("com.finalexec.npdev.service.BetaSecurityRoleEvaluator");
        assertPackaged("com.finalexec.npdev.service.CrossTenantGovernanceService");
        assertPackaged("com.finalexec.npdev.service.ExecutionMonitorService");
        assertPackaged("com.finalexec.npdev.service.FileRuntimePluginExecutionSummaryStore");
        assertPackaged("com.finalexec.npdev.service.PanelRuntime");
        assertPackaged("com.finalexec.npdev.service.PermissionAwareUiMetadataService");
        assertPackaged("com.finalexec.npdev.service.PluginExecutionPolicyEvaluator");
        assertPackaged("com.finalexec.npdev.service.PublicationChainReferenceResolver");
        assertPackaged("com.finalexec.npdev.service.RuntimeMetadataService");
        assertPackaged("com.finalexec.npdev.service.RuntimeMetadataValidationService");
        assertPackaged("com.finalexec.npdev.service.RuntimePluginPackageCatalog");
        assertPackaged("com.finalexec.npdev.service.RuntimePluginProfileResolver");
        assertPackaged("com.finalexec.npdev.service.SandboxedPluginExecutionEngine");
        assertPackaged("com.finalexec.npdev.service.RuntimePluginPackageDiscoveryService");
        assertPackaged("com.finalexec.npdev.service.RuntimePluginPackageRealizationService");
        assertPackaged("com.finalexec.npdev.service.SupportDiagnosticsService");
        assertPackaged("com.finalexec.npdev.service.TenantStoragePathResolver");

        assertNotPackaged("com.finalexec.npdev.service.experimental.FlowBuilderService");
        assertNotPackaged("com.finalexec.npdev.service.internal.ModelSyncStatusService");
        assertNotPackaged("com.finalexec.npdev.service.internal.PublicationExecutorService");
        assertNotPackaged("com.finalexec.npdev.service.experimental.PreviewReferenceResolver");
        assertNotPackaged("com.finalexec.npdev.service.internal.SemanticBehaviorWriteBackService");
        assertNotPackaged("com.finalexec.npdev.service.experimental.TemplateLibraryManagementService");
        assertNotPackaged("com.finalexec.npdev.service.internal.RuntimeTopologyExplorerService");
        assertNotPackaged("com.finalexec.npdev.service.internal.TenantOperationalAdministrationService");
    }

    private static void assertPackaged(String className) {
        assertDoesNotThrow(() -> Class.forName(className), className + " must remain packaged.");
    }

    private static void assertNotPackaged(String className) {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(className), className + " must be excluded.");
    }
}
