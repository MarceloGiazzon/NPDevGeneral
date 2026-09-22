package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ports.InvariantEngine;

import java.util.List;
import java.util.Map;

/**
 * REG-208 (B28 lift): a mutable wrapper whose {@link #setModel(CompiledModel)} rebuilds its
 * {@link CelInvariantEngine} delegate in place on a live model reload -- {@code CelInvariantEngine}
 * itself stays immutable/untouched, built once per {@code fromCompiledModel} call as today.
 */
public final class ReloadableInvariantEngine implements InvariantEngine {
    private volatile CelInvariantEngine delegate;

    public ReloadableInvariantEngine(CompiledModel initialModel) {
        this.delegate = CelInvariantEngine.fromCompiledModel(initialModel);
    }

    /** Rebuilds the delegate from the reloaded model -- the SAME instance of this wrapper keeps
     * being used by every existing holder of the {@link InvariantEngine} interface reference. */
    public void setModel(CompiledModel model) {
        this.delegate = CelInvariantEngine.fromCompiledModel(model);
    }

    @Override
    public List<String> evaluate(String entityName, Object payload) {
        return delegate.evaluate(entityName, payload);
    }

    @Override
    public List<Violation> evaluate(List<String> invariants, EvaluationContext context) {
        return delegate.evaluate(invariants, context);
    }

    @Override
    public InvariantEvaluationResult evaluate(InvariantEvaluationRequest request) {
        return delegate.evaluate(request);
    }

    @Override
    public List<Violation> evaluateAggregateInvariants(
            String aggregateName,
            String rootConcept,
            List<AggregateInvariantSpec> invariants,
            Map<String, Object> draftTree
    ) {
        return delegate.evaluateAggregateInvariants(aggregateName, rootConcept, invariants, draftTree);
    }
}
