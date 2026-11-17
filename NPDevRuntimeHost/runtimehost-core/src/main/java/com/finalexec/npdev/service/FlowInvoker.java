package com.finalexec.npdev.service;

import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;

import java.util.Map;

/**
 * FORM P3-T5c: how a caller that used to run a procedure by name (a panel action or data source, an aggregate hook, a
 * workbench action) runs the flow that replaced it. Wired late (the kernel runner needs the procedure runner, so the
 * procedure runner cannot be handed the kernel runner at construction): {@link ProcedureRunner#setFlowInvoker}.
 */
@FunctionalInterface
public interface FlowInvoker {

    ExecutionResult execute(String flowName, Map<String, Object> input, ExecutionContext context);
}
