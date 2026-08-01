package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.RoleAst;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * Wave 3 (RC-B1): structural checks for the optional top-level {@code roles} declaration -- name
 * uniqueness/non-blank and grants non-empty/unique. Grant names are NOT checked against the real
 * {@code Permission} enum here (see {@link RoleAst}'s javadoc: the dsl module has no dependency on
 * the kernel module); that check happens at runtime boot instead.
 */
final class RoleValidation {

    private RoleValidation() {
    }

    static void validateRoles(ModelAst modelAst, List<String> errors) {
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
            if (role.grants().isEmpty()) {
                errors.add(here + ": grants must not be empty");
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
}
