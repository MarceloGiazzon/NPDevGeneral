package com.finalexec.config;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.concepts.ConceptGatewaySemanticPolicy;
import com.npdev.kernel.concepts.ConfiguredConceptGatewaySemanticPolicy;

public final class RuntimeConceptGatewaySemanticPolicies {

    private RuntimeConceptGatewaySemanticPolicies() {
    }

    /**
     * @deprecated Move 6 §7.5 (docs/MOVE6_TYPED_SURFACE_PLAN.md): the CompiledModel -> policy
     *     mapping moved to {@link ConfiguredConceptGatewaySemanticPolicy#fromCompiledModel}, which
     *     has no RuntimeHost dependency and so is reachable from a plain kernel-module unit test
     *     too. This wrapper is kept only so existing production callers don't need to change;
     *     call the kernel method directly in new code.
     */
    @Deprecated(forRemoval = false)
    public static ConceptGatewaySemanticPolicy fromCompiledModel(CompiledModel compiledModel) {
        return ConfiguredConceptGatewaySemanticPolicy.fromCompiledModel(compiledModel);
    }
}
