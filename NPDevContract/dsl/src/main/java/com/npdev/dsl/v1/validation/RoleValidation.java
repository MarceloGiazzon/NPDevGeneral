package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.RoleAst;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * Wave 3 (RC-B1): structural checks for the optional top-level {@code roles} declaration -- name
 * uniqueness/non-blank and grants non-empty/unique. Each grant must also name a platform permission
 * from {@link RoleAst#PLATFORM_PERMISSIONS} (the dsl's pinned mirror of the kernel's
 * {@code Permission} enum), so a bad grant fails validation instead of {@code Permission.valueOf}
 * at app boot. A {@code concept:verb} grant gets pointed at {@code roles[].concepts}.
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
                    continue;
                }
                validateGrantName(grant, here, entitiesByLower, errors);
            }
        }
    }

    private static void validateGrantName(String grant, String here, Map<String, ConceptAst> entitiesByLower,
                                          List<String> errors) {
        String upper = grant.trim().toUpperCase(Locale.ROOT);
        if (RoleAst.PLATFORM_PERMISSIONS.contains(upper)) {
            return;
        }
        String head = here + ": grants entry \"" + grant + "\" is not a platform permission (accepted: "
                + String.join(", ", RoleAst.PLATFORM_PERMISSIONS) + ")";
        int colon = grant.indexOf(':');
        if (colon > 0 && colon < grant.length() - 1) {
            String conceptPart = grant.substring(0, colon).trim();
            String verb = grant.substring(colon + 1).trim().toLowerCase(Locale.ROOT);
            ConceptAst declared = entitiesByLower.get(normalize(conceptPart));
            String concept = declared != null && hasText(declared.getName())
                    ? declared.getName()
                    : Character.toUpperCase(conceptPart.charAt(0)) + conceptPart.substring(1);
            String operations = RoleAst.CONCEPT_OPERATIONS.contains(verb) && !"read".equals(verb)
                    ? "[\"read\",\"" + verb + "\"]"
                    : "[\"read\",\"create\"]";
            errors.add(head + " -- suggestedFix: per-concept operations go in roles[].concepts, e.g. "
                    + "\"concepts\": {\"" + concept + "\": " + operations + "}");
            return;
        }
        errors.add(head + " -- suggestedFix: replace \"" + grant + "\" with one of the accepted platform "
                + "permissions, or move per-concept access to roles[].concepts");
    }

    private static void validateConcepts(RoleAst role, String here, Map<String, ConceptAst> entitiesByLower,
                                         List<String> errors) {
        for (Map.Entry<String, List<String>> entry : role.concepts().entrySet()) {
            String concept = entry.getKey();
            if (!entitiesByLower.containsKey(normalize(concept))) {
                errors.add(here + ": concepts names unknown concept " + concept
                        + " (declared concepts: " + declaredConceptNames(entitiesByLower) + ")"
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

    private static String declaredConceptNames(Map<String, ConceptAst> entitiesByLower) {
        Set<String> names = new TreeSet<>();
        for (ConceptAst concept : entitiesByLower.values()) {
            if (concept != null && hasText(concept.getName())) {
                names.add(concept.getName());
            }
        }
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
