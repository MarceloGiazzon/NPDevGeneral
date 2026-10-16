package com.npdev.dsl.v1.ast;

import java.util.List;

/**
 * PROC-1: the optional {@code task} block on an {@code awaitEvent} flow step. Declares that the
 * wait is a HUMAN task: who may answer it (assignee), what they see (title/description templates,
 * {@code ${path}} over flow state), what they fill in (fields) and which buttons complete it
 * (outcomes). Completing the task publishes the step's own awaited event (runtime: PROC-2), so the
 * kernel needs no change -- this is pure metadata riding the compiled model.
 */
public final class HumanTaskAst {

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

    public HumanTaskAst(String title, String description, String assigneeUser, List<String> assigneeRoles,
                        String outcomeField, List<Outcome> outcomes, List<Field> fields, String record) {
        this.title = title;
        this.description = description;
        this.assigneeUser = assigneeUser;
        this.assigneeRoles = assigneeRoles == null ? List.of() : List.copyOf(assigneeRoles);
        this.outcomeField = outcomeField == null || outcomeField.isBlank() ? "outcome" : outcomeField.trim();
        this.outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.record = record;
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
}
