package com.finalexec;

import com.finalexec.api.PublicationExecutorController;
import com.finalexec.api.PublicationRollbackExecutorController;
import com.finalexec.api.RealPublicationExecutorController;
import com.finalexec.api.RollbackExecutionController;
import com.finalexec.api.SemanticBehaviorWriteBackController;
import com.finalexec.api.SemanticPublicationMappingController;
import com.finalexec.api.SourceMutationApprovalGateController;
import com.finalexec.api.StructuralPublicationMappingController;
import com.finalexec.npdev.service.PublicationExecutorService;
import com.finalexec.npdev.service.PublicationRollbackExecutorService;
import com.finalexec.npdev.service.RealPublicationExecutorService;
import com.finalexec.npdev.service.RollbackExecutionService;
import com.finalexec.npdev.service.SemanticBehaviorWriteBackCanonicalizationService;
import com.finalexec.npdev.service.SemanticBehaviorWriteBackService;
import com.finalexec.npdev.service.SemanticPublicationMappingService;
import com.finalexec.npdev.service.SourceMutationApprovalGateService;
import com.finalexec.npdev.service.StructuralPublicationMappingService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The publication / rollback / semantic write-back controllers drive the SHARED model and source
 * pipeline and keep one untenanted history, so a tenant ADMIN (which every anonymous caller is, in an
 * auth.mode=none app) must not reach their POSTs or history reads -- SUPERUSER only, the
 * MetadataHotSwapController /apply posture. Summaries are static rule metadata and stay ADMIN.
 */
class SharedModelMutationSuperUserGateTest {

    private static final String REAL = "/api/admin/real-publication-executor";
    private static final String EXECUTOR = "/api/admin/publication-executor";
    private static final String ROLLBACK = "/api/admin/publication-rollback";
    private static final String ROLLBACK_EXECUTION = "/api/admin/rollback-execution";
    private static final String APPROVAL = "/api/admin/source-mutation-approval";
    private static final String SEMANTIC_MAPPING = "/api/admin/semantic-publication-mapping";
    private static final String STRUCTURAL_MAPPING = "/api/admin/structural-publication-mapping";
    private static final String WRITEBACK = "/api/admin/model/semantic-behavior-writeback";

    static Stream<String> superUserOnlyEndpoints() {
        return Stream.of(
                "GET " + REAL + "/history", "POST " + REAL,
                "GET " + EXECUTOR + "/history", "POST " + EXECUTOR,
                "GET " + ROLLBACK + "/history", "POST " + ROLLBACK,
                "GET " + ROLLBACK_EXECUTION + "/history", "POST " + ROLLBACK_EXECUTION,
                "GET " + APPROVAL + "/history", "POST " + APPROVAL + "/decision",
                "GET " + SEMANTIC_MAPPING + "/history", "POST " + SEMANTIC_MAPPING,
                "GET " + STRUCTURAL_MAPPING + "/history", "POST " + STRUCTURAL_MAPPING + "/map",
                "GET " + WRITEBACK + "/history", "GET " + WRITEBACK + "/execution/history",
                "POST " + WRITEBACK, "POST " + WRITEBACK + "/apply",
                "GET " + WRITEBACK + "/canonicalization/history", "POST " + WRITEBACK + "/canonicalization/plan");
    }

    static Stream<String> adminSummaryEndpoints() {
        return Stream.of(REAL, EXECUTOR, ROLLBACK, ROLLBACK_EXECUTION, APPROVAL, SEMANTIC_MAPPING,
                STRUCTURAL_MAPPING, WRITEBACK, WRITEBACK + "/canonicalization");
    }

    @ParameterizedTest
    @MethodSource("superUserOnlyEndpoints")
    void tenantAdminIsRefused(String endpoint) throws Exception {
        assertEquals(403, status(Set.of("ADMIN", "USER", "DEBUG"), endpoint));
    }

    @ParameterizedTest
    @MethodSource("superUserOnlyEndpoints")
    void superUserPassesTheGate(String endpoint) throws Exception {
        int status = status(Set.of("ADMIN", "SUPERUSER"), endpoint);
        assertNotEquals(403, status);
        assertEquals(2, status / 100, endpoint + " answered " + status);
    }

    @ParameterizedTest
    @MethodSource("adminSummaryEndpoints")
    void summaryStaysReadableByAdmin(String path) throws Exception {
        assertEquals(200, status(Set.of("ADMIN"), "GET " + path));
    }

    private static int status(Set<String> roles, String endpoint) throws Exception {
        RuntimeContextService runtimeContextService = Mockito.mock(RuntimeContextService.class);
        when(runtimeContextService.currentContext(any()))
                .thenReturn(ExecutionContext.of("tenant-a", "actor-a").withRoles(roles));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controllers(runtimeContextService).toArray()).build();

        String[] parts = endpoint.split(" ", 2);
        MockHttpServletRequestBuilder request = "POST".equals(parts[0])
                ? post(parts[1]).contentType(MediaType.APPLICATION_JSON).content("{}")
                : get(parts[1]);
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    private static List<Object> controllers(RuntimeContextService runtimeContextService) {
        return List.of(
                new RealPublicationExecutorController(serviceMock(RealPublicationExecutorService.class), runtimeContextService),
                new PublicationExecutorController(serviceMock(PublicationExecutorService.class), runtimeContextService),
                new PublicationRollbackExecutorController(serviceMock(PublicationRollbackExecutorService.class), runtimeContextService),
                new RollbackExecutionController(serviceMock(RollbackExecutionService.class), runtimeContextService),
                new SourceMutationApprovalGateController(serviceMock(SourceMutationApprovalGateService.class), runtimeContextService),
                new SemanticPublicationMappingController(serviceMock(SemanticPublicationMappingService.class), runtimeContextService),
                new StructuralPublicationMappingController(serviceMock(StructuralPublicationMappingService.class), runtimeContextService),
                new SemanticBehaviorWriteBackController(
                        serviceMock(SemanticBehaviorWriteBackService.class),
                        serviceMock(SemanticBehaviorWriteBackCanonicalizationService.class),
                        runtimeContextService));
    }

    /** Every Map-returning service method answers an empty map, so a call that passes the gate is a 2xx. */
    private static <T> T serviceMock(Class<T> type) {
        return Mockito.mock(type, invocation ->
                Map.class.isAssignableFrom(invocation.getMethod().getReturnType()) ? Map.of() : null);
    }
}
