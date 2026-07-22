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
        assertPackaged("com.finalexec.api.internal.PublicationExecutorController");
        assertPackaged("com.finalexec.api.internal.PublicationRollbackExecutorController");
        assertPackaged("com.finalexec.api.internal.PublicationTransactionRecordController");
        assertPackaged("com.finalexec.api.internal.RealPublicationExecutorController");
        assertPackaged("com.finalexec.api.internal.RollbackExecutionController");
        assertPackaged("com.finalexec.api.internal.SemanticPublicationMappingController");
        assertPackaged("com.finalexec.api.internal.SourceMutationApprovalGateController");
        assertPackaged("com.finalexec.api.internal.SourceMutationAuditRecordController");
        assertPackaged("com.finalexec.api.internal.SourceMutationRollbackAnchorController");
        assertPackaged("com.finalexec.api.internal.StructuralPublicationMappingController");

        assertNotPackaged("com.finalexec.HelloController");
        assertNotPackaged("com.finalexec.api.internal.ModelSyncStatusController");
        assertNotPackaged("com.finalexec.api.experimental.FlowBuilderController");
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
        assertPackaged("com.finalexec.npdev.service.internal.CanonicalSourceArtifactStore");
        assertPackaged("com.finalexec.npdev.service.internal.CanonicalSourceMutationExecutorService");
        assertPackaged("com.finalexec.npdev.service.internal.CanonicalSourceValidationService");
        assertPackaged("com.finalexec.npdev.service.internal.PublicationExecutorService");
        assertPackaged("com.finalexec.npdev.service.internal.PublicationRollbackExecutorService");
        assertPackaged("com.finalexec.npdev.service.internal.PublicationStateStore");
        assertPackaged("com.finalexec.npdev.service.internal.PublicationTransactionRecordService");
        assertPackaged("com.finalexec.npdev.service.internal.RealPublicationExecutorService");
        assertPackaged("com.finalexec.npdev.service.internal.RollbackExecutionService");
        assertPackaged("com.finalexec.npdev.service.internal.RollbackReferenceNormalizer");
        assertPackaged("com.finalexec.npdev.service.internal.SemanticPublicationMappingService");
        assertPackaged("com.finalexec.npdev.service.internal.SourceMutationApprovalGateService");
        assertPackaged("com.finalexec.npdev.service.internal.SourceMutationAuditRecordService");
        assertPackaged("com.finalexec.npdev.service.internal.SourceMutationRegenerationArtifactStore");
        assertPackaged("com.finalexec.npdev.service.internal.SourceMutationRegenerationService");
        assertPackaged("com.finalexec.npdev.service.internal.SourceMutationRollbackAnchorService");
        assertPackaged("com.finalexec.npdev.service.internal.StructuralPublicationMappingService");

        assertNotPackaged("com.finalexec.npdev.service.experimental.FlowBuilderService");
        assertNotPackaged("com.finalexec.npdev.service.internal.ModelSyncStatusService");
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
