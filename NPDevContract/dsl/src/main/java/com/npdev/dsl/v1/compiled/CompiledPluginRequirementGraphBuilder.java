package com.npdev.dsl.v1.compiled;

import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.StepAst;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class CompiledPluginRequirementGraphBuilder {

    private static final List<String> BUILT_IN_CAPABILITY_TYPES = List.of(
            "persistencecapability",
            "notificationcapability",
            "webhookcapability",
            "apicapability",
            "messagingcapability",
            "eventpublicationcapability"
    );

    public CompiledPluginRequirementGraph build(ModelAst modelAst) {
        Objects.requireNonNull(modelAst, "modelAst");

        Map<String, CapabilityAst> capabilitiesByName = new LinkedHashMap<>();
        for (CapabilityAst capability : modelAst.getCapabilities()) {
            capabilitiesByName.put(normalize(capability.getName()), capability);
        }

        Map<String, String> bindingsByCapability = new HashMap<>();
        for (CapabilityBindingAst binding : modelAst.getBindings()) {
            bindingsByCapability.put(normalize(binding.getCapability()), safe(binding.getAdapter()));
        }

        List<CompiledPluginRequirement> requirements = new ArrayList<>();
        for (FlowAst flow : modelAst.getFlows()) {
            collectFromSteps(flow.getName(), flow.getSteps(), capabilitiesByName, bindingsByCapability, requirements);
        }

        requirements.sort(Comparator
                .comparing(CompiledPluginRequirement::capabilityName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CompiledPluginRequirement::operationName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CompiledPluginRequirement::flowName, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(CompiledPluginRequirement::stepName, String.CASE_INSENSITIVE_ORDER));

        return new CompiledPluginRequirementGraph(requirements);
    }

    private static void collectFromSteps(
            String flowName,
            List<StepAst> steps,
            Map<String, CapabilityAst> capabilitiesByName,
            Map<String, String> bindingsByCapability,
            List<CompiledPluginRequirement> out
    ) {
        for (StepAst step : steps) {
            if ("capability".equals(normalize(step.getType()))) {
                String capabilityName = safe(step.getCapability());
                CapabilityAst capability = capabilitiesByName.get(normalize(capabilityName));
                String capabilityType = capability == null ? "" : safe(capability.getType());
                out.add(new CompiledPluginRequirement(
                        capabilityName,
                        capabilityType,
                        safe(step.getOperation()),
                        safe(flowName),
                        safe(step.getName()),
                        bindingsByCapability.getOrDefault(normalize(capabilityName), ""),
                        isExternalCandidate(capabilityType)
                ));
            }

            if (!step.getThenSteps().isEmpty()) {
                collectFromSteps(flowName, step.getThenSteps(), capabilitiesByName, bindingsByCapability, out);
            }
            if (!step.getElseSteps().isEmpty()) {
                collectFromSteps(flowName, step.getElseSteps(), capabilitiesByName, bindingsByCapability, out);
            }
        }
    }

    private static boolean isExternalCandidate(String capabilityType) {
        String normalized = normalize(capabilityType);
        return !normalized.isBlank() && !BUILT_IN_CAPABILITY_TYPES.contains(normalized);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
