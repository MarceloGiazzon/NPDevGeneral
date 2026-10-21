package com.npdev.dsl.v1.compiled;

import java.util.List;

/**
 * PROC-1: compiled counterpart of {@link com.npdev.dsl.v1.ast.HumanTaskAst} -- copied 1:1, no resolution.
 */
public final class CompiledHumanTask {

    /** One form field the assignee fills in. type is one of string|text|integer|decimal|boolean|date. */
    public record Field(String name, String type, String label, boolean required, Integer maxLength) {
    }

    /** One completion button; value lands in the event payload under outcomeField. */
    public record Outcome(String value, String label) {
    }

    private final String title;
    private final String description;
    private final String assigneeUser;
    private final List<String> assigneeRoles;
    private final String outcomeField;
    private final List<Outcome> outcomes;
    private final List<Field> fields;
    private final String record;
    private final String proposalValue;
    private final String proposalCurrent;

    public CompiledHumanTask(String title, String description, String assigneeUser, List<String> assigneeRoles,
                        String outcomeField, List<Outcome> outcomes, List<Field> fields, String record) {
        this(title, description, assigneeUser, assigneeRoles, outcomeField, outcomes, fields, record, null, null);
    }

    public CompiledHumanTask(String title, String description, String assigneeUser, List<String> assigneeRoles,
                        String outcomeField, List<Outcome> outcomes, List<Field> fields, String record,
                        String proposalValue, String proposalCurrent) {
        this.title = title;
        this.description = description;
        this.assigneeUser = assigneeUser;
        this.assigneeRoles = assigneeRoles == null ? List.of() : List.copyOf(assigneeRoles);
        this.outcomeField = outcomeField == null || outcomeField.isBlank() ? "outcome" : outcomeField.trim();
        this.outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.record = record;
        this.proposalValue = proposalValue;
        this.proposalCurrent = proposalCurrent;
    }

    public String getTitle() { return title; }

    public String getDescription() { return description; }

    /** Flow-state ref (e.g. {@code $saved.recipientUsername}) resolving to the assignee user; may be null. */
    public String getAssigneeUser() { return assigneeUser; }

    /** Roles any of whose holders may answer; may be empty (then assigneeUser is set). */
    public List<String> getAssigneeRoles() { return assigneeRoles; }

    /** Payload key that receives the chosen outcome value; defaults to {@code outcome}. */
    public String getOutcomeField() { return outcomeField; }

    public List<Outcome> getOutcomes() { return outcomes; }

    public List<Field> getFields() { return fields; }

    /** Optional flow-state ref to the business record this task is about. */
    public String getRecord() { return record; }

    /** AI7-1: flow-state ref to the proposed values this task asks a human to review; null for a plain task. */
    public String getProposalValue() { return proposalValue; }

    /** AI7-1: flow-state ref to the current record the proposal is diffed against; may be null. */
    public String getProposalCurrent() { return proposalCurrent; }
}
