package com.npdev.generator.emitters;

/**
 * Static Java source template for {@code GeneratedActionKernelRunner}: the generated class that
 * drives a single generated-action invocation through idempotency, capability dispatch,
 * evidence (event/audit/trace) recording, and correlation bookkeeping.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2) into its own file purely because of size
 * -- this is one pure, parameterless string-literal method.
 */
final class TrustedActionKernelRunnerTemplate {

    private TrustedActionKernelRunnerTemplate() {
    }

    static String generatedActionKernelRunnerSource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.generated.runtime.service.KernelFacade;
                import com.npdev.kernel.CapabilityCall;
                import com.npdev.kernel.CapabilityResult;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.capability.IdempotencyRecord;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptListRequest;
                import com.npdev.kernel.concepts.ConceptRecord;
                import com.npdev.kernel.concepts.ConceptWriteRequest;
                import com.npdev.kernel.events.EventEnvelope;
                import com.npdev.kernel.ports.CapabilityDispatcher;
                import org.springframework.stereotype.Service;

                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;
                import java.util.Optional;
                import java.util.concurrent.ConcurrentHashMap;
                import java.util.UUID;

                @Service
                public class GeneratedActionKernelRunner {
                    private final ConceptGateway conceptGateway;
                    private final KernelFacade kernelFacade;
                    private final CapabilityDispatcher capabilityDispatcher;

                    public GeneratedActionKernelRunner(
                            ConceptGateway conceptGateway,
                            KernelFacade kernelFacade
                    ) {
                        this.conceptGateway = conceptGateway;
                        this.kernelFacade = kernelFacade;
                        this.capabilityDispatcher = GeneratedActionCapabilityDispatcherFactory.create();
                    }

