package com.finalexec.npdev.service;

import com.finalexec.config.ModelHolder;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import com.npdev.kernel.procedures.DefaultProcedureExecutor;
import com.npdev.kernel.procedures.ProcedureDefinition;
import com.npdev.kernel.procedures.ProcedureExecutionResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared execution of a compiled model's flows by name (procedures[] became flows), used by both the panel runtime
 * (panel action bindings, data-source procedures) and the aggregate runtime (procedure-over-aggregate
 * invoke). Extracting this here breaks the {@code AggregateRuntime -> PanelRuntime} injection cycle:
 * both services depend on this leaf runner instead of on each other.
 */
@Service
public class ProcedureRunner {

    // REG-208 (B28 lift): a live ModelHolder, not a directly-injected CompiledModel snapshot -- every
    // use below is a fresh per-call lookup (buildQueriesByName et al.), never a cached
    // derivation, so no ModelReloadListener is needed: modelHolder.get() alone observes a reload.
    private final ModelHolder modelHolder;
    private final ConceptGateway conceptGateway;
    // FORM P3-T5c: runs a flow by name; null (tests, bare hosts) = nothing can be run by name.
    private volatile FlowInvoker flowInvoker;

    @Autowired
    public ProcedureRunner(ModelHolder modelHolder, ObjectProvider<ConceptGateway> conceptGateway) {
        this(modelHolder, conceptGateway == null ? null : conceptGateway.getIfAvailable());
    }

    /** Convenience overload for existing call sites (PanelRuntime, tests) with a plain {@link
     * CompiledModel} and no live {@link ModelHolder} -- wraps it in a non-reloading holder. */
    public ProcedureRunner(CompiledModel compiledModel, ConceptGateway conceptGateway) {
        this(new ModelHolder(compiledModel), conceptGateway);
    }

    public ProcedureRunner(ModelHolder modelHolder, ConceptGateway conceptGateway) {
        this.modelHolder = modelHolder;
        this.conceptGateway = conceptGateway;
    }

    /**
     * FORM P3-T5c: lets a name that is no longer a procedure (it became a flow) still be run by the callers that used to
     * run it, and answers in the procedure result shape ({@code state.return}) they already read.
     */
    public void setFlowInvoker(FlowInvoker flowInvoker) {
        this.flowInvoker = flowInvoker;
    }

    /**
     * Wires both directions between this runner and {@code kernel}: the kernel's concept steps (set, readConcept,
     * runQuery ...) execute through this runner's step executor, and a name run here executes as the kernel's flow.
     */
    public void attach(com.npdev.kernel.KernelRunner kernel) {
        kernel.withProcedureExecutor((definition, input, context) -> execute(definition, input, context));
        setFlowInvoker(kernel::execute);
    }

    /** Whether the compiled model declares a flow with this name. */
    public boolean hasFlow(String flowName) {
        if (flowName == null || flowName.isBlank()) {
            return false;
        }
        CompiledModel model = modelHolder.get();
        return model != null && model.getFlows().stream().anyMatch(flow -> flowName.trim().equals(flow.getName()));
    }

    /** Whether a flow invoker is wired and the model declares a flow with this name: what {@link #execute(String, Map, ExecutionContext)} can run. */
    public boolean canRun(String name) {
        return flowInvoker != null && hasFlow(name);
    }

    /**
     * Runs the flow named {@code name} (procedures[] became flows) and answers in the procedure result shape
     * ({@code state.return}) the callers already read.
     *
     * @throws IllegalArgumentException if the name is blank or no such flow is declared / no flow invoker is wired
     */
    public ProcedureExecutionResult execute(
            String name,
            Map<String, Object> input,
            ExecutionContext context
    ) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Procedure execution requires a procedure name.");
        }
        FlowInvoker invoker = flowInvoker;
        if (invoker != null && hasFlow(name)) {
            return toProcedureResult(invoker.execute(
                    name.trim(),
                    input == null ? Map.of() : input,
                    context == null ? ExecutionContext.anonymous() : context));
        }
        throw new IllegalArgumentException("Procedure not found: " + name);
    }

    /**
     * FORM P3-T5c: a flow's result as the {@link ProcedureExecutionResult} its callers read: success carries the flow's
     * output as {@code state.return}; any other status is a failure whose code is the leading {@code CODE:} of the error
     * text when it has one (the kernel prefixes a failed step's code), else the status name (the kernel's generic {@code system_exception} adds nothing). A flow that parks
     * (WAITING_EVENT) cannot answer a synchronous caller and is reported as a failure.
     */
    static ProcedureExecutionResult toProcedureResult(com.npdev.kernel.ExecutionResult result) {
        if (result.getStatus() == com.npdev.kernel.ExecutionStatus.OK) {
            Map<String, Object> state = new java.util.LinkedHashMap<>();
            state.put("return", result.getOutput());
            return ProcedureExecutionResult.success(state, java.util.List.of());
        }
        String message = result.getError() == null ? "" : result.getError();
        String code = result.getStatus().name();
        java.util.regex.Matcher leading = java.util.regex.Pattern.compile("^([A-Z][A-Z0-9_]{2,}):\\s").matcher(message);
        if (leading.find()) {
            code = leading.group(1);
            message = message.substring(leading.end());
        } else if (result.getStatus() == com.npdev.kernel.ExecutionStatus.WAITING_EVENT) {
            code = "FLOW_WAITING";
            message = "The flow parked on an event and cannot answer a synchronous caller.";
        }
        return ProcedureExecutionResult.failure(Map.of(), java.util.List.of(), code, message);
    }

    /**
     * FORM P3-T5b: runs a definition the caller built -- not necessarily one the model declares. A flow's
     * {@code readConcept} / {@code runQuery} / {@code set} ... step is carried as a one-step definition so it runs the
     * very code a procedure step runs; it has no entry in the model's procedure list, so it cannot be found by name.
     */
    public ProcedureExecutionResult execute(
            ProcedureDefinition definition,
            Map<String, Object> input,
            ExecutionContext context
    ) {
        if (conceptGateway == null) {
            throw new IllegalStateException("ConceptGateway is required to execute procedures.");
        }
        DefaultProcedureExecutor executor = new DefaultProcedureExecutor(
                conceptGateway,
                com.npdev.kernel.procedures.ProcedureExecutionLimits.defaults(),
                buildQueriesByName(),
                null
        );
        return executor.execute(definition, input == null ? Map.of() : input,
                context == null ? ExecutionContext.anonymous() : context);
    }

    /** LIFT-QUERY-P1: lets a {@code runQuery} procedure step resolve its declared query's
     * where/orderBy/limit instead of always returning every row for the concept. */
    private Map<String, com.npdev.dsl.v1.compiled.CompiledQuery> buildQueriesByName() {
        CompiledModel model = modelHolder.get();
        if (model == null) {
            return Map.of();
        }
        Map<String, com.npdev.dsl.v1.compiled.CompiledQuery> queries = new LinkedHashMap<>();
        for (com.npdev.dsl.v1.compiled.CompiledQuery query : model.getQueries()) {
            queries.put(query.name(), query);
        }
        return Map.copyOf(queries);
    }
}
