package com.npdev.dsl.v1.compiled;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2.1's own stated most-important test (docs/FRONTEND_STRATEGY_PLAN.md, docs/NEXT_EXECUTION_PLAN.md
 * P4.2): every {@code invocations[].path} (and every {@code pathAliases[]} entry) MUST resolve to a
 * REAL controller route -- otherwise the catalog can drift from reality exactly the way a hand-authored
 * screen silently can, defeating the whole point of shipping it.
 *
 * <p>{@link RealRoutePatterns#ALL} is not a fixture of resolved instances (e.g. one literal string per
 * concept/flow) -- it is the small, STABLE set of real controller route PATTERNS this catalog's
 * generator methods are built from (one or two per controller, {@code {*}} standing in for a Spring
 * path variable of any name). This generalizes correctly to any future model (more concepts, more
 * flows, more panels) without needing regeneration, unlike a literal per-entity fixture would.
 *
 * <p>The list itself lives in {@link RealRoutePatterns}, shared with the generator module's
 * {@code RoutePatternStalenessTest} (docs/REMEDIATION_PLAN.md R-R1) so it is never hand-maintained in
 * two places: this test proves every CATALOG entry matches a real pattern; that one proves every
 * PATTERN still matches a real, freshly generated controller route (catching one that has gone
 * stale, which this test alone cannot -- it only ever checks the catalog against the patterns, never
 * the patterns against reality).
 */
class InvocationCatalogRouteConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyInvocationPathMatchesARealControllerRoute() throws Exception {
        Path modelPath = resolvePath(List.of(
                Path.of("..", "..", "NPDevSamples", "medium-expense-approval", "Input", "model.json"),
                Path.of("..", "..", "..", "NPDevSamples", "medium-expense-approval", "Input", "model.json")
        ));

        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiledModel = new ModelCompiler().compile(ast);
        String json = CompiledMetadataCanonicalJson.toJson(modelPath, compiledModel);
        JsonNode invocations = MAPPER.readTree(json).path("catalogs").path("invocations");

        assertTrue(invocations.isArray() && invocations.size() > 0, "Expected a non-empty invocations catalog.");

        int checked = 0;
        for (JsonNode entry : invocations) {
            String method = entry.path("method").asText();
            String id = entry.path("id").asText();
            String mainPath = entry.path("path").asText();
            assertTrue(RealRoutePatterns.isRealRoute(method, mainPath),
                    () -> "invocations[] entry '" + id + "' has no matching real route: " + method + " " + mainPath);
            checked++;
            for (JsonNode alias : entry.path("pathAliases")) {
                assertTrue(RealRoutePatterns.isRealRoute(method, alias.asText()),
                        () -> "invocations[] entry '" + id + "' pathAlias has no matching real route: " + method + " " + alias.asText());
                checked++;
            }
        }
        assertTrue(checked >= invocations.size(), "Sanity: must have checked at least one path per entry.");
    }

    @Test
    void submitExpenseIsFlowBackedAndCreateDirectSaysSo() throws Exception {
        Path modelPath = resolvePath(List.of(
                Path.of("..", "..", "NPDevSamples", "medium-expense-approval", "Input", "model.json"),
                Path.of("..", "..", "..", "NPDevSamples", "medium-expense-approval", "Input", "model.json")
        ));

        ModelAst ast = new JsonModelParser().parse(modelPath);
        CompiledModel compiledModel = new ModelCompiler().compile(ast);
        String json = CompiledMetadataCanonicalJson.toJson(modelPath, compiledModel);
        JsonNode invocations = MAPPER.readTree(json).path("catalogs").path("invocations");

        JsonNode flowEntry = findById(invocations, "flow:SubmitExpense");
        assertTrue(flowEntry != null, "Expected a flow:SubmitExpense invocation entry.");
        assertEquals("POST", flowEntry.path("method").asText());
        assertEquals("/api/v1/flows/SubmitExpense/execute", flowEntry.path("path").asText());
        assertTrue(flowEntry.path("preferred").asBoolean());

        JsonNode createDirect = findById(invocations, "createDirect:ExpenseRequest");
        assertTrue(createDirect != null, "Expected a createDirect:ExpenseRequest invocation entry.");
        assertFalse(createDirect.path("preferred").asBoolean(),
                "ExpenseRequest.create is flow-backed (SubmitExpense) -- the direct route must say non-preferred.");
        assertEquals("flow:SubmitExpense", createDirect.path("prefer").asText());

        JsonNode panelAction = findById(invocations, "panelAction:ExpenseApprovalPanel:SubmitExpense");
        assertTrue(panelAction != null, "Expected the panel's procedure-bound action to be cataloged.");
        assertEquals("procedure", panelAction.path("binding").asText());
        assertEquals("POST", panelAction.path("method").asText());
        assertEquals("/api/v1/runtime/metadata/ui/panels/ExpenseApprovalPanel/actions/SubmitExpense",
                panelAction.path("path").asText());
    }

    private static JsonNode findById(JsonNode invocations, String id) {
        for (JsonNode entry : invocations) {
            if (id.equals(entry.path("id").asText())) {
                return entry;
            }
        }
        return null;
    }

    private static Path resolvePath(List<Path> candidates) {
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not resolve medium-expense-approval model.json from any candidate: " + candidates);
    }
}
