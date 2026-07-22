package com.finalexec.npdev.service.internal;

import com.finalexec.npdev.service.*;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CapabilityReadinessClassifier {

    public String classify(
            String bindingStatus,
            List<String> runtimeUsage,
            List<String> draftUsage,
            int successfulExecutionCount
    ) {
        boolean hasRuntimeUsage = runtimeUsage != null && !runtimeUsage.isEmpty();
        boolean hasDraftUsage = draftUsage != null && !draftUsage.isEmpty();
        boolean bound = "BOUND".equals(bindingStatus);

        if (!bound && (hasRuntimeUsage || hasDraftUsage)) {
            return "BLOCKED";
        }
        if (bound && hasRuntimeUsage && successfulExecutionCount > 0) {
            return "RUNNABLE_TODAY";
        }
        if (bound && hasRuntimeUsage) {
            return "PARTIALLY_RUNNABLE";
        }
        if (bound || hasDraftUsage) {
            return "INSPECTION_ONLY";
        }
        return "BLOCKED";
    }

    public String proofStatus(
            String classification,
            String bindingStatus,
            List<String> runtimeUsage,
            List<String> draftUsage,
            int successfulExecutionCount
    ) {
        if ("RUNNABLE_TODAY".equals(classification) && successfulExecutionCount > 0) {
            return "SUCCESSFUL_RUNTIME_PROOF";
        }
        if ("PARTIALLY_RUNNABLE".equals(classification) && runtimeUsage != null && !runtimeUsage.isEmpty()) {
            return "RUNTIME_FLOW_PRESENT_NO_SUCCESS_PROOF";
        }
        if ("INSPECTION_ONLY".equals(classification) && draftUsage != null && !draftUsage.isEmpty()) {
            return "DRAFT_ONLY_NO_RUNTIME_PROOF";
        }
        if ("INSPECTION_ONLY".equals(classification) && "BOUND".equals(bindingStatus)) {
            return "CONFIGURED_NO_RUNTIME_PROOF";
        }
        return "NO_RUNTIME_PROOF";
    }

    public String band(String classification) {
        return switch (classification) {
            case "RUNNABLE_TODAY" -> "READY";
            case "PARTIALLY_RUNNABLE" -> "PARTIAL";
            case "INSPECTION_ONLY" -> "INSPECTION";
            case "BLOCKED" -> "BLOCKED";
            default -> "UNKNOWN";
        };
    }

    public String narrative(
            String classification,
            String proofStatus,
            List<String> runtimeUsage,
            List<String> draftUsage,
            int successfulExecutionCount
    ) {
        return switch (classification) {
            case "RUNNABLE_TODAY" ->
                    "Capability is linked by runtime flows and has successful same-tenant direct execution proof.";
            case "PARTIALLY_RUNNABLE" ->
                    "Capability is linked by runtime flows, but no successful direct execution proof is recorded yet.";
            case "INSPECTION_ONLY" -> inspectionNarrative(proofStatus, runtimeUsage, draftUsage, successfulExecutionCount);
            case "BLOCKED" ->
                    "Capability is referenced but cannot be treated as runnable because binding or runtime proof is missing.";
            default -> "Capability readiness needs review.";
        };
    }

    private String inspectionNarrative(
            String proofStatus,
            List<String> runtimeUsage,
            List<String> draftUsage,
            int successfulExecutionCount
    ) {
        if ("DRAFT_ONLY_NO_RUNTIME_PROOF".equals(proofStatus) && draftUsage != null && !draftUsage.isEmpty()) {
            return "Capability is visible in draft flows only; runtime proof is not present yet.";
        }
        if ("CONFIGURED_NO_RUNTIME_PROOF".equals(proofStatus) && (runtimeUsage == null || runtimeUsage.isEmpty())) {
            return "Capability is configured and inspectable, but no runtime flow or success proof is currently recorded.";
        }
        if (successfulExecutionCount > 0) {
            return "Capability has some historical proof, but the currently visible flow linkage needs review.";
        }
        return "Capability can be inspected, but runtime readiness is not yet proven.";
    }
}
