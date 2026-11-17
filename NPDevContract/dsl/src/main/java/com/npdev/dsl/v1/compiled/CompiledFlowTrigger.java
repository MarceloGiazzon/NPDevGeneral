package com.npdev.dsl.v1.compiled;

/** FORM P3-T5a: compiled counterpart of {@code FlowTriggerAst}. */
public record CompiledFlowTrigger(String kind, String event, String condition, String webhook) {
}
