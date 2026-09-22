package com.npdev.kernel;

import com.npdev.kernel.capability.IdempotencyKeys;

import java.util.List;

/**
 * REG-238 (boundary B28): a cheap structural fingerprint of a flow's step tree, stamped into a
 * durable flow instance's state at {@code execute()} time and compared again at
 * {@code resumeExecution()} time.
 *
 * <p><b>Why this exists.</b> {@code KernelRunner.resumeExecution} resolves a persisted instance's
 * flow definition FRESH, by name, and then applies the checkpointed {@code currentStepIndex}
 * POSITIONALLY against whatever step list that lookup returns. If the flow was reshaped in between
 * -- an operator edits the model and redeploys, or hot-reloads it -- the resumed instance silently
 * re-enters the WRONG step. This class makes that detectable.
 *
 * <p><b>What is hashed, and what deliberately is not.</b> The fingerprint covers each step's
 * {@code name} and {@code type}, and the shape of the tree they sit in (the five nested child
 * lists), prefixed by the flow name. It does NOT cover step parameters -- {@code inputRef},
 * {@code argsRefs}, {@code outputRef}, condition text, capability/operation, timeouts, schemas.
 *
 * <p>That line is drawn where it is because the question this guard answers is narrow: <em>is the
 * checkpointed {@code currentStepIndex} still meaningful?</em> It is not "has anything about this
 * flow changed?". Editing a step's parameters in place leaves every step at its original index and
 * under its original name, so the resumed instance re-enters exactly the step it parked on and
 * merely behaves as the edited model says -- which is the ordinary, intended result of redeploying
 * an edited model. Hashing parameters would turn every routine {@code inputRef} tweak into a
 * mass-STUCK event across every in-flight instance of that flow: strictly worse than the hazard
 * being guarded against. A fail-safe should fire on the failure it can actually detect.
 *
 * <p><b>Why names and not just types and arity.</b> The durable checkpoints written INSIDE a step
 * are keyed by step name, not index -- {@code ForEachStep}'s {@code "__forEachProgress." + name},
 * {@code FlowStateCodec}'s await keys, and the per-step await deadline all look up by name. A step
 * renamed in place keeps its index, so a type-and-arity-only fingerprint would pass it, and every
 * one of those name-keyed lookups would then silently miss. Step names are validated unique per
 * flow by the DSL, so {@code (name, type)} is a sound identity.
 *
 * <p><b>Why nested children are included.</b> The same name-keyed progress applies inside
 * {@code forEach} bodies and {@code branch} arms, so reshaping a loop body is exactly as dangerous
 * as reshaping the top level -- even though the top-level {@code subList} index is untouched.
 *
 * <p><b>This is a safety net, not a security boundary.</b> The fingerprint rides in the instance's
 * own state map, so a model author could in principle overwrite {@link #STATE_KEY} with a
 * {@code map} step. That is accepted: the guard exists to catch operator/deploy accidents, not to
 * resist a hostile model, which already has far more direct means.
 */
final class FlowShapeFingerprint {

    /**
     * The reserved state key the fingerprint is stored under.
     *
     * <p>Flat double-underscore form, matching {@code CompensationRunner}'s {@code __npdev_…__}
     * convention rather than {@code FlowStateCodec}'s dotted {@code _npdev.await}: a dot in a state
     * key is ambiguous with {@code KernelRunner}'s nested-path reference resolution. It is also
     * deliberately outside the {@code _npdev.await}, {@code __forEachProgress.}, parallel-loop and
     * compensation namespaces, each of which has code that removes its own keys.
     */
    static final String STATE_KEY = "__npdev_flowShape__";

    private FlowShapeFingerprint() {
    }

    /** The fingerprint of {@code flow}'s current step tree. Never null. */
    static String of(FlowDefinition flow) {
        StringBuilder canonical = new StringBuilder("Flow|").append(flow.getName());
        appendSteps(canonical, flow.getSteps());
        return IdempotencyKeys.sha256Hex(canonical.toString());
    }

    private static void appendSteps(StringBuilder out, List<FlowStepDefinition> steps) {
        if (steps == null || steps.isEmpty()) {
            return;
        }
        for (FlowStepDefinition step : steps) {
            // name is guaranteed non-blank by FlowStepDefinition's own constructor; type is an enum.
            out.append('|').append(step.getName()).append(':').append(step.getType());
            appendChildren(out, "then", step.getThenSteps());
            appendChildren(out, "else", step.getElseSteps());
            appendChildren(out, "loop", step.getLoopSteps());
            appendChildren(out, "onFailure", step.getOnFailureSteps());
            appendChildren(out, "onTimeout", step.getOnTimeoutSteps());
        }
    }

    private static void appendChildren(StringBuilder out, String label, List<FlowStepDefinition> children) {
        if (children == null || children.isEmpty()) {
            // Written as nothing rather than as an empty bracket pair, so that "has no else arm"
            // and "has an empty else arm" fingerprint alike -- they are the same shape to a
            // positional index.
            return;
        }
        out.append(label).append('[');
        appendSteps(out, children);
        out.append(']');
    }
}
