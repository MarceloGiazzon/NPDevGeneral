package com.npdev.dsl.v1.compiled;

import com.npdev.dsl.v1.ast.CapabilityAst;
import com.npdev.dsl.v1.ast.CapabilityBindingAst;
import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.ProcedureAst;
import com.npdev.dsl.v1.ast.ProcedureStepAst;
import com.npdev.dsl.v1.ast.StepAst;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CompiledPluginRequirementGraphBuilder {

    private static final List<String> BUILT_IN_CAPABILITY_TYPES = List.of(
            "persistencecapability",
            "notificationcapability",
            "webhookcapability",
            "apicapability",
            "messagingcapability",
            "eventpublicationcapability",
            "eventbuscapability"
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
        // A procedure's own capabilityCall steps are a second, independent source of plugin
        // requirements -- discovered live (Move 3 G4, docs/MOVE3_AGGREGATE_WORKBENCH_PLAN.md, C10
        // investigation): a capability referenced ONLY from a procedure (never from any flow) was
        // never mounted at all (no Java source compiled in, no plugin-manifest entry), so
        // ProcedureRunner's own dispatch (REG-73) correctly resolved an adapter id that named an
        // adapter the runtime had never registered -- "Adapter ... is not declared in active plugin
        // manifest" at boot. Every custom capability exercised so far had ALSO been called from a
        // pre-existing flow, which mounted it and masked this gap until a capability with no flow
        // caller was tried.
        for (ProcedureAst procedure : modelAst.getProcedures()) {
            collectFromProcedureSteps(procedure.name(), procedure.steps(), capabilitiesByName, bindingsByCapability, requirements);
        }

        // R10 (plugin:java-controller): a mounted @RestController is invoked directly by an HTTP
        // client, never by a flow/procedure capabilityCall step -- so the flow/procedure walk above
        // can never discover it, unlike every other plugin:* adapter this graph was built for. Without
        // this, GeneratedPluginMountPlan.fromModelSource() (which iterates graph.getRequirements())
        // would never see a java-controller binding at all: no Java source copied, no security
        // manifest entry, silently nothing mounted despite a valid model declaration -- the exact
        // "declared but never wired" shape REG-73/Move 3 G4 already found once for procedure-only
        // capabilities. Synthesize ONE requirement per plugin:java-controller binding whose capability
        // was not already discovered by a real call site, using a fixed operationName sentinel
        // ("mount", never a real flow/procedure operation) so it still satisfies
        // GeneratedPluginMountPlan.isMountCandidate()'s non-blank-operation check.
        addSyntheticControllerMountRequirements(capabilitiesByName, bindingsByCapability, requirements);

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

    private static void collectFromProcedureSteps(
            String procedureName,
            List<ProcedureStepAst> steps,
            Map<String, CapabilityAst> capabilitiesByName,
            Map<String, String> bindingsByCapability,
            List<CompiledPluginRequirement> out
    ) {
        for (ProcedureStepAst step : steps) {
            String normalizedType = normalize(step.type());
            if ("capabilitycall".equals(normalizedType) || "callcapability".equals(normalizedType)) {
                String capabilityName = safe(step.capability());
                CapabilityAst capability = capabilitiesByName.get(normalize(capabilityName));
                String capabilityType = capability == null ? "" : safe(capability.getType());
                out.add(new CompiledPluginRequirement(
                        capabilityName,
                        capabilityType,
                        safe(step.operation()),
                        safe(procedureName),
                        safe(step.name()),
                        bindingsByCapability.getOrDefault(normalize(capabilityName), ""),
                        isExternalCandidate(capabilityType)
                ));
            }

            if (!step.thenSteps().isEmpty()) {
                collectFromProcedureSteps(procedureName, step.thenSteps(), capabilitiesByName, bindingsByCapability, out);
            }
            if (!step.elseSteps().isEmpty()) {
                collectFromProcedureSteps(procedureName, step.elseSteps(), capabilitiesByName, bindingsByCapability, out);
            }
            if (!step.steps().isEmpty()) {
                collectFromProcedureSteps(procedureName, step.steps(), capabilitiesByName, bindingsByCapability, out);
            }
        }
    }

    /** The one adapter id whose mount is never reached by a flow/procedure walk (see the call site's
     *  comment). Kept as a single named constant rather than an inline literal so
     *  GeneratedPluginMountPlan's own mountKind() switch and this synthesis stay easy to find as a
     *  pair -- both must agree on the exact adapter string or a controller binding either never
     *  mounts (this method silently produces nothing) or mounts as GENERIC_MOUNTED instead of
     *  JAVA_CONTROLLER (GeneratedPluginMountPlan silently loses the descriptor/security-wrapper path). */
    private static final String JAVA_CONTROLLER_ADAPTER = "plugin:java-controller";

    private static void addSyntheticControllerMountRequirements(
            Map<String, CapabilityAst> capabilitiesByName,
            Map<String, String> bindingsByCapability,
            List<CompiledPluginRequirement> out
    ) {
        Set<String> alreadyDiscovered = new HashSet<>();
        for (CompiledPluginRequirement requirement : out) {
            alreadyDiscovered.add(normalize(requirement.capabilityName()));
        }
        for (Map.Entry<String, String> entry : bindingsByCapability.entrySet()) {
            String normalizedCapability = entry.getKey();
            if (!JAVA_CONTROLLER_ADAPTER.equals(normalize(entry.getValue()))) {
                continue;
            }
            if (!alreadyDiscovered.add(normalizedCapability)) {
                continue;
            }
            CapabilityAst capability = capabilitiesByName.get(normalizedCapability);
            String capabilityName = capability == null ? normalizedCapability : safe(capability.getName());
            String capabilityType = capability == null ? "" : safe(capability.getType());
            out.add(new CompiledPluginRequirement(
                    capabilityName,
                    capabilityType,
                    "mount",
                    "",
                    "",
                    entry.getValue(),
                    isExternalCandidate(capabilityType)
            ));
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
