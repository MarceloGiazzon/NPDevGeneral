package com.npdev.generator.emitters;

import com.npdev.generator.emitters.trustedsource.model.TrustedFlow;

import java.util.List;

import static com.npdev.generator.emitters.TrustedSourceTemplateSupport.quote;

/**
 * Static Java source templates for the trusted-source flow-execution DTOs (descriptor,
 * execution request/response) plus the {@code GeneratedFlowRegistry} template that lists every
 * start-endpoint flow the compiled model declares.
 *
 * <p>Split out of {@code TrustedSourceEmitter} (2.B.2).
 */
final class TrustedFlowSupportTemplates {

    private TrustedFlowSupportTemplates() {
    }

    static String generatedFlowDescriptorSource() {
        return """
                package com.npdev.generated.trusted;

                public record GeneratedFlowDescriptor(
                        String flowName,
                        String actionName
                ) {
                    public GeneratedFlowDescriptor {
                        flowName = require(flowName, "flowName");
                        actionName = clean(actionName);
                    }

                    private static String require(String value, String label) {
                        String cleaned = clean(value);
                        if (cleaned.isBlank()) {
                            throw new IllegalArgumentException(label + " must be non-blank");
                        }
                        return cleaned;
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    static String generatedFlowExecutionRequestSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.Collections;
                import java.util.LinkedHashMap;
                import java.util.Map;

                public record GeneratedFlowExecutionRequest(
                        String executionId,
                        String correlationId,
                        String idempotencyKey,
                        Map<String, Object> input
                ) {
                    public GeneratedFlowExecutionRequest {
                        executionId = clean(executionId);
                        correlationId = clean(correlationId);
                        idempotencyKey = clean(idempotencyKey);
                        // REG-175/REG-146: nested into published event/audit payloads --
                        // Map.copyOf's JEP 269 iteration-order randomization (re-salted every JVM
                        // start) meant this event JSON's key order changed on every app restart.
                        input = input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
                    }

                    public static GeneratedFlowExecutionRequest from(Map<String, Object> body) {
                        Map<String, Object> input = body == null ? Map.of() : new LinkedHashMap<>(body);
                        return new GeneratedFlowExecutionRequest(
                                stringValue(input.remove("executionId")),
                                stringValue(input.remove("correlationId")),
                                stringValue(input.remove("idempotencyKey")),
                                input
                        );
                    }

                    public Map<String, Object> toKernelInput() {
                        Map<String, Object> out = new LinkedHashMap<>(input);
                        if (!executionId.isBlank()) {
                            out.put("executionId", executionId);
                        }
                        if (!correlationId.isBlank()) {
                            out.put("correlationId", correlationId);
                        }
                        if (!idempotencyKey.isBlank()) {
                            out.put("idempotencyKey", idempotencyKey);
                        }
                        return out;
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

    static String generatedFlowExecutionResponseSource() {
        return """
                package com.npdev.generated.trusted;

                import java.util.Collections;
                import java.util.LinkedHashMap;
                import java.util.Map;

                public record GeneratedFlowExecutionResponse(
                        String status,
                        String flowName,
                        String flowInstanceId,
                        String flowStatus,
                        String executionId,
                        String correlationId,
                        String actionName,
                        String procedureName,
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
                        String evidenceViewerUrl,
                        String message,
                        String error,
                        Map<String, Object> result
                ) {
                    public GeneratedFlowExecutionResponse {
                        status = clean(status);
                        flowName = clean(flowName);
                        flowInstanceId = clean(flowInstanceId);
                        flowStatus = clean(flowStatus);
                        executionId = clean(executionId);
                        correlationId = clean(correlationId);
                        actionName = clean(actionName);
                        procedureName = clean(procedureName);
                        capabilityId = clean(capabilityId);
                        capabilityDispatchStatus = clean(capabilityDispatchStatus);
                        eventStatus = clean(eventStatus);
                        auditStatus = clean(auditStatus);
                        traceStatus = clean(traceStatus);
                        idempotencyStatus = clean(idempotencyStatus);
                        correlationStatus = clean(correlationStatus);
                        evidenceViewerUrl = clean(evidenceViewerUrl);
                        message = clean(message);
                        error = clean(error);
                        // REG-175/REG-146: returned directly as the JSON body of POST
                        // /api/generated/flows/{flowName}/start and .../events/{eventName} --
                        // Map.copyOf's JEP 269 iteration-order randomization meant this response's
                        // "result" field order changed on every app restart.
                        result = result == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(result));
                    }

                    public Map<String, Object> toMap() {
                        Map<String, Object> out = new LinkedHashMap<>();
                        out.put("status", status);
                        out.put("flowName", flowName);
                        out.put("flowInstanceId", flowInstanceId);
                        out.put("flowStatus", flowStatus);
                        out.put("flowInstanceStatus", flowStatus);
                        out.put("executionId", executionId);
                        out.put("correlationId", correlationId);
                        out.put("actionName", actionName);
                        out.put("procedureName", procedureName);
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
                        out.put("evidenceViewerUrl", evidenceViewerUrl);
                        out.put("message", message);
                        out.put("error", error);
                        Object flowStartIdempotencyStatus = result.get("flowStartIdempotencyStatus");
                        if (flowStartIdempotencyStatus != null) {
                            out.put("flowStartIdempotencyStatus", String.valueOf(flowStartIdempotencyStatus));
                        }
                        if (!result.isEmpty()) {
                            out.put("result", result);
                        }
                        return out;
                    }

                    public GeneratedFlowExecutionResponse withFlowStartIdempotencyStatus(String statusValue) {
                        Map<String, Object> copy = new LinkedHashMap<>(result);
                        copy.put("flowStartIdempotencyStatus", clean(statusValue));
                        return new GeneratedFlowExecutionResponse(
                                status,
                                flowName,
                                flowInstanceId,
                                flowStatus,
                                executionId,
                                correlationId,
                                actionName,
                                procedureName,
                                capabilityId,
                                capabilityDispatchStatus,
                                createdCount,
                                sideEffectCountBefore,
                                sideEffectCountAfter,
                                eventStatus,
                                auditStatus,
                                traceStatus,
                                idempotencyStatus,
                                correlationStatus,
                                evidenceViewerUrl,
                                message,
                                error,
                                copy
                        );
                    }

                    public GeneratedFlowExecutionResponse asFlowStartIdempotencyReplay() {
                        Map<String, Object> copy = new LinkedHashMap<>(result);
                        copy.put("flowStartIdempotencyStatus", "reused: generated flow-start idempotency guard returned existing response before KernelRunner dispatch");
                        return new GeneratedFlowExecutionResponse(
                                status,
                                flowName,
                                flowInstanceId,
                                flowStatus,
                                executionId,
                                correlationId,
                                actionName,
                                procedureName,
                                capabilityId,
                                "prevented: generated flow-start idempotency guard reused existing response before KernelRunner dispatch",
                                0,
                                sideEffectCountAfter,
                                sideEffectCountAfter,
                                eventStatus,
                                auditStatus,
                                traceStatus,
                                "reused: generated flow-start idempotency guard",
                                correlationStatus,
                                evidenceViewerUrl,
                                "flow start idempotency replay",
                                error,
                                copy
                        );
                    }

                    private static String clean(String value) {
                        return value == null ? "" : value.trim();
                    }
                }
                """;
    }

    static String generatedFlowRegistrySource(List<TrustedFlow> flows) {
        StringBuilder source = new StringBuilder();
        source.append("""
                package com.npdev.generated.trusted;

