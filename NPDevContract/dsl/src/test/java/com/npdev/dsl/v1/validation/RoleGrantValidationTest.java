package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.RoleAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code roles[].grants} must name a platform permission at validate time -- before this, a grant
 * such as {@code "workorder:create"} validated clean and then crashed the app at boot with
 * {@code No enum constant Permission.WORKORDER:CREATE}. Also pins {@link RoleAst#PLATFORM_PERMISSIONS}
 * to the kernel's real {@code Permission} enum source, since the dsl cannot depend on the kernel.
 */
class RoleGrantValidationTest {

    private static List<String> errors(Path dir, String roles) throws Exception {
        Path modelPath = dir.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "roles.grants",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "WorkOrder", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] },
                    { "name": "Asset", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
                  ],
                  "roles": %s
                }
                """.formatted(roles));
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new SemanticValidator().validateWithWarnings(ast).getErrors();
    }

    @Test
    void realPermissionNamesValidateCaseInsensitively(@TempDir Path dir) throws Exception {
        List<String> errors = errors(dir, """
                [ { "name": "Ops", "grants": ["EXECUTE_FLOW", "read_executions", "READ_EVENTS"] } ]
                """);
        assertTrue(errors.isEmpty(), "expected no errors, got: " + errors);
    }

    @Test
    void aConceptVerbGrantIsRejectedAndPointedAtRolesConcepts(@TempDir Path dir) throws Exception {
        String all = String.join("\n", errors(dir, """
                [ { "name": "Technician", "grants": ["workorder:create"] } ]
                """));
        assertTrue(all.contains("Role Technician: grants entry \"workorder:create\" is not a platform permission"
                + " (accepted: " + String.join(", ", RoleAst.PLATFORM_PERMISSIONS) + ")"), all);
        assertTrue(all.contains("per-concept operations go in roles[].concepts, e.g. "
                + "\"concepts\": {\"WorkOrder\": [\"read\",\"create\"]}"), all);
    }

    @Test
    void aPlainUnknownGrantListsTheAcceptedValues(@TempDir Path dir) throws Exception {
        String all = String.join("\n", errors(dir, """
                [ { "name": "Boss", "grants": ["ADMIN"] } ]
                """));
        assertTrue(all.contains("grants entry \"ADMIN\" is not a platform permission (accepted: EXECUTE_FLOW,"), all);
        assertTrue(all.contains("suggestedFix: replace \"ADMIN\" with one of the accepted platform permissions"), all);
    }

    @Test
    void theGrantDiagnosticCarriesTheSiteSuggestedFix() {
        ValidationDiagnostic diagnostic = ValidationDiagnosticNormalizer.semanticDiagnostic(
                "Role Technician: grants entry \"workorder:create\" is not a platform permission (accepted: "
                        + String.join(", ", RoleAst.PLATFORM_PERMISSIONS) + ") -- suggestedFix: per-concept "
                        + "operations go in roles[].concepts, e.g. \"concepts\": {\"WorkOrder\": [\"read\",\"create\"]}",
                ValidationSeverity.ERROR);
        assertEquals("Per-concept operations go in roles[].concepts, e.g. \"concepts\": "
                + "{\"WorkOrder\": [\"read\",\"create\"]}", diagnostic.getSuggestedFix());
    }

    @Test
    void anUnknownConceptKeyListsTheDeclaredConcepts(@TempDir Path dir) throws Exception {
        String all = String.join("\n", errors(dir, """
                [ { "name": "Member", "concepts": { "Ghost": ["read"] } } ]
                """));
        assertTrue(all.contains("Role Member: concepts names unknown concept Ghost (declared concepts: Asset, WorkOrder)"),
                all);
    }

    @Test
    void platformPermissionsMirrorTheKernelEnum() throws IOException {
        Path source = workspaceRoot().resolve(
                "NPDevKernel/kernel/src/main/java/com/npdev/kernel/auth/Permission.java");
        String text = Files.readString(source);
        Matcher body = Pattern.compile("enum\\s+Permission\\s*\\{([^;}]*)").matcher(text);
        assertTrue(body.find(), "could not find the Permission enum body in " + source);
        List<String> kernel = new ArrayList<>();
        Matcher constant = Pattern.compile("\\b([A-Z][A-Z0-9_]*)\\b").matcher(
                body.group(1).replaceAll("//[^\\n]*|/\\*(?s:.*?)\\*/", ""));
        while (constant.find()) {
            kernel.add(constant.group(1));
        }
        assertEquals(kernel, RoleAst.PLATFORM_PERMISSIONS,
                "RoleAst.PLATFORM_PERMISSIONS drifted from the kernel's Permission enum -- update the dsl copy");
    }

    /** Identify the root by its CONTENTS, never its directory name (REG-144). */
    private static Path workspaceRoot() {
        Path candidate = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve("NPDevContract"))
                    && Files.isDirectory(candidate.resolve("NPDevGenerator"))
                    && Files.isDirectory(candidate.resolve("NPDevKernel"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Unable to resolve workspace root");
    }
}
