package com.npdev.generator.emitters;

/**
 * Static Java source templates for the trusted-source action-execution scaffolding that ships
 * with every generated FinalApp regardless of which procedures/panels/widgets it declares:
 * the procedure context interface, the generated-action DTOs, and the capability adapter /
 * dispatcher-factory / registry-contributor that wire generated actions into the kernel's
 * capability dispatch path.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2) -- every method here is a pure,
 * parameterless string literal with no dependency on the rest of the emitter.
 */
final class TrustedActionSupportTemplates {

    private TrustedActionSupportTemplates() {
    }

    static String procedureContextSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.List;
                import java.util.Map;

                public interface NPDevProcedureContext {
                    String tenantId();
                    String actorId();
                    List<Map<String, Object>> saveMany(String concept, List<Map<String, Object>> records);
                }
                """;
    }

    static String generatedActionDescriptorSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.List;
                import java.util.Map;

                public record GeneratedActionDescriptor(
                        String actionName,
                        String procedureName,
                        String requiredRole,
                        boolean tenantScoped,
                        List<String> affectedConcepts,
                        String sideEffectConcept,
                        String eventNameOnSuccess,
                        String auditResourceType,
                        String idempotencyPolicy,
                        String tracePolicy,
                        String correlationPolicy,
                        String capabilityId,
                        Handler handler
                ) {
                    public GeneratedActionDescriptor {
                        actionName = require(actionName, "actionName");
                        procedureName = require(procedureName, "procedureName");
                        requiredRole = require(requiredRole, "requiredRole");
                        affectedConcepts = affectedConcepts == null ? List.of() : List.copyOf(affectedConcepts);
                        sideEffectConcept = clean(sideEffectConcept);
                        eventNameOnSuccess = require(eventNameOnSuccess, "eventNameOnSuccess");
                        auditResourceType = require(auditResourceType, "auditResourceType");
                        idempotencyPolicy = require(idempotencyPolicy, "idempotencyPolicy");
                        tracePolicy = require(tracePolicy, "tracePolicy");
                        correlationPolicy = require(correlationPolicy, "correlationPolicy");
                        capabilityId = require(capabilityId, "capabilityId");
                        if (handler == null) {
                            throw new IllegalArgumentException("handler must be non-null");
                        }
                    }

                    private static String require(String value, String field) {
                        if (value == null || value.trim().isEmpty()) {
                            throw new IllegalArgumentException(field + " must be non-blank");
                        }
                        return value.trim();
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }

                    @FunctionalInterface
                    public interface Handler {
                        Map<String, Object> invoke(NPDevProcedureContext context);
                    }
                }
                """;
    }

    static String generatedActionExecutionRequestSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public record GeneratedActionExecutionRequest(
                        String executionId,
                        String correlationId,
                        String idempotencyKey,
                        Map<String, Object> input
                ) {
                    public GeneratedActionExecutionRequest {
                        executionId = clean(executionId);
                        correlationId = clean(correlationId);
                        idempotencyKey = clean(idempotencyKey);
                        input = input == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(input));
                    }

                    public static GeneratedActionExecutionRequest from(Map<String, Object> body) {
                        Map<String, Object> input = body == null ? Map.of() : new LinkedHashMap<>(body);
                        return new GeneratedActionExecutionRequest(
                                stringValue(input.remove("executionId")),
                                stringValue(input.remove("correlationId")),
                                stringValue(input.remove("idempotencyKey")),
                                input
                        );
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value);
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    static String generatedActionExecutionResponseSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public record GeneratedActionExecutionResponse(
                        String status,
                        String actionName,
                        String procedureName,
                        String executionId,
                        String correlationId,
                        String capabilityId,
                        String capabilityDispatchStatus,
                        int createdCount,
                        int sideEffectCountBefore,
                        int sideEffectCountAfter,
                        String eventStatus,
                        String auditStatus,
                        String traceStatus,
                        String idempotencyStatus,
                        String correlationStatus,
                        String message,
                        String error,
                        Map<String, Object> result
                ) {
                    public GeneratedActionExecutionResponse {
                        status = clean(status);
                        actionName = clean(actionName);
                        procedureName = clean(procedureName);
                        executionId = clean(executionId);
                        correlationId = clean(correlationId);
                        capabilityId = clean(capabilityId);
                        capabilityDispatchStatus = clean(capabilityDispatchStatus);
                        eventStatus = clean(eventStatus);
                        auditStatus = clean(auditStatus);
                        traceStatus = clean(traceStatus);
                        idempotencyStatus = clean(idempotencyStatus);
                        correlationStatus = clean(correlationStatus);
                        message = clean(message);
                        error = clean(error);
                        result = result == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(result));
                    }

                    public Map<String, Object> toMap() {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("status", status);
                        out.put("actionName", actionName);
                        out.put("procedureName", procedureName);
                        out.put("executionId", executionId);
                        out.put("correlationId", correlationId);
                        out.put("capabilityId", capabilityId);
                        out.put("capabilityDispatchStatus", capabilityDispatchStatus);
                        out.put("createdCount", createdCount);
                        out.put("sideEffectCountBefore", sideEffectCountBefore);
                        out.put("sideEffectCountAfter", sideEffectCountAfter);
                        out.put("eventStatus", eventStatus);
                        out.put("auditStatus", auditStatus);
                        out.put("traceStatus", traceStatus);
                        out.put("idempotencyStatus", idempotencyStatus);
                        out.put("correlationStatus", correlationStatus);
                        out.put("message", message);
                        out.put("error", error);
                        if (!result.isEmpty()) {
                            out.put("result", result);
                        }
                        return out;
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    static String generatedActionCapabilityRequestSource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.kernel.ExecutionContext;

                public record GeneratedActionCapabilityRequest(
                        GeneratedActionDescriptor descriptor,
                        GeneratedActionExecutionRequest executionRequest,
                        NPDevProcedureContext procedureContext,
                        ExecutionContext executionContext,
                        String executionId,
                        String correlationId
                ) {
                    public GeneratedActionCapabilityRequest {
                        if (descriptor == null) {
                            throw new IllegalArgumentException("descriptor must be non-null");
                        }
                        if (executionRequest == null) {
                            throw new IllegalArgumentException("executionRequest must be non-null");
                        }
                        if (procedureContext == null) {
                            throw new IllegalArgumentException("procedureContext must be non-null");
                        }
                        if (executionContext == null) {
                            throw new IllegalArgumentException("executionContext must be non-null");
                        }
                        executionId = clean(executionId);
                        correlationId = clean(correlationId);
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    static String generatedActionCapabilityResultSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.Map;

                public record GeneratedActionCapabilityResult(
                        String status,
                        Map<String, Object> result,
                        boolean dispatcherEntered,
                        boolean providerEntered,
                        boolean handlerInvoked,
                        String message,
                        String error
                ) {
                    public GeneratedActionCapabilityResult {
                        status = clean(status);
                        result = result == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(result));
                        message = clean(message);
                        error = clean(error);
                    }

                    public static GeneratedActionCapabilityResult ok(Map<String, Object> result) {
                        return new GeneratedActionCapabilityResult("ok", result, true, true, true, "ok", "");
                    }

                    public static GeneratedActionCapabilityResult error(String message, String error) {
                        return new GeneratedActionCapabilityResult("error", Map.of(), true, true, false, message, error);
                    }

                    public Map<String, Object> toMap() {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("status", status);
                        out.put("dispatcherEntered", dispatcherEntered);
                        out.put("providerEntered", providerEntered);
                        out.put("handlerInvoked", handlerInvoked);
                        out.put("message", message);
                        out.put("error", error);
                        out.put("result", result);
                        out.putAll(result);
                        return out;
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    static String generatedActionCapabilityAdapterSource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.kernel.CapabilityCall;
                import com.npdev.kernel.CapabilityErrorKind;
                import com.npdev.kernel.CapabilityResult;
                import com.npdev.kernel.ExecutionContext;
                import com.npdev.kernel.concepts.ConceptGateway;
                import com.npdev.kernel.concepts.ConceptRecord;
                import com.npdev.kernel.concepts.ConceptWriteRequest;
                import com.npdev.kernel.ports.CapabilityAdapter;

                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;

                public final class GeneratedActionCapabilityAdapter implements CapabilityAdapter {
                    public static final String CAPABILITY_TYPE = "GeneratedActionCapability";
                    public static final String ADAPTER_ID = "generated-action";
                    public static final String OPERATION_RUN = "run";
                    private final ConceptGateway conceptGateway;

                    public GeneratedActionCapabilityAdapter() {
                        this(null);
                    }

                    public GeneratedActionCapabilityAdapter(ConceptGateway conceptGateway) {
                        this.conceptGateway = conceptGateway;
                    }

                    @Override
                    public String adapterId() {
                        return ADAPTER_ID;
                    }

                    @Override
                    public String capability() {
                        return CAPABILITY_TYPE;
                    }

                    @Override
                    public String capabilityType() {
                        return CAPABILITY_TYPE;
                    }

                    @Override
                    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
                        if (conceptGateway != null) {
                            GeneratedActionCapabilityDispatcherFactory.dispatcherEntered();
                        }
                        GeneratedActionCapabilityDispatcherFactory.providerEntered();
                        if (call == null || !OPERATION_RUN.equals(call.operation())) {
                            return CapabilityResult.failure(
                                    "GENERATED_ACTION_OPERATION_UNSUPPORTED",
                                    "Generated action capability only supports operation run",
                                    CapabilityErrorKind.CONTRACT,
                                    Map.of()
                            );
                        }
                        Object input = call.input();
                        GeneratedActionCapabilityRequest request = null;
                        if (input instanceof GeneratedActionCapabilityRequest richRequest) {
                            request = richRequest;
                        } else if (conceptGateway != null) {
                            request = requestFromKernelFlow(call, contextState);
                        }
                        if (request == null) {
                            return CapabilityResult.failure(
                                    "GENERATED_ACTION_REQUEST_INVALID",
                                    "Generated action capability requires GeneratedActionCapabilityRequest",
                                    CapabilityErrorKind.CONTRACT,
                                    Map.of()
                            );
                        }
                        try {
                            Map<String, Object> rawResult = request.descriptor().handler().invoke(request.procedureContext());
                            Map<String, Object> safeResult = rawResult == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(rawResult));
                            GeneratedActionCapabilityDispatcherFactory.handlerInvoked();
                            return CapabilityResult.success(GeneratedActionCapabilityResult.ok(safeResult));
                        } catch (RuntimeException exception) {
                            String error = exception.getClass().getSimpleName() + ": " + clean(exception.getMessage());
                            return CapabilityResult.success(GeneratedActionCapabilityResult.error("handler failed", error));
                        }
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }

                    @SuppressWarnings("unchecked")
                    private GeneratedActionCapabilityRequest requestFromKernelFlow(
                            CapabilityCall call,
                            Map<String, Object> contextState
                    ) {
                        Map<String, Object> state = contextState == null ? Map.of() : contextState;
                        String capability = clean(call.capability());
                        String actionName = capability.startsWith("generated.action.")
                                ? capability.substring("generated.action.".length())
                                : capability;
                        GeneratedActionDescriptor descriptor = GeneratedActionRegistry.find(actionName);
                        if (descriptor == null) {
                            return null;
                        }
                        Map<String, Object> input = new LinkedHashMap<>();
                        Object rawInput = call.input();
                        if (rawInput instanceof Map<?, ?> map) {
                            for (Map.Entry<?, ?> entry : map.entrySet()) {
                                input.put(String.valueOf(entry.getKey()), entry.getValue());
                            }
                        } else {
                            Object stateInput = state.get("input");
                            if (stateInput instanceof Map<?, ?> map) {
                                for (Map.Entry<?, ?> entry : map.entrySet()) {
                                    input.put(String.valueOf(entry.getKey()), entry.getValue());
                                }
                            }
                        }
                        String executionId = stringValue(state.get("executionId"));
                        String correlationId = firstNonBlank(stringValue(state.get("correlationId")), call.correlationId());
                        String idempotencyKey = firstNonBlank(call.idempotencyKey(), stringValue(input.get("idempotencyKey")));
                        GeneratedActionExecutionRequest executionRequest = new GeneratedActionExecutionRequest(
                                executionId,
                                correlationId,
                                idempotencyKey,
                                input
                        );
                        ExecutionContext executionContext = ExecutionContext.of(
                                firstNonBlank(stringValue(state.get("tenantId")), "dev"),
                                firstNonBlank(stringValue(state.get("actorId")), "system")
                        ).withTag("executionId", executionId).withTag("correlationId", correlationId);
                        if (!idempotencyKey.isBlank()) {
                            executionContext = executionContext.withTag("idempotencyKey", idempotencyKey);
                        }
                        return new GeneratedActionCapabilityRequest(
                                descriptor,
                                executionRequest,
                                new AdapterProcedureContext(conceptGateway, executionContext),
                                executionContext,
                                executionId,
                                correlationId
                        );
                    }

                    private static String stringValue(Object value) {
                        return value == null ? "" : String.valueOf(value).trim();
                    }

                    private static String firstNonBlank(String... values) {
                        if (values == null) {
                            return "";
                        }
                        for (String value : values) {
                            if (value != null && !value.isBlank()) {
                                return value.trim();
                            }
                        }
                        return "";
                    }

                    private static final class AdapterProcedureContext implements NPDevProcedureContext {
                        private final ConceptGateway conceptGateway;
                        private final ExecutionContext context;

                        private AdapterProcedureContext(ConceptGateway conceptGateway, ExecutionContext context) {
                            this.conceptGateway = conceptGateway;
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

    static String generatedActionCapabilityDispatcherFactorySource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.kernel.CapabilityRegistry;
                import com.npdev.kernel.RegistryCapabilityDispatcher;
                import com.npdev.kernel.ports.CapabilityDispatcher;

                import java.util.concurrent.atomic.AtomicInteger;

                public final class GeneratedActionCapabilityDispatcherFactory {
                    private static final AtomicInteger DISPATCHER_INVOCATIONS = new AtomicInteger();
                    private static final AtomicInteger PROVIDER_INVOCATIONS = new AtomicInteger();
                    private static final AtomicInteger HANDLER_INVOCATIONS = new AtomicInteger();

                    private GeneratedActionCapabilityDispatcherFactory() {
                    }

                    public static CapabilityDispatcher create() {
                        CapabilityRegistry registry = new CapabilityRegistry();
                        GeneratedActionCapabilityAdapter adapter = new GeneratedActionCapabilityAdapter();
                        for (GeneratedActionDescriptor descriptor : GeneratedActionRegistry.all()) {
                            registry.register(
                                    descriptor.capabilityId(),
                                    GeneratedActionCapabilityAdapter.CAPABILITY_TYPE,
                                    GeneratedActionCapabilityAdapter.ADAPTER_ID,
                                    adapter
                            );
                        }
                        CapabilityDispatcher delegate = new RegistryCapabilityDispatcher(registry);
                        return (call, contextState) -> {
                            DISPATCHER_INVOCATIONS.incrementAndGet();
                            return delegate.invoke(call, contextState);
                        };
                    }

                    public static void providerEntered() {
                        PROVIDER_INVOCATIONS.incrementAndGet();
                    }

                    public static void dispatcherEntered() {
                        DISPATCHER_INVOCATIONS.incrementAndGet();
                    }

                    public static void handlerInvoked() {
                        HANDLER_INVOCATIONS.incrementAndGet();
                    }

                    public static int dispatcherInvocations() {
                        return DISPATCHER_INVOCATIONS.get();
                    }

                    public static int providerInvocations() {
                        return PROVIDER_INVOCATIONS.get();
                    }

                    public static int handlerInvocations() {
                        return HANDLER_INVOCATIONS.get();
                    }

                    public static void resetCounters() {
                        DISPATCHER_INVOCATIONS.set(0);
                        PROVIDER_INVOCATIONS.set(0);
                        HANDLER_INVOCATIONS.set(0);
                    }
                }
                """;
    }

    static String generatedActionCapabilityRegistryContributorSource() {
        return """
                package com.npdev.generated.trusted;

                import com.npdev.kernel.CapabilityRegistry;
                import com.npdev.kernel.concepts.ConceptGateway;
                import org.springframework.beans.factory.SmartInitializingSingleton;
                import org.springframework.stereotype.Component;

                @Component
                public final class GeneratedActionCapabilityRegistryContributor implements SmartInitializingSingleton {
                    private final CapabilityRegistry capabilityRegistry;
                    private final ConceptGateway conceptGateway;

                    public GeneratedActionCapabilityRegistryContributor(
                            CapabilityRegistry capabilityRegistry,
                            ConceptGateway conceptGateway
                    ) {
                        this.capabilityRegistry = capabilityRegistry;
                        this.conceptGateway = conceptGateway;
                    }

                    @Override
                    public void afterSingletonsInstantiated() {
                        GeneratedActionCapabilityAdapter adapter = new GeneratedActionCapabilityAdapter(conceptGateway);
                        for (GeneratedActionDescriptor descriptor : GeneratedActionRegistry.all()) {
                            capabilityRegistry.register(
                                    descriptor.capabilityId(),
                                    GeneratedActionCapabilityAdapter.CAPABILITY_TYPE,
                                    GeneratedActionCapabilityAdapter.ADAPTER_ID,
                                    adapter
                            );
                        }
                    }
                }
                """;
    }
}