                    public GeneratedActionExecutionResponse run(
                            String actionName,
                            GeneratedActionExecutionRequest request,
                            ExecutionContext context
                    ) {
                        GeneratedActionExecutionRequest safeRequest = request == null
                                ? new GeneratedActionExecutionRequest("", "", "", Map.of())
                                : request;
                        ExecutionContext safeContext = context == null ? ExecutionContext.anonymous() : context;
                        String executionId = firstNonBlank(safeRequest.executionId(), UUID.randomUUID().toString());
                        String correlationId = firstNonBlank(
                                safeRequest.correlationId(),
                                safeContext.correlationId(),
                                UUID.randomUUID().toString()
                        );
                        ExecutionContext actionContext = safeContext
                                .withTag("executionId", executionId)
                                .withTag("correlationId", correlationId);
                        if (!safeRequest.idempotencyKey().isBlank()) {
                            actionContext = actionContext.withTag("idempotencyKey", safeRequest.idempotencyKey());
                        }

                        GeneratedActionDescriptor descriptor = GeneratedActionRegistry.find(actionName);
                        if (descriptor == null) {
                            return response(
                                    "rejected",
                                    clean(actionName),
                                     "",
                                     executionId,
                                     correlationId,
                                     "unavailable: action not found",
                                     "unavailable: action not found before capability dispatch",
                                     0,
                                     0,
                                    "unavailable: action not found, no event published",
                                    "unavailable: no generated action executed",
                                    "unavailable: generated action runner has no direct trace store API",
                                    idempotencyEvidenceStatus(safeRequest),
                                    "tagged: correlationId present on ExecutionContext only",
                                    "unknown-action",
                                    "unknown-action",
                                    Map.of()
                            );
                        }

                        int before = runtimeCount(actionContext, descriptor.sideEffectConcept());
                        String sideEffectCountingStatus = sideEffectCountingStatus(descriptor);
                        String correlationStatus = claimCorrelation(correlationId, actionContext);
                        String authorizationFailure = authorizationFailure(descriptor, safeRequest.input(), actionContext);
                        if (!authorizationFailure.isBlank()) {
                            String auditStatus = recordAudit(
                                    descriptor,
                                    executionId,
                                    correlationId,
                                    "denied",
                                    authorizationFailure,
                                    actionContext
                            );
                            return response(
                                    "rejected",
                                    descriptor.actionName(),
                                     descriptor.procedureName(),
                                     executionId,
                                     correlationId,
                                     descriptor.capabilityId(),
                                     "prevented: authorization rejected before capability dispatch",
                                     before,
                                     before,
                                    "unavailable: authorization rejected before event publication",
                                    auditStatus,
                                    "unavailable: generated action runner has no direct trace store API",
                                    idempotencyEvidenceStatus(safeRequest),
                                    correlationStatus,
                                    authorizationFailure,
                                    authorizationFailure,
                                    Map.of()
                            );
                        }

                        Optional<IdempotencyRecord> cachedRecord = findIdempotency(descriptor, safeRequest, actionContext);
                        if (cachedRecord.isPresent() && cachedRecord.get().success()) {
                            String auditStatus = recordAudit(
                                    descriptor,
                                    executionId,
                                    correlationId,
                                    "reused",
                                    "idempotency_reused",
                                    actionContext
                            );
                            String traceStatus = writeTrace(
                                    descriptor,
                                    executionId,
                                    correlationId,
                                    "ok",
                                    System.currentTimeMillis(),
                                    System.currentTimeMillis(),
                                    before,
                                    before,
                                    actionContext
                            );
                            return response(
                                    "ok",
                                    descriptor.actionName(),
                                     descriptor.procedureName(),
                                     executionId,
                                     correlationId,
                                     descriptor.capabilityId(),
                                     "prevented: idempotency reused before capability dispatch",
                                     before,
                                     before,
                                    "unavailable: idempotent reuse prevented duplicate side effects; no success event republished",
                                    auditStatus,
                                    traceStatus,
                                    "reused: idempotencyKey=" + safeRequest.idempotencyKey(),
                                    correlationStatus,
                                    "idempotent-reuse",
                                    "",
                                    withSideEffectCountingStatus(
                                            Map.of("idempotencyReused", true, "sideEffectPrevented", true),
                                            sideEffectCountingStatus
                                    )
                            );
                        }

                        Map<String, Object> result = Map.of();
                        String error = "";
                        String message = "ok";
                        String status = "ok";
                        String capabilityDispatchStatus = "unavailable: capability dispatch not attempted";
                        long startedAtMs = System.currentTimeMillis();
                        String auditStatus = recordAudit(
                                descriptor,
                                executionId,
                                correlationId,
                                "started",
                                "handler_start",
                                actionContext
                        );
                        try {
                            GeneratedTrustedProcedureContext trustedContext = new GeneratedTrustedProcedureContext(actionContext);
                            GeneratedActionCapabilityRequest capabilityRequest = new GeneratedActionCapabilityRequest(
                                    descriptor,
                                    safeRequest,
                                    trustedContext,
                                    actionContext,
                                    executionId,
                                    correlationId
                            );
                            CapabilityCall capabilityCall = new CapabilityCall(
                                    descriptor.capabilityId(),
                                    GeneratedActionCapabilityAdapter.CAPABILITY_TYPE,
                                    GeneratedActionCapabilityAdapter.ADAPTER_ID,
                                    GeneratedActionCapabilityAdapter.OPERATION_RUN,
                                    List.of(capabilityRequest),
                                    correlationId,
                                    safeRequest.idempotencyKey().isBlank() ? null : safeRequest.idempotencyKey()
                            );
                            CapabilityResult capabilityResult = capabilityDispatcher.invoke(
                                     capabilityCall,
                                     capabilityContextState(descriptor, safeRequest, actionContext, executionId, correlationId)
                             );
                            capabilityDispatchStatus = "dispatched: capabilityId=" + descriptor.capabilityId();
                             if (!capabilityResult.ok()) {
                                 status = "error";
                                 error = capabilityResult.error().code() + ": " + capabilityResult.error().message();
                                 message = "capability dispatch failed";
                                 capabilityDispatchStatus = "failed: " + error;
                             } else if (capabilityResult.value() instanceof GeneratedActionCapabilityResult actionResult) {
                                 status = actionResult.status();
                                 message = actionResult.message().isBlank() ? status : actionResult.message();
                                 error = actionResult.error();
                                 result = actionResult.result();
                            } else {
                                status = "error";
                                error = "Generated action capability returned unexpected result type";
                                message = "capability dispatch failed";
                            }
                        } catch (RuntimeException exception) {
                             status = "error";
                             error = exception.getClass().getSimpleName() + ": " + clean(exception.getMessage());
                             message = "handler failed";
                             capabilityDispatchStatus = capabilityDispatchStatus.startsWith("dispatched")
                                     ? "failed: " + error
                                     : "failed: exception before capability dispatch: " + error;
                         }

                        int after = runtimeCount(actionContext, descriptor.sideEffectConcept());
                        long endedAtMs = System.currentTimeMillis();
                        String eventStatus = "ok".equals(status)
                                ? publishActionEvent(descriptor, safeRequest, actionContext, executionId, correlationId, status, after - before)
                                : "unavailable: failed action did not publish success event";
                        String traceStatus = writeTrace(
                                descriptor,
                                executionId,
                                correlationId,
                                status,
                                startedAtMs,
                                endedAtMs,
                                before,
                                after,
                                actionContext
                        );
                        String completionAuditStatus = recordAudit(
                                descriptor,
                                executionId,
                                correlationId,
                                "ok".equals(status) ? "completed" : "failed",
                                "ok".equals(status) ? "ok" : error,
                                actionContext
                        );
                        if (completionAuditStatus.startsWith("written")) {
                            auditStatus = completionAuditStatus;
                        }
                        String idempotencyStatus = recordIdempotency(descriptor, safeRequest, actionContext, status, result, error);
                        return response(
                                status,
                                descriptor.actionName(),
                                 descriptor.procedureName(),
                                 executionId,
                                 correlationId,
                                 descriptor.capabilityId(),
                                 capabilityDispatchStatus,
                                 before,
                                 after,
                                eventStatus,
                                auditStatus,
                                traceStatus,
                                idempotencyStatus,
                                correlationStatus,
                                message,
                                error,
                                withSideEffectCountingStatus(result, sideEffectCountingStatus)
                        );
                    }

