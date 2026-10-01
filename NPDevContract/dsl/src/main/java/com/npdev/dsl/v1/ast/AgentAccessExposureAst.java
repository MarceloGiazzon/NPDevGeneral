package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One concept or one flow offered to agents. Exactly one of {@code concept} / {@code flow} is set
 * (enforced by {@code AgentAccessValidation}, not here).
 */
public final class AgentAccessExposureAst {
    private final String concept;
    private final String flow;
    private final List<String> operations;
    private final List<String> fields;
    private final List<String> roles;
    private final String description;
    private final Boolean confirmWrites;

    public AgentAccessExposureAst(String concept, String flow, List<String> operations, List<String> fields,
            List<String> roles, String description, Boolean confirmWrites) {
        this.concept = concept;
        this.flow = flow;
        this.operations = operations == null ? new ArrayList<>() : new ArrayList<>(operations);
        this.fields = fields == null ? new ArrayList<>() : new ArrayList<>(fields);
        this.roles = roles == null ? new ArrayList<>() : new ArrayList<>(roles);
        this.description = description;
        this.confirmWrites = confirmWrites;
    }

    public String getConcept() { return concept; }

    public String getFlow() { return flow; }

    public List<String> getOperations() { return Collections.unmodifiableList(operations); }

    public List<String> getFields() { return Collections.unmodifiableList(fields); }

    public List<String> getRoles() { return Collections.unmodifiableList(roles); }

    public String getDescription() { return description; }

    public Boolean getConfirmWrites() { return confirmWrites; }
}