                import java.util.LinkedHashMap;
                import java.util.List;
                import java.util.Map;

                public final class GeneratedFlowRegistry {
                    private static final List<GeneratedFlowDescriptor> DESCRIPTORS = List.of(
                """);
        for (int i = 0; i < flows.size(); i++) {
            TrustedFlow flow = flows.get(i);
            source.append("            new GeneratedFlowDescriptor(")
                    .append(quote(flow.flowName()))
                    .append(", ")
                    .append(quote(flow.actionName()))
                    .append(")");
            if (i + 1 < flows.size()) {
                source.append(",");
            }
            source.append("\n");
        }
        source.append("""
                    );
                    private static final Map<String, GeneratedFlowDescriptor> BY_FLOW_NAME = byFlowName();

                    private GeneratedFlowRegistry() {
                    }

                    public static List<GeneratedFlowDescriptor> all() {
                        return DESCRIPTORS;
                    }

                    public static GeneratedFlowDescriptor find(String flowName) {
                        if (flowName == null) {
                            return null;
                        }
                        return BY_FLOW_NAME.get(flowName.trim());
                    }

                    private static Map<String, GeneratedFlowDescriptor> byFlowName() {
                        Map<String, GeneratedFlowDescriptor> out = new LinkedHashMap<>();
                        for (GeneratedFlowDescriptor descriptor : DESCRIPTORS) {
                            out.put(descriptor.flowName(), descriptor);
                        }
                        return Map.copyOf(out);
                    }
                }
                """);
        return source.toString();
    }
}