                    private static Map<String, Object> capabilityContextState(
                            GeneratedActionDescriptor descriptor,
                            GeneratedActionExecutionRequest request,
                            ExecutionContext context,
                            String executionId,
                            String correlationId
                    ) {
                        Map<String, Object> state = new LinkedHashMap<>();
                        state.put("actionName", descriptor.actionName());
                        state.put("procedureName", descriptor.procedureName());
                        state.put("executionId", executionId);
                        state.put("correlationId", correlationId);
                        state.put("tenantId", context.tenantId());
                        state.put("actorId", context.actorId());
                        state.put("input", request.input());
                        state.put("dispatchModel", "CapabilityDispatcher");
                        return Map.copyOf(state);
                    }

                    private String publishActionEvent(
                            GeneratedActionDescriptor descriptor,
                            GeneratedActionExecutionRequest request,
                            ExecutionContext context,
                            String executionId,
                            String correlationId,
                            String status,
                            int createdCount
                    ) {
                        if (kernelFacade == null) {
                            return "unavailable: KernelFacade bean unavailable";
                        }
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("actionName", descriptor.actionName());
                        payload.put("procedureName", descriptor.procedureName());
                        payload.put("executionId", executionId);
                        payload.put("correlationId", correlationId);
                        payload.put("status", status);
                        payload.put("createdCount", createdCount);
                        payload.put("input", request.input());
                        try {
                            EventEnvelope envelope = kernelFacade.publishExternalEvent(
                                    descriptor.eventNameOnSuccess(),
                                    correlationId,
                                    executionId,
                                    payload,
                                    context
                            );
                            return kernelFacade.verifyGeneratedActionEvent(
                                    descriptor.eventNameOnSuccess(),
                                    correlationId,
                                    envelope.eventId(),
                                    context
                            );
                        } catch (RuntimeException exception) {
                            return "failed: " + exception.getClass().getSimpleName() + ": " + clean(exception.getMessage());
                        }
                    }

