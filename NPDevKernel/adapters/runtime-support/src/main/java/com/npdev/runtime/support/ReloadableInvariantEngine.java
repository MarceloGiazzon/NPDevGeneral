package com.npdev.runtime.support;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ports.InvariantEngine;

import java.util.List;
import java.util.Map;

/**
 * REG-208 (B28 lift): a mutable wrapper whose {@link #setModel(CompiledModel)} rebuilds its
 * {@link CelInvariantEngine} delegate in place on a live model reload -- {@code CelInvariantEngine}
 * itself stays immutable/untouched, built once per {@code fromCompiledModel} call as today.
 *
 * <p>REG-283: this is the engine flow steps use, and a flow only knows its tenant at evaluation
 * time (KernelRunner seeds {@code state.tenantId} from the caller's ExecutionContext). With
 * {@link TenantLookups}, each {@link #evaluate(InvariantEvaluationRequest)} binds lookups scoped to
 * THAT tenant; a request whose state carries no tenant gets lookups that refuse, so a unique/scope
 * rule reports {@code INVARIANT_UNCHECKABLE} instead of searching some other tenant's rows. The
 * overloads without state never have a tenant, so their lookups are unwired (also uncheckable).
 */
public final class ReloadableInvariantEngine implements InvariantEngine {

    /** Builds the lookups for one tenant; {@code conceptName} is the concept the request checks. */
    @FunctionalInterface
    public interface TenantLookups {
        CelInvariantEngine.Lookups forTenant(String tenantId, String conceptName);
    }

    static final String NO_TENANT_REASON =
            "the flow state carries no tenantId, so there is no tenant whose rows to search";

    private volatile CelInvariantEngine delegate;
    private final TenantLookups tenantLookups;

    public ReloadableInvariantEngine(CompiledModel initialModel) {
        this(initialModel, null);
    }

    public ReloadableInvariantEngine(CompiledModel initialModel, TenantLookups tenantLookups) {
        this.delegate = CelInvariantEngine.fromCompiledModel(initialModel);
        this.tenantLookups = tenantLookups;
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
        CelInvariantEngine engine = delegate;
        if (tenantLookups == null) {
            return engine.evaluate(request);
        }
        Object tenant = request.state().get("tenantId");
        String tenantId = tenant == null ? null : String.valueOf(tenant).trim();
        CelInvariantEngine.Lookups lookups = tenantId == null || tenantId.isEmpty()
                ? CelInvariantEngine.Lookups.unavailable(NO_TENANT_REASON)
                : tenantLookups.forTenant(tenantId, request.conceptName());
        return engine.withLookups(lookups).evaluate(request);
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
