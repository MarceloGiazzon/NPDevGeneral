package com.finalexec.npdev.service;

import com.finalexec.config.ModelHolder;
import com.npdev.adapters.flowcompiled.CompiledModelFlowDefinitionProvider;
import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityOperation;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.capabilities.CapabilityExecutionPolicy;

import java.util.function.Function;

/**
 * RUN-1/RUN-2: resolves the execution policy a model declares on a capability operation
 * ({@code capabilities[].operations[].policy}), for capability calls made outside a flow. One lookup
 * shared by the procedure runner and the orchestration dispatcher, reading the live model on every
 * call so a model reload is observed.
 */
public final class CapabilityOperationPolicies {

    private CapabilityOperationPolicies() {
    }

    public static Function<CapabilityCall, CapabilityExecutionPolicy> forModel(ModelHolder modelHolder) {
        return call -> policyFor(modelHolder.get(), call);
    }

    static CapabilityExecutionPolicy policyFor(CompiledModel model, CapabilityCall call) {
        if (model == null || call == null || call.capability() == null || call.operation() == null) {
            return CapabilityExecutionPolicy.defaults();
        }
        for (CompiledCapability capability : model.getCapabilities()) {
            if (capability.getName() == null
                    || !capability.getName().trim().equalsIgnoreCase(call.capability().trim())) {
                continue;
            }
            for (CompiledCapabilityOperation operation : capability.getOperations()) {
                if (operation.getName() != null
                        && operation.getName().trim().equalsIgnoreCase(call.operation().trim())) {
                    return CompiledModelFlowDefinitionProvider.toExecutionPolicy(operation.getExecutionPolicy());
                }
            }
        }
        return CapabilityExecutionPolicy.defaults();
    }
}