                    private String claimCorrelation(String correlationId, ExecutionContext context) {
                        if (kernelFacade == null) {
                            return "unavailable: KernelFacade bean unavailable";
                        }
                        return kernelFacade.claimGeneratedActionCorrelation(correlationId, context);
                    }

                    private Optional<IdempotencyRecord> findIdempotency(
                            GeneratedActionDescriptor descriptor,
                            GeneratedActionExecutionRequest request,
                            ExecutionContext context
                    ) {
                        if (kernelFacade == null
                                || request == null
                                || request.idempotencyKey().isBlank()
                                || "disabled".equalsIgnoreCase(descriptor.idempotencyPolicy())) {
                            return Optional.empty();
                        }
                        return kernelFacade.findGeneratedActionIdempotency(
                                descriptor.actionName(),
                                request.idempotencyKey(),
                                context
                        );
                    }

                    private String recordIdempotency(
                            GeneratedActionDescriptor descriptor,
                            GeneratedActionExecutionRequest request,
                            ExecutionContext context,
                            String status,
                            Map<String, Object> result,
                            String error
                    ) {
                        if (request == null || request.idempotencyKey().isBlank()) {
                            return "disabled: no idempotencyKey supplied";
                        }
                        if ("disabled".equalsIgnoreCase(descriptor.idempotencyPolicy())) {
                            return "disabled: descriptor idempotencyPolicy is disabled";
                        }
                        if (kernelFacade == null) {
                            return "unavailable: KernelFacade bean unavailable";
                        }
                        if ("ok".equalsIgnoreCase(status)) {
                            return kernelFacade.recordGeneratedActionIdempotencySuccess(
                                    descriptor.actionName(),
                                    request.idempotencyKey(),
                                    result == null ? "{}" : result.toString(),
                                    context
                            );
                        }
                        return kernelFacade.recordGeneratedActionIdempotencyFailure(
                                descriptor.actionName(),
                                request.idempotencyKey(),
                                error,
                                context
                        );
                    }

                    private String recordAudit(
                            GeneratedActionDescriptor descriptor,
                            String executionId,
                            String correlationId,
                            String outcome,
                            String reasonCode,
                            ExecutionContext context
                    ) {
                        if (kernelFacade == null) {
                            return "unavailable: KernelFacade bean unavailable";
                        }
                        return kernelFacade.recordGeneratedActionAudit(
                                descriptor.actionName(),
                                descriptor.auditResourceType(),
                                executionId,
                                correlationId,
                                outcome,
                                reasonCode,
                                context
                        );
                    }

                    private String writeTrace(
                            GeneratedActionDescriptor descriptor,
                            String executionId,
                            String correlationId,
                            String outcome,
                            long startedAtMs,
                            long endedAtMs,
                            int before,
                            int after,
                            ExecutionContext context
                    ) {
                        if (kernelFacade == null) {
                            return "unavailable: KernelFacade bean unavailable";
                        }
                        if ("disabled".equalsIgnoreCase(descriptor.tracePolicy())) {
                            return "disabled: descriptor tracePolicy is disabled";
                        }
                        return kernelFacade.writeGeneratedActionTrace(
                                descriptor.actionName(),
                                executionId,
                                correlationId,
                                outcome,
                                startedAtMs,
                                endedAtMs,
                                before,
                                after,
                                context
                        );
                    }

                    private GeneratedActionExecutionResponse response(
                            String status,
                            String actionName,
                             String procedureName,
                             String executionId,
                             String correlationId,
                             String capabilityId,
                             String capabilityDispatchStatus,
                             int before,
                             int after,
                            String eventStatus,
                            String auditStatus,
                            String traceStatus,
                            String idempotencyStatus,
                            String correlationStatus,
                            String message,
                            String error,
                            Map<String, Object> result
                    ) {
                        return new GeneratedActionExecutionResponse(
                                status,
                                actionName,
                                 procedureName,
                                 executionId,
                                 correlationId,
                                 capabilityId,
                                 capabilityDispatchStatus,
                                 Math.max(0, after - before),
                                before,
                                after,
                                eventStatus,
                                auditStatus,
                                traceStatus,
                                idempotencyStatus,
                                correlationStatus,
                                message,
                                error,
                                result
                        );
                    }

