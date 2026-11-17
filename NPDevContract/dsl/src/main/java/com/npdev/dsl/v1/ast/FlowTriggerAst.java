package com.npdev.dsl.v1.ast;

/**
 * FORM P3-T5a: what starts a flow. {@code kind} is {@code event} (with {@code event} and an optional
 * {@code condition}), {@code schedule} (the cron lives in the flow's own {@link FlowScheduleAst}) or
 * {@code webhook} (with the {@code webhook} name). A flow with no trigger is started by an API call.
 */
public record FlowTriggerAst(String kind, String event, String condition, String webhook) {
}
