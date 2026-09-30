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
        assertPackaged("com.finalexec.api.PublicationExecutorController");
        assertPackaged("com.finalexec.api.PublicationRollbackExecutorController");
        assertPackaged("com.finalexec.api.PublicationTransactionRecordController");
        assertPackaged("com.finalexec.api.RealPublicationExecutorController");
        assertPackaged("com.finalexec.api.RollbackExecutionController");
        assertPackaged("com.finalexec.api.SemanticPublicationMappingController");
        assertPackaged("com.finalexec.api.SourceMutationApprovalGateController");
        assertPackaged("com.finalexec.api.SourceMutationAuditRecordController");
        assertPackaged("com.finalexec.api.SourceMutationRollbackAnchorController");
        assertPackaged("com.finalexec.api.StructuralPublicationMappingController");
        // REG-138: promoted out of deferredControllers -- the semantic-behavior-writeback
        // endpoints are now reachable in the default supported-core profile.
        assertPackaged("com.finalexec.api.SemanticBehaviorWriteBackController");

        assertNotPackaged("com.finalexec.HelloController");
        assertNotPackaged("com.finalexec.api.experimental.FlowBuilderController"); // remains excluded (old path)
        // REG-163: deferredControllers are now COMPILED (allowedControllers UNION deferredControllers)
        // so RuntimeControllerAllowlistConfig's runtime bean-removal filter has a real bean to remove
        // under the default profile and a real one to KEEP under non-default/experimental --
        // previously they were compiled out of sourceSets.main entirely, making the non-default
        // profile permanently dead code regardless of what ran at runtime. "Packaged" (present on the
        // compiled classpath) is no longer the same question as "active under the default profile";
        // this test only ever asked the former. RuntimePluginPackagesController/RuntimeRefreshController/
        // ModelSyncStatusController all compile cleanly under the new gate, so they move here.
        assertPackaged("com.finalexec.api.RuntimePluginPackagesController");
        assertPackaged("com.finalexec.api.ModelSyncStatusController");
        // REG-168: RuntimeTopologyExplorerController promoted to allowedControllers -- its service
        // dependencies (FlowBuilderService, GovernanceWorkspaceService, CapabilityIntegrationPanelService)
        // are now all in supportedCoreServiceComponents and live in service/, so the
        // dependency chain is fully satisfiable.
        assertPackaged("com.finalexec.api.RuntimeTopologyExplorerController");

        // Wave 4 (2026-09-25): RuntimeRefreshController deleted -- a canned-response stub superseded
        // by MetadataHotSwapController's real /model-reload (REG-208/B28). BetaOnboardingController
        // deleted -- the Beta0 programme it described closed (the project has shipped beta1.x tags
        // since 2026-08-11, per docs/RELEASE_PROCESS.md). FlowBuilderController deleted -- its
        // saveDraft/addStep mutation endpoints had zero callers besides itself (flows are authored in
        // the model); FlowBuilderService.listDrafts()/draftHistory() stay, since
        // RuntimeTopologyExplorerService and CapabilityIntegrationPanelService both still read them.
        assertNotPackaged("com.finalexec.api.internal.RuntimeRefreshController");
        assertNotPackaged("com.finalexec.api.internal.BetaOnboardingController");
        assertNotPackaged("com.finalexec.api.internal.FlowBuilderController");
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
        assertPackaged("com.finalexec.npdev.service.TimeBoundedPluginExecutionEngine");
        assertPackaged("com.finalexec.npdev.service.RuntimePluginPackageDiscoveryService");
        assertPackaged("com.finalexec.npdev.service.RuntimePluginPackageRealizationService");
        assertPackaged("com.finalexec.npdev.service.SupportDiagnosticsService");
        assertPackaged("com.finalexec.npdev.service.TenantStoragePathResolver");
        assertPackaged("com.finalexec.npdev.service.CanonicalSourceArtifactStore");
        assertPackaged("com.finalexec.npdev.service.CanonicalSourceMutationExecutorService");
        assertPackaged("com.finalexec.npdev.service.CanonicalSourceValidationService");
        assertPackaged("com.finalexec.npdev.service.PublicationExecutorService");
        assertPackaged("com.finalexec.npdev.service.PublicationRollbackExecutorService");
        assertPackaged("com.finalexec.npdev.service.PublicationStateStore");
        assertPackaged("com.finalexec.npdev.service.PublicationTransactionRecordService");
        assertPackaged("com.finalexec.npdev.service.RealPublicationExecutorService");
        assertPackaged("com.finalexec.npdev.service.RollbackExecutionService");
        assertPackaged("com.finalexec.npdev.service.RollbackReferenceNormalizer");
        assertPackaged("com.finalexec.npdev.service.SemanticPublicationMappingService");
        assertPackaged("com.finalexec.npdev.service.SourceMutationApprovalGateService");
        assertPackaged("com.finalexec.npdev.service.SourceMutationAuditRecordService");
        assertPackaged("com.finalexec.npdev.service.SourceMutationRegenerationArtifactStore");
        assertPackaged("com.finalexec.npdev.service.SourceMutationRegenerationService");
        assertPackaged("com.finalexec.npdev.service.SourceMutationRollbackAnchorService");
        assertPackaged("com.finalexec.npdev.service.StructuralPublicationMappingService");
        // REG-138: promoted alongside the controller -- execute() now writes a real capabilityCall
        // step into the app's own model source instead of an unread workspace audit file.
        assertPackaged("com.finalexec.npdev.service.SemanticBehaviorWriteBackService");
        assertPackaged("com.finalexec.npdev.service.SemanticBehaviorWriteBackCanonicalizationService");

        assertNotPackaged("com.finalexec.npdev.service.experimental.FlowBuilderService");
        assertNotPackaged("com.finalexec.npdev.service.experimental.PreviewReferenceResolver");
        assertNotPackaged("com.finalexec.npdev.service.experimental.TemplateLibraryManagementService");
        // Wave 4 (2026-09-25): deleted alongside BetaOnboardingController -- see the sibling
        // controller test's own comment.
        assertNotPackaged("com.finalexec.npdev.service.internal.BetaOnboardingService");
        // REG-163: see the sibling controller test's own comment -- nonDefaultServicePatterns
        // services are now compiled so the profile they exist for is reachable at all.
        assertPackaged("com.finalexec.npdev.service.ModelSyncStatusService");
        assertPackaged("com.finalexec.npdev.service.internal.TenantOperationalAdministrationService");
        // REG-168: RuntimeTopologyExplorerService promoted alongside its controller -- all transitive
        // deps now in service/ and supportedCoreServiceComponents.
        assertPackaged("com.finalexec.npdev.service.RuntimeTopologyExplorerService");
        assertPackaged("com.finalexec.npdev.service.FlowBuilderService");
        assertPackaged("com.finalexec.npdev.service.CapabilityIntegrationPanelService");
    }

    private static void assertPackaged(String className) {
        assertDoesNotThrow(() -> Class.forName(className), className + " must remain packaged.");
    }

    private static void assertNotPackaged(String className) {
        assertThrows(ClassNotFoundException.class, () -> Class.forName(className), className + " must be excluded.");
    }
}
