package com.finalexec.api;

import com.finalexec.npdev.service.SandboxedPluginExecutionEngine;
import com.finalexec.npdev.service.SandboxedPluginExecutionResult;
import com.npdev.generated.runtime.dto.FlowExecutionResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

@ControllerAdvice
public class FlowExecutionPluginEvidenceAdvice implements ResponseBodyAdvice<Object> {

    private final SandboxedPluginExecutionEngine sandboxedPluginExecutionEngine;

    public FlowExecutionPluginEvidenceAdvice(SandboxedPluginExecutionEngine sandboxedPluginExecutionEngine) {
        this.sandboxedPluginExecutionEngine = Objects.requireNonNull(
                sandboxedPluginExecutionEngine,
                "sandboxedPluginExecutionEngine"
        );
    }

    @Override
    public boolean supports(
            MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        if (!(body instanceof FlowExecutionResponse flowExecutionResponse)) {
            return body;
        }
        FlowExecutionResponse bodyView = flowExecutionResponse;
        if (bodyView.correlationId() == null || bodyView.correlationId().isBlank()) {
            return body;
        }
        String path = request == null || request.getURI() == null ? "" : String.valueOf(request.getURI().getPath());
        if (!path.contains("/flows/") || !path.endsWith("/execute")) {
            return body;
        }

        SandboxedPluginExecutionResult.Summary executionSummary = sandboxedPluginExecutionEngine.recentExecutions().stream()
                .filter(summary -> bodyView.correlationId().equals(summary.correlationId()))
                .filter(summary -> "notification".equalsIgnoreCase(summary.capability()))
                .filter(summary -> "send".equalsIgnoreCase(summary.operation()))
                .findFirst()
                .orElse(null);
        if (executionSummary == null) {
            return body;
        }

        Map<String, Object> output = outputMap(bodyView.output());
        if (output == null) {
            return body;
        }
        Map<String, Object> outputEvidence = executionSummary.outputEvidence() == null
                ? Map.of()
                : executionSummary.outputEvidence();

        putIfMissing(output, "adapterId", firstNonBlank(
                stringValue(outputEvidence.get("adapterId")),
                executionSummary.adapterId()
        ));
        putIfMissing(output, "pluginProfile", stringValue(outputEvidence.get("pluginProfile")));
        putIfMissing(output, "channel", stringValue(outputEvidence.get("channel")));
        putIfMissing(output, "selectedPackageId", executionSummary.selectedPackageId());

        return new FlowExecutionResponse(
                bodyView.executionId(),
                bodyView.correlationId(),
                bodyView.traceId(),
                bodyView.flowName(),
                bodyView.status(),
                Map.copyOf(output),
                bodyView.errorCode(),
                bodyView.error(),
                bodyView.capabilityStepName(),
                bodyView.capabilityStepIndex(),
                bodyView.capabilityName(),
                bodyView.capabilityOperation(),
                bodyView.capabilityAdapterId(),
                bodyView.capabilityError(),
                bodyView.failureInfo(),
                bodyView.awaitedStepName(),
                bodyView.awaitedStepIndex(),
                bodyView.awaitedEventName(),
                bodyView.awaitedCorrelationId(),
                bodyView.inputValidationErrors(),
                bodyView.invariantViolations(),
                bodyView.emittedEvents()
        );
    }

    private static Map<String, Object> outputMap(Object output) {
        if (!(output instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return normalized;
    }

    private static void putIfMissing(Map<String, Object> output, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        Object existing = output.get(key);
        if (existing instanceof String existingString && !existingString.isBlank()) {
            return;
        }
        if (existing != null && !(existing instanceof String)) {
            return;
        }
        output.put(key, value);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String stringValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }
}
