package com.finalexec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.api.internal.PublicationExecutorController;
import com.finalexec.api.internal.PublicationRollbackExecutorController;
import com.finalexec.api.internal.PublicationTransactionRecordController;
import com.finalexec.api.internal.RealPublicationExecutorController;
import com.finalexec.api.internal.RollbackExecutionController;
import com.finalexec.api.internal.SemanticPublicationMappingController;
import com.finalexec.api.internal.SourceMutationApprovalGateController;
import com.finalexec.api.internal.SourceMutationAuditRecordController;
import com.finalexec.api.internal.SourceMutationRollbackAnchorController;
import com.finalexec.api.internal.StructuralPublicationMappingController;
import com.finalexec.api.experimental.WorkingDraftSystemController;
import com.finalexec.npdev.service.internal.CanonicalSourceArtifactStore;
import com.finalexec.npdev.service.internal.CanonicalSourceMutationExecutorService;
import com.finalexec.npdev.service.internal.CanonicalSourceValidationService;
import com.finalexec.npdev.service.PublicationChainReferenceResolver;
import com.finalexec.npdev.service.internal.PublicationExecutorService;
import com.finalexec.npdev.service.internal.PublicationRollbackExecutorService;
import com.finalexec.npdev.service.internal.PublicationStateStore;
import com.finalexec.npdev.service.internal.PublicationTransactionRecordService;
import com.finalexec.npdev.service.internal.RealPublicationExecutorService;
import com.finalexec.npdev.service.internal.RollbackExecutionService;
import com.finalexec.npdev.service.internal.RollbackReferenceNormalizer;
import com.finalexec.npdev.service.internal.SemanticPublicationMappingService;
import com.finalexec.npdev.service.internal.SourceMutationApprovalGateService;
import com.finalexec.npdev.service.internal.SourceMutationAuditRecordService;
import com.finalexec.npdev.service.internal.SourceMutationRegenerationArtifactStore;
import com.finalexec.npdev.service.internal.SourceMutationRegenerationService;
import com.finalexec.npdev.service.internal.SourceMutationRollbackAnchorService;
import com.finalexec.npdev.service.internal.StructuralPublicationMappingService;
import com.finalexec.npdev.service.internal.WorkingDraftSystemService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PublicationRollbackE2EIT {
    private static final String API_KEY = "dev-key";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        PublicationChainReferenceResolver referenceResolver = new PublicationChainReferenceResolver(objectMapper);
        RollbackReferenceNormalizer rollbackReferenceNormalizer = new RollbackReferenceNormalizer();
        CanonicalSourceArtifactStore canonicalSourceArtifactStore = new CanonicalSourceArtifactStore(objectMapper);
        SourceMutationRegenerationArtifactStore regenerationArtifactStore =
                new SourceMutationRegenerationArtifactStore(objectMapper);

        WorkingDraftSystemService workingDraftSystemService = new WorkingDraftSystemService(objectMapper);
        SourceMutationApprovalGateService approvalGateService = new SourceMutationApprovalGateService(objectMapper);
        SourceMutationAuditRecordService auditRecordService = new SourceMutationAuditRecordService(objectMapper);
        SourceMutationRollbackAnchorService rollbackAnchorService = new SourceMutationRollbackAnchorService(objectMapper);
        StructuralPublicationMappingService structuralPublicationMappingService =
                new StructuralPublicationMappingService(objectMapper, referenceResolver);
        SemanticPublicationMappingService semanticPublicationMappingService =
                new SemanticPublicationMappingService(objectMapper, referenceResolver);
        PublicationTransactionRecordService publicationTransactionRecordService =
                new PublicationTransactionRecordService(objectMapper, referenceResolver);
        PublicationExecutorService publicationExecutorService =
                new PublicationExecutorService(objectMapper, referenceResolver);
        CanonicalSourceMutationExecutorService canonicalSourceMutationExecutorService =
                new CanonicalSourceMutationExecutorService(objectMapper, referenceResolver, canonicalSourceArtifactStore);
        CanonicalSourceValidationService canonicalSourceValidationService =
                new CanonicalSourceValidationService(objectMapper, referenceResolver, canonicalSourceMutationExecutorService);
        SourceMutationRegenerationService sourceMutationRegenerationService =
                new SourceMutationRegenerationService(
                        objectMapper,
                        referenceResolver,
                        canonicalSourceValidationService,
                        regenerationArtifactStore
                );
        RealPublicationExecutorService realPublicationExecutorService =
                new RealPublicationExecutorService(
                        objectMapper,
                        referenceResolver,
                        canonicalSourceMutationExecutorService,
                        canonicalSourceValidationService,
                        sourceMutationRegenerationService
                );
        PublicationStateStore publicationStateStore = new PublicationStateStore(objectMapper);
        PublicationRollbackExecutorService publicationRollbackExecutorService =
                new PublicationRollbackExecutorService(
                        objectMapper,
                        referenceResolver,
                        publicationStateStore,
                        rollbackReferenceNormalizer
                );
        RollbackExecutionService rollbackExecutionService =
                new RollbackExecutionService(
                        objectMapper,
                        referenceResolver,
                        canonicalSourceArtifactStore,
                        regenerationArtifactStore
                );

        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("admin-tenant", "admin-user").withRoles(Set.of("ADMIN")));

        mockMvc = MockMvcBuilders.standaloneSetup(
                new WorkingDraftSystemController(workingDraftSystemService, runtimeContextService),
                new SourceMutationApprovalGateController(approvalGateService, runtimeContextService),
                new SourceMutationAuditRecordController(auditRecordService, runtimeContextService),
                new SourceMutationRollbackAnchorController(rollbackAnchorService, runtimeContextService),
                new StructuralPublicationMappingController(structuralPublicationMappingService, runtimeContextService),
                new SemanticPublicationMappingController(semanticPublicationMappingService, runtimeContextService),
                new PublicationTransactionRecordController(publicationTransactionRecordService, runtimeContextService),
                new PublicationExecutorController(publicationExecutorService, runtimeContextService),
                new RealPublicationExecutorController(realPublicationExecutorService, runtimeContextService),
                new RollbackExecutionController(rollbackExecutionService, runtimeContextService),
                new PublicationRollbackExecutorController(publicationRollbackExecutorService, runtimeContextService)
        ).build();
    }

    @Test
    void governedPublicationChainPublishesThenRollsBack() throws Exception {
        String runId = UUID.randomUUID().toString();
        String tenantId = "tenant-publication-e2e-" + runId;
        String requestedBy = "publication-rollback-e2e";
        String draftId = "draft-" + runId;
        String transactionReference = "tx-" + runId;
        String sourceMutationReference = "mutation-" + runId;
        String publicationRollbackReference = "publication-rollback-" + runId;
        String rollbackExecutionReference = "rollback-" + runId;

        JsonNode createdDraft = postJson(
                "/api/v1/admin/working-drafts/publish",
                Map.of(
                        "workingDraftId", draftId,
                        "draftSystemName", "Publication Rollback E2E " + runId,
                        "sourceType", "e2e-test",
                        "sourceReference", sourceMutationReference,
                        "requestedBy", requestedBy,
                        "tenantId", tenantId
                ),
                202
        );
        assertEquals(draftId, createdDraft.path("workingDraftId").asText());
        assertEquals("DRAFT", createdDraft.path("lifecycleState").asText());
        assertEquals("UNPUBLISHED", createdDraft.path("publicationStatus").asText());

        JsonNode candidateTransition = postJson(
                "/api/v1/admin/working-drafts/lifecycle/transition",
                Map.of(
                        "workingDraftId", draftId,
                        "lifecycleAction", "promoteDraftCandidate",
                        "requestedBy", requestedBy,
                        "rationale", "Promote draft into publication candidate for E2E"
                ),
                202
        );
        assertEquals("ESTABLISHED", candidateTransition.path("status").asText());
        assertEquals(
                "CANDIDATE_FOR_PUBLICATION",
                candidateTransition.path("lifecycleRecord").path("resultingState").asText()
        );

        JsonNode readyTransition = postJson(
                "/api/v1/admin/working-drafts/lifecycle/transition",
                Map.of(
                        "workingDraftId", draftId,
                        "lifecycleAction", "markReadyForPublication",
                        "requestedBy", requestedBy,
                        "rationale", "Mark draft ready for governed publication"
                ),
                202
        );
        assertEquals("READY_FOR_PUBLICATION", readyTransition.path("updatedDraft").path("lifecycleState").asText());

        JsonNode publishTransition = postJson(
                "/api/v1/admin/working-drafts/lifecycle/transition",
                Map.of(
                        "workingDraftId", draftId,
                        "lifecycleAction", "publishDraft",
                        "requestedBy", requestedBy,
                        "rationale", "Record publication governance handoff"
                ),
                202
        );
        assertEquals("REVIEW_REQUIRED", publishTransition.path("status").asText());
        assertEquals(
                "PUBLICATION_REVIEW_REQUIRED",
                publishTransition.path("lifecycleRecord").path("resultingState").asText()
        );

        JsonNode auditRecord = postJson(
                "/api/v1/admin/source-mutation-audit/record",
                Map.of(
                        "mutationScope", "structural-source-mutation",
                        "mutationReference", sourceMutationReference,
                        "auditEventType", "APPROVAL_RECORDED",
                        "decision", "APPROVED",
                        "requestedBy", requestedBy,
                        "rationale", "Capture auditable approval for the source mutation",
                        "tenantId", tenantId
                ),
                202
        );
        String auditId = auditRecord.path("auditId").asText();
        assertFalse(auditId.isBlank());

        JsonNode approvalRecord = postJson(
                "/api/v1/admin/source-mutation-approval/decision",
                Map.of(
                        "mutationScope", "structural-source-mutation",
                        "mutationReference", sourceMutationReference,
                        "decision", "APPROVED",
                        "requestedBy", requestedBy,
                        "rationale", "Approve the source mutation for publication",
                        "tenantId", tenantId
                ),
                202
        );
        String approvalId = approvalRecord.path("approvalId").asText();
        assertFalse(approvalId.isBlank());

        JsonNode rollbackAnchor = postJson(
                "/api/v1/admin/source-mutation-rollback-anchor/create",
                Map.of(
                        "mutationScope", "structural-source-mutation",
                        "mutationReference", sourceMutationReference,
                        "beforeStateReference", "before-state-" + runId,
                        "requestedBy", requestedBy,
                        "rationale", "Capture rollback anchor for the governed mutation",
                        "tenantId", tenantId
                ),
                202
        );
        String rollbackAnchorId = rollbackAnchor.path("rollbackAnchorId").asText();
        assertFalse(rollbackAnchorId.isBlank());

        JsonNode structuralMapping = postJson(
                "/api/v1/admin/structural-publication-mapping/map",
                Map.of(
                        "publicationBatchId", "batch-" + runId,
                        "tenantId", tenantId,
                        "requestedBy", requestedBy,
                        "rationale", "Map structural publication layer",
                        "sourceMutationReferences", new String[]{sourceMutationReference},
                        "draftReferences", new String[]{draftId},
                        "includedStructuralScopes", new String[]{"concepts", "flows"},
                        "includedConcepts", new String[]{"Patient", "Appointment"}
                ),
                202
        );
        String structuralMappingId = structuralMapping.path("publicationMappingId").asText();
        assertEquals("RESOLVED", structuralMapping.path("integrityStatus").asText());

        JsonNode semanticMapping = postJson(
                "/api/v1/admin/semantic-publication-mapping",
                Map.of(
                        "mappingScope", "canonical-demo",
                        "mappingReference", "semantic-" + runId,
                        "draftReference", draftId,
                        "semanticMutationReferences", new String[]{sourceMutationReference},
                        "requestedBy", requestedBy,
                        "rationale", "Map semantic publication layer",
                        "tenantId", tenantId
                ),
                202
        );
        String semanticMappingId = semanticMapping.path("publicationMappingId").asText();
        assertEquals("RESOLVED", semanticMapping.path("integrityStatus").asText());

        JsonNode publicationTransaction = postJson(
                "/api/v1/admin/publication-transactions",
                Map.of(
                        "transactionScope", "canonical-demo",
                        "transactionReference", transactionReference,
                        "draftReference", draftId,
                        "structuralMappingReferences", new String[]{structuralMappingId},
                        "semanticMappingReferences", new String[]{semanticMappingId},
                        "approvalReferences", new String[]{approvalId},
                        "rollbackAnchorReferences", new String[]{rollbackAnchorId},
                        "requestedBy", requestedBy,
                        "rationale", "Assemble publication transaction",
                        "tenantId", tenantId
                ),
                202
        );
        String publicationTransactionId = publicationTransaction.path("publicationTransactionId").asText();
        assertEquals("RESOLVED", publicationTransaction.path("integrityStatus").asText());

        JsonNode governedPublication = postJson(
                "/api/v1/admin/publication-executor",
                Map.of(
                        "transactionReference", transactionReference,
                        "draftReference", draftId,
                        "requestedBy", requestedBy,
                        "rationale", "Record governed publication execution",
                        "tenantId", tenantId,
                        "executionMode", "governed-publication-v1"
                ),
                202
        );
        String publicationExecutionId = governedPublication.path("publicationExecutionId").asText();
        assertEquals("RECORDED_EXECUTION_ATTEMPT", governedPublication.path("executionStatus").asText());
        assertEquals("PARTIALLY_EXECUTED_REVIEW_REQUIRED", governedPublication.path("executionOutcome").asText());

        JsonNode realPublication = postJson(
                "/api/v1/admin/real-publication-executor",
                Map.of(
                        "transactionReference", transactionReference,
                        "requestedBy", requestedBy,
                        "rationale", "Execute authoritative publication",
                        "tenantId", tenantId,
                        "publicationMode", "real-publication-v1"
                ),
                202
        );
        String realPublicationExecutionId = realPublication.path("realPublicationExecutionId").asText();
        String mutationReference = realPublication.path("mutationReference").asText();
        assertFalse(realPublicationExecutionId.isBlank());
        assertEquals("PUBLISHED", realPublication.path("publicationStatus").asText());
        assertEquals("SAFE_APPLIED", realPublication.path("safeApplyStatus").asText());
        assertEquals("REGENERATED", realPublication.path("regenerationStatus").asText());

        JsonNode rollbackExecution = postJson(
                "/api/v1/admin/rollback-execution",
                Map.of(
                        "rollbackReference", rollbackExecutionReference,
                        "anchorReference", rollbackAnchorId,
                        "transactionReference", transactionReference,
                        "requestedBy", requestedBy,
                        "rationale", "Restore source and regeneration artifacts",
                        "tenantId", tenantId,
                        "rollbackMode", "rollback-execution-v1"
                ),
                202
        );
        assertEquals("ROLLED_BACK_PARTIALLY", rollbackExecution.path("rollbackStatus").asText());
        assertEquals("PARTIAL_RESTORATION_RECORDED", rollbackExecution.path("rollbackOutcome").asText());
        assertTrue(arrayContains(rollbackExecution.path("restoredScopes"), "canonical-source-registry"));
        assertTrue(arrayContains(rollbackExecution.path("restoredScopes"), "regeneration-manifest"));

        JsonNode canonicalRegistry = readJsonFile(Path.of("runtime-data", "canonical-source-artifacts", "canonical-source-registry.json"));
        JsonNode canonicalTenantArtifact = findTenantArtifact(canonicalRegistry, tenantId);
        assertNotNull(canonicalTenantArtifact);
        assertFalse(arrayContains(canonicalTenantArtifact.path("appliedTransactions"), transactionReference));
        assertTrue(arrayContains(canonicalTenantArtifact.path("rolledBackTransactions"), transactionReference));
        assertTrue(arrayContains(canonicalTenantArtifact.path("rolledBackMutations"), mutationReference));

        JsonNode regenerationManifest = readJsonFile(Path.of("runtime-data", "regenerated-artifacts", "regeneration-manifest.json"));
        JsonNode regenerationTenantArtifact = findTenantArtifact(regenerationManifest, tenantId);
        assertNotNull(regenerationTenantArtifact);
        assertFalse(arrayContains(regenerationTenantArtifact.path("regeneratedTransactions"), transactionReference));
        assertTrue(arrayContains(regenerationTenantArtifact.path("rolledBackTransactions"), transactionReference));
        assertTrue(arrayContains(regenerationTenantArtifact.path("rolledBackMutations"), mutationReference));

        JsonNode publicationRollback = postJson(
                "/api/v1/admin/publication-rollback",
                Map.of(
                        "rollbackReference", publicationRollbackReference,
                        "transactionReference", transactionReference,
                        "executionReference", realPublicationExecutionId,
                        "requestedBy", requestedBy,
                        "rationale", "Rollback publication state after artifact restoration",
                        "tenantId", tenantId,
                        "rollbackMode", "publication-rollback-v1"
                ),
                202
        );
        assertEquals("ROLLED_BACK", publicationRollback.path("publicationRollbackStatus").asText());
        assertTrue(arrayContains(publicationRollback.path("restoredScopes"), "publication-execution-state"));
        assertTrue(arrayContains(publicationRollback.path("restoredScopes"), "publication-transaction-state"));

        JsonNode realPublicationHistory = getJson("/api/v1/admin/real-publication-executor/history", 200);
        JsonNode currentPublication = findHistoryItem(
                realPublicationHistory.path("items"),
                "realPublicationExecutionId",
                realPublicationExecutionId
        );
        assertNotNull(currentPublication);
        assertEquals("ROLLED_BACK", currentPublication.path("publicationStatus").asText());
        assertEquals("PUBLICATION_STATE_RESTORED", currentPublication.path("publicationOutcome").asText());

        JsonNode transactionHistory = getJson("/api/v1/admin/publication-transactions/history", 200);
        JsonNode currentTransaction = findHistoryItem(
                transactionHistory.path("items"),
                "publicationTransactionId",
                publicationTransactionId
        );
        assertNotNull(currentTransaction);
        assertEquals("ROLLED_BACK_PUBLICATION_STATE", currentTransaction.path("transactionStatus").asText());
        assertEquals(publicationRollbackReference, currentTransaction.path("publicationRollbackReference").asText());

        JsonNode publicationExecutionHistory = getJson("/api/v1/admin/publication-executor/history", 200);
        JsonNode currentPublicationExecution = findHistoryItem(
                publicationExecutionHistory.path("items"),
                "publicationExecutionId",
                publicationExecutionId
        );
        assertNotNull(currentPublicationExecution);
        assertEquals(transactionReference, currentPublicationExecution.path("transactionReference").asText());

        JsonNode lifecycleHistory = getJson("/api/v1/admin/working-drafts/lifecycle/history", 200);
        JsonNode publishLifecycleRecord = findHistoryItem(
                lifecycleHistory.path("items"),
                "workingDraftId",
                draftId
        );
        assertNotNull(publishLifecycleRecord);

        JsonNode auditHistory = getJson("/api/v1/admin/source-mutation-audit/history", 200);
        JsonNode currentAuditRecord = findHistoryItem(auditHistory.path("items"), "auditId", auditId);
        assertNotNull(currentAuditRecord);
        assertEquals("APPROVAL_RECORDED", currentAuditRecord.path("auditEventType").asText());
        assertEquals("APPROVED", currentAuditRecord.path("decision").asText());
    }

    private JsonNode postJson(String path, Object body, int expectedStatus) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("X-Api-Key", API_KEY)
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode getJson(String path, int expectedStatus) throws Exception {
        String response = mockMvc.perform(get(path)
                        .header("X-Api-Key", API_KEY)
                        .contentType(APPLICATION_JSON))
                .andExpect(status().is(expectedStatus))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode readJsonFile(Path path) throws Exception {
        assertTrue(Files.exists(path), "Expected file to exist: " + path);
        return objectMapper.readTree(path.toFile());
    }

    private static JsonNode findHistoryItem(JsonNode items, String fieldName, String expectedValue) {
        if (!items.isArray()) {
            return null;
        }
        for (JsonNode item : items) {
            if (expectedValue.equals(item.path(fieldName).asText())) {
                return item;
            }
        }
        return null;
    }

    private static JsonNode findTenantArtifact(JsonNode manifest, String tenantId) {
        JsonNode artifacts = manifest.path("artifacts");
        if (!artifacts.isArray()) {
            return null;
        }
        for (JsonNode artifact : artifacts) {
            if (tenantId.equals(artifact.path("tenantId").asText())) {
                return artifact;
            }
        }
        return null;
    }

    private static boolean arrayContains(JsonNode items, String expectedValue) {
        if (!items.isArray()) {
            return false;
        }
        for (JsonNode item : items) {
            if (expectedValue.equals(item.asText())) {
                return true;
            }
        }
        return false;
    }
}

