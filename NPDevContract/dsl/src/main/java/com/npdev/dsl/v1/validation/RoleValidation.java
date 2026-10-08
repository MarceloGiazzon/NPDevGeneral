package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.RoleAst;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * Wave 3 (RC-B1): structural checks for the optional top-level {@code roles} declaration -- name
 * uniqueness/non-blank and grants non-empty/unique. Grant names are NOT checked against the real
 * {@code Permission} enum here (see {@link RoleAst}'s javadoc: the dsl module has no dependency on
 * the kernel module); that check happens at runtime boot instead.
 *
 * <p>Roles and users: a role may instead (or also) carry {@code concepts} -- concept name ->
 * operations from {@link RoleAst#CONCEPT_OPERATIONS}. Each concept must exist and each operation
 * must be known; a role with neither grants nor concepts grants nothing and is rejected.
 */
final class RoleValidation {

    private RoleValidation() {
    }

    static void validateRoles(ModelAst modelAst, Map<String, ConceptAst> entitiesByLower, List<String> errors) {
        Set<String> roleNames = new HashSet<>();
        for (RoleAst role : modelAst.getRoles()) {
            if (!hasText(role.name())) {
                errors.add("Role: name is required");
                continue;
            }
            String here = "Role " + role.name();
            if (!roleNames.add(normalize(role.name()))) {
                errors.add(here + ": duplicate role name");
            }
            validateConcepts(role, here, entitiesByLower, errors);
            if (role.grants().isEmpty()) {
                if (role.concepts().isEmpty()) {
                    errors.add(here + ": grants must not be empty");
                }
                continue;
            }
            Set<String> grantNames = new HashSet<>();
            for (String grant : role.grants()) {
                if (!hasText(grant)) {
                    errors.add(here + ": grant name is required");
                    continue;
                }
                if (!grantNames.add(normalize(grant))) {
                    errors.add(here + ": duplicate grant " + grant);
                }
            }
        }
    }

    private static void validateConcepts(RoleAst role, String here, Map<String, ConceptAst> entitiesByLower,
                                         List<String> errors) {
        for (Map.Entry<String, List<String>> entry : role.concepts().entrySet()) {
            String concept = entry.getKey();
            if (!entitiesByLower.containsKey(normalize(concept))) {
                errors.add(here + ": concepts names unknown concept " + concept
                        + " -- suggestedFix: use the name of a concept declared in this model, or remove the entry");
            }
            if (entry.getValue().isEmpty()) {
                errors.add(here + ": concepts." + concept + " must list at least one operation"
                        + " -- suggestedFix: list read, create, update and/or delete, or remove the entry");
            }
            Set<String> operations = new HashSet<>();
            for (String operation : entry.getValue()) {
                if (!RoleAst.CONCEPT_OPERATIONS.contains(operation)) {
                    errors.add(here + ": concepts." + concept + " has unknown operation '" + operation
                            + "' -- suggestedFix: use one of " + RoleAst.CONCEPT_OPERATIONS);
                } else if (!operations.add(operation)) {
                    errors.add(here + ": concepts." + concept + " repeats operation " + operation
                            + " -- suggestedFix: list each operation once");
                }
            }
        }
    }
}
