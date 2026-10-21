package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.FlowAst;
import com.npdev.dsl.v1.ast.HumanTaskAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.RoleAst;
import com.npdev.dsl.v1.ast.StepAst;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * PROC-1: semantic rules for the optional {@code task} block of an {@code awaitEvent} step (the
 * schema already enforces required keys and shapes; this covers what a schema cannot: step-type
 * placement, duplicates, name clashes with the reserved payload keys and the parallelAwait
 * restriction).
 */
final class HumanTaskValidation {

    /** Roles every generated app understands without a roles[] entry. */
    private static final Set<String> BUILT_IN_ROLES = Set.of("USER", "OPERATOR", "ADMIN", "SUPERUSER");

    /** Payload key the runtime always stamps with the answering user. */
    static final String COMPLETED_BY = "completedBy";

    /** AI7-1: payload keys the runtime stamps from parked flow state when the task carries a proposal. */
    static final String PROPOSAL = "proposal";
    static final String RECORD_ID = "recordId";

    private HumanTaskValidation() {
    }

    static void validate(ModelAst model, List<String> errors, List<String> warnings) {
        Set<String> declaredRoles = new HashSet<>();
        for (RoleAst role : model.getRoles()) {
            if (role != null && hasText(role.name())) {
                declaredRoles.add(role.name().trim().toUpperCase(Locale.ROOT));
            }
        }
        for (FlowAst flow : model.getFlows()) {
            walk(flow, flow.getSteps(), false, declaredRoles, errors, warnings);
        }
    }

    private static void walk(
            FlowAst flow,
            List<StepAst> steps,
            boolean insideParallelForEach,
            Set<String> declaredRoles,
            List<String> errors,
            List<String> warnings
    ) {
        for (StepAst step : steps) {
            String type = normalize(step.getType());
            HumanTaskAst task = step.getTask();
            if (task != null) {
                String here = "Flow " + flow.getName() + " step " + step.getName() + ": ";
                if (!"await".equals(type)) {
                    errors.add(here + "task is only allowed on an awaitEvent step -- suggestedFix: move the task block onto an awaitEvent step or remove it");
                } else {
                    if (insideParallelForEach) {
                        errors.add(here + "task is not supported inside a parallelAwait forEach -- suggestedFix: remove the task block or set parallelAwait to false");
                    }
                    validateTask(here, task, declaredRoles, errors, warnings);
                }
            }
            boolean parallel = insideParallelForEach
                    || ("foreach".equals(type) && Boolean.TRUE.equals(step.getParallelAwait()));
            walk(flow, step.getThenSteps(), insideParallelForEach, declaredRoles, errors, warnings);
            walk(flow, step.getElseSteps(), insideParallelForEach, declaredRoles, errors, warnings);
            walk(flow, step.getLoopSteps(), parallel, declaredRoles, errors, warnings);
            walk(flow, step.getOnFailureSteps(), insideParallelForEach, declaredRoles, errors, warnings);
            walk(flow, step.getOnTimeoutSteps(), insideParallelForEach, declaredRoles, errors, warnings);
        }
    }

    private static void validateTask(
            String here,
            HumanTaskAst task,
            Set<String> declaredRoles,
            List<String> errors,
            List<String> warnings
    ) {
        if (!hasText(task.getTitle())) {
            errors.add(here + "task.title is required -- suggestedFix: give the task a non-blank title");
        }
        if (!hasText(task.getAssigneeUser()) && task.getAssigneeRoles().isEmpty()) {
            errors.add(here + "task.assignee needs a user ref or at least one role -- suggestedFix: set assignee.user to a flow-state ref or list assignee.roles");
        }
        for (String role : task.getAssigneeRoles()) {
            if (!hasText(role)) {
                errors.add(here + "task.assignee.roles contains a blank role -- suggestedFix: remove the blank role or name a real one");
                continue;
            }
            String upper = role.trim().toUpperCase(Locale.ROOT);
            if (!declaredRoles.contains(upper) && !BUILT_IN_ROLES.contains(upper)) {
                warnings.add(here + "task.assignee role '" + role + "' is not declared in roles[] and is not a"
                        + " built-in role (USER/OPERATOR/ADMIN/SUPERUSER); it only matches users that carry it");
            }
        }
        if (task.getOutcomes().isEmpty()) {
            errors.add(here + "task.outcomes needs at least one outcome -- suggestedFix: declare at least one outcome such as APPROVED");
        }
        Set<String> outcomeValues = new HashSet<>();
        for (HumanTaskAst.Outcome outcome : task.getOutcomes()) {
            if (!hasText(outcome.value())) {
                errors.add(here + "task.outcomes contains an outcome with a blank value -- suggestedFix: give every outcome a non-blank value");
            } else if (!outcomeValues.add(outcome.value().trim())) {
                errors.add(here + "task.outcomes has duplicate outcome value '" + outcome.value()
                        + "' -- suggestedFix: make every outcome value unique");
            }
        }
        Set<String> fieldNames = new HashSet<>();
        for (HumanTaskAst.Field field : task.getFields()) {
            if (!hasText(field.name())) {
                errors.add(here + "task.fields contains a field with a blank name -- suggestedFix: give every task field a non-blank name");
                continue;
            }
            String name = field.name().trim();
            if (!fieldNames.add(name)) {
                errors.add(here + "task.fields has duplicate field name '" + name
                        + "' -- suggestedFix: rename or remove the duplicate field");
            }
            if (name.equals(task.getOutcomeField()) || COMPLETED_BY.equals(name)) {
                errors.add(here + "task.fields name '" + name + "' clashes with a reserved payload key ("
                        + task.getOutcomeField() + ", " + COMPLETED_BY
                        + ") -- suggestedFix: rename the task field");
            }
            if (task.getProposalValue() != null && (PROPOSAL.equals(name) || RECORD_ID.equals(name))) {
                errors.add(here + "task.fields name '" + name + "' clashes with a payload key a task.proposal stamps ("
                        + PROPOSAL + ", " + RECORD_ID + ") -- suggestedFix: rename the task field");
            }
        }
        if (task.getProposalValue() != null) {
            if (!task.getProposalValue().trim().startsWith("$")) {
                errors.add(here + "task.proposal.value '" + task.getProposalValue() + "' is not a flow-state ref"
                        + " -- suggestedFix: point it at the step output holding the proposed values, e.g. '$suggestion'");
            }
            if (task.getProposalCurrent() != null && !task.getProposalCurrent().trim().startsWith("$")) {
                errors.add(here + "task.proposal.current '" + task.getProposalCurrent() + "' is not a flow-state ref"
                        + " -- suggestedFix: point it at the record the proposal changes, e.g. '$input'");
            }
            if (PROPOSAL.equals(task.getOutcomeField()) || RECORD_ID.equals(task.getOutcomeField())) {
                errors.add(here + "task.outcomeField '" + task.getOutcomeField() + "' clashes with a payload key a"
                        + " task.proposal stamps (" + PROPOSAL + ", " + RECORD_ID + ") -- suggestedFix: rename outcomeField");
            }
        }
    }
}