                    private static String authorizationFailure(
                            GeneratedActionDescriptor descriptor,
                            Map<String, Object> input,
                            ExecutionContext context
                    ) {
                        if (!context.hasRole(descriptor.requiredRole())) {
                            return "missing-role";
                        }
                        if (descriptor.tenantScoped()) {
                            String requestedTenant = stringValue(input.get("tenantId"));
                            if (!requestedTenant.isBlank() && !requestedTenant.equals(context.tenantId())) {
                                return "wrong-tenant";
                            }
                        }
                        return "";
                    }

                    private static String idempotencyEvidenceStatus(GeneratedActionExecutionRequest request) {
                        if (request == null || request.idempotencyKey().isBlank()) {
                            return "disabled: no idempotencyKey supplied";
                        }
                        return "unavailable: action did not reach idempotency evidence check";
                    }

                    private int runtimeCount(ExecutionContext context, String conceptName) {
                        if (conceptName == null || conceptName.isBlank()) {
                            return 0;
                        }
                        try {
                            return conceptGateway.list(new ConceptListRequest(conceptName, context.tenantId()), context).size();
                        } catch (RuntimeException ignored) {
                            return 0;
                        }
                    }

                    private static String sideEffectCountingStatus(GeneratedActionDescriptor descriptor) {
                        if (descriptor == null || descriptor.sideEffectConcept().isBlank()) {
                            return "disabled: descriptor sideEffectConcept is not declared";
                        }
                        return "enabled: sideEffectConcept=" + descriptor.sideEffectConcept();
                    }

                    private static Map<String, Object> withSideEffectCountingStatus(
                            Map<String, Object> result,
                            String sideEffectCountingStatus
                    ) {
                        if (sideEffectCountingStatus == null || sideEffectCountingStatus.isBlank()
                                || sideEffectCountingStatus.startsWith("enabled:")) {
                            return result == null ? Map.of() : result;
                        }
                        Map<String, Object> out = new LinkedHashMap<>(result == null ? Map.of() : result);
                        out.put("sideEffectCountingStatus", sideEffectCountingStatus);
                        return Map.copyOf(out);
                    }

                    private static String firstNonBlank(String... values) {
                        if (values == null) {
                            return "";
                        }
                        for (String value : values) {
                            String cleaned = clean(value);
                            if (!cleaned.isBlank()) {
                                return cleaned;
                            }
                        }
                        return "";
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value).trim();
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }

                    private final class GeneratedTrustedProcedureContext implements NPDevProcedureContext {
                        private final ExecutionContext context;

                        private GeneratedTrustedProcedureContext(ExecutionContext context) {
                            this.context = context;
                        }

                        @Override
                        public String tenantId() {
                            return context.tenantId();
                        }

                        @Override
                        public String actorId() {
                            return context.actorId();
                        }

                        @Override
                        public List<Map<String, Object>> saveMany(String concept, List<Map<String, Object>> records) {
                            List<Map<String, Object>> saved = new ArrayList<>();
                            for (Map<String, Object> record : records == null ? List.<Map<String, Object>>of() : records) {
                                String id = stringValue(record.get("id"));
                                Map<String, Object> data = new LinkedHashMap<>(record);
                                data.put("tenantId", context.tenantId());
                                ConceptRecord savedRecord = conceptGateway.save(
                                        new ConceptWriteRequest(concept, id, context.tenantId(), data),
                                        context
                                );
                                Map<String, Object> row = new LinkedHashMap<>(savedRecord.data());
                                row.put("id", savedRecord.id());
                                row.put("tenantId", savedRecord.tenantId());
                                saved.add(row);
                            }
                            return List.copyOf(saved);
                        }
                    }
                }
                """;
    }
}
