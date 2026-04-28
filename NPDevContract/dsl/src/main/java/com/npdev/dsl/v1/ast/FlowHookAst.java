package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class FlowHookAst {
    private final String position;
    private final String targetStep;
    private final List<StepAst> steps;

    public FlowHookAst(String position, String targetStep, List<StepAst> steps) {
        this.position = position;
        this.targetStep = targetStep;
        this.steps = steps == null ? List.of() : new ArrayList<>(steps);
    }

    public String getPosition() {
        return position;
    }

    public String getTargetStep() {
        return targetStep;
    }

    public List<StepAst> getSteps() {
        return Collections.unmodifiableList(steps);
    }
}
