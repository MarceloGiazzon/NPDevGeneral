package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledAutoPanel;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PACK-1: the built-in approvals pack ({@code NPDevContract/packs/approvals/pack.json}). Loads the real
 * artifact and consumes it the way an app author would: a local {@code $ref} under an alias plus the
 * app's {@code provides.roleBindings} for the pack's two logical roles (PACK-9).
 */
class ApprovalsPackResolutionTest {

    @TempDir
    Path temp;

    private static final String MODEL_WITH_BINDINGS = """
            {
              "namespace": "approvals.pack.test",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "packs": [ { "$ref": "packs/approvals/pack.json", "as": "approvals" } ],
              "provides": { "roleBindings": { "approver": "MANAGER", "requester": "STAFF" } },
              "concepts": [
                { "name": "Expense", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ]
            }
            """;

    private ModelAst parse(String modelJson) throws Exception {
        Path realPack = Path.of("..", "packs", "approvals", "pack.json").toAbsolutePath().normalize();
        assertTrue(Files.exists(realPack), "Built-in approvals pack must exist at " + realPack);
        write("packs/approvals/pack.json", Files.readString(realPack));
        Path model = write("model.json", modelJson);
        ResolvedModelSource resolvedSource = new ModelSourceResolver().resolve(model);
        return new JsonModelParser().parse(resolvedSource);
    }

    @Test
    void builtInApprovalsPackCompilesWithItsRolesBoundToTheAppsOwn() throws Exception {
        ModelAst ast = parse(MODEL_WITH_BINDINGS);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected the approvals pack model to validate, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledConcept request = compiled.findConcept("approvals::ApprovalRequest").orElseThrow();
        CompiledConcept decision = compiled.findConcept("approvals::ApprovalDecision").orElseThrow();

        // the pack's role('approver') / role('requester') tokens became the app's concrete role names
        String requestRead = request.getAccess().getRead();
        assertTrue(requestRead.contains("'MANAGER'") && !requestRead.contains("role("), requestRead);
        assertTrue(decision.getAccess().getWrite().contains("'MANAGER'"), decision.getAccess().getWrite());

        CompiledFlow flow = compiled.getFlows().stream()
                .filter(f -> f.getName().endsWith("RequestApproval")).findFirst().orElseThrow();
        CompiledFlowStep await = flow.getSteps().stream()
                .filter(step -> "await-decision".equals(step.getName())).findFirst().orElseThrow();
        assertEquals(List.of("MANAGER"), await.getTask().getAssigneeRoles(),
                "the human task is routed to the role the app bound to 'approver'");
        assertEquals("status", await.getTask().getOutcomeField());
        assertEquals(List.of("APPROVED", "REJECTED"),
                await.getTask().getOutcomes().stream().map(o -> o.value()).toList());

        List<String> queryNames = compiled.getQueries().stream().map(q -> q.name()).toList();
        for (String expected : List.of("PendingApprovals", "DecidedApprovals")) {
            assertTrue(queryNames.stream().anyMatch(n -> n.endsWith(expected)),
                    "Expected query " + expected + " among " + queryNames);
        }
        List<String> routes = compiled.getAutoPanels().stream().map(CompiledAutoPanel::route).toList();
        assertTrue(routes.stream().anyMatch(r -> r.startsWith("/approvals")), "auto-panel routes: " + routes);
    }

    @Test
    void importingWithoutBindingTheRolesIsRefusedNamingTheLogicalRole() throws Exception {
        IOException refusal = assertThrows(IOException.class, () -> parse("""
                {
                  "namespace": "approvals.pack.test",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/approvals/pack.json", "as": "approvals" } ],
                  "concepts": [
                    { "name": "Expense", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """));
        assertTrue(refusal.getMessage().contains("approvals") && refusal.getMessage().contains("role('"),
                "the refusal must name the pack and the unbound logical role: " + refusal.getMessage());
        assertTrue(refusal.getMessage().contains("provides.roleBindings"), refusal.getMessage());
    }

    @Test
    void theCorpusWitnessCopyMatchesTheBuiltInPack() throws Exception {
        // dsl-conformance-max cannot $ref outside its own root, so it carries a copy; it must not drift.
        Path builtIn = Path.of("..", "packs", "approvals", "pack.json").toAbsolutePath().normalize();
        Path witness = Path.of("..", "..", "NPDevSamples", "dsl-conformance-max", "Input", "packs", "approvals", "pack.json")
                .toAbsolutePath().normalize();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode expected = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(builtIn.toFile());
        com.fasterxml.jackson.databind.node.ObjectNode actual = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(witness.toFile());
        expected.remove("$schema");   // the relative schema path differs by location
        actual.remove("$schema");
        assertEquals(expected, actual, "NPDevSamples/dsl-conformance-max/Input/packs/approvals/pack.json drifted from the built-in pack");
    }

    private Path write(String relative, String content) throws Exception {
        Path path = temp.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }
}
