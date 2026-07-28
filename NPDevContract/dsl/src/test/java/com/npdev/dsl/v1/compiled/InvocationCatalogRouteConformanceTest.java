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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F2.1's own stated most-important test (docs/FRONTEND_STRATEGY_PLAN.md, docs/NEXT_EXECUTION_PLAN.md
 * P4.2): every {@code invocations[].path} (and every {@code pathAliases[]} entry) MUST resolve to a
 * REAL controller route -- otherwise the catalog can drift from reality exactly the way a hand-authored
 * screen silently can, defeating the whole point of shipping it.
 *
 * <p>{@link #REAL_ROUTE_PATTERNS} is not a fixture of resolved instances (e.g. one literal string per
 * concept/flow) -- it is the small, STABLE set of real controller route PATTERNS this catalog's
 * generator methods are built from (one or two per controller, {@code {*}} standing in for a Spring
 * path variable of any name). This generalizes correctly to any future model (more concepts, more
 * flows, more panels) without needing regeneration, unlike a literal per-entity fixture would.
 *
 * <p>Each pattern was independently verified against the REAL generated source of a real app
 * (WmsOffice, 32 concepts / 15 flows / panels / 2 aggregates, regenerated 2026-07-28) -- not assumed
 * from a template or the model's shape, which is exactly the mistake this catalog exists to prevent
 * (see this class's own javadoc history: an earlier design sketch got several of these paths wrong,
 * e.g. assuming {@code /api/concepts/&lt;ConceptName&gt;} when the real generic-CRUD path is
 * {@code /api/concepts/&lt;tableName&gt;}). A companion cross-check against WmsOffice's actual
 * compiled-metadata.json (343 paths across 252 real invocation entries) found zero mismatches once
 * this list was correct.
 */
class InvocationCatalogRouteConformanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** One compiled regex per real controller route, {@code {*}} -&gt; {@code [^/]+} (exactly one
     * path segment, matching Spring's own {@code {name}} path-variable semantics). Source comments
     * cite the controller + line verified against a real generated app. */
    private static final List<Pattern> REAL_ROUTE_PATTERNS = List.of(
            // GeneratedConceptCrudController (generated tree) -- path variable is the TABLE name.
            route("GET", "/api/concepts/{*}"),
            route("POST", "/api/concepts/{*}"),
            route("GET", "/api/concepts/{*}/{*}"),
            route("PUT", "/api/concepts/{*}/{*}"),
            route("DELETE", "/api/concepts/{*}/{*}"),
            // ConceptQueryController (template tree, @RequestMapping({"/api/v1/concepts","/api/concepts"}))
            // -- path variable is the concept NAME, a different vocabulary at the same nominal depth.
            route("GET", "/api/v1/concepts/{*}/page"),
            route("GET", "/api/concepts/{*}/page"),
            route("GET", "/api/v1/concepts/{*}/export.csv"),
            route("GET", "/api/concepts/{*}/export.csv"),
            // npdev-runtime-flow-controller.mustache (generated tree, class @RequestMapping("/api")).
            route("POST", "/api/v1/flows/{*}/execute"),
            route("POST", "/api/flows/{*}/execute"),
            // DirectExecutionGatewayController (template tree) -- flow-bound panel actions only.
            route("POST", "/api/v1/execute/panel-action"),
            route("POST", "/api/execute/panel-action"),
            // RuntimeUiMetadataController (template tree, @RequestMapping({"/api/v1/runtime/metadata/ui","/api/runtime/metadata/ui"})).
            route("POST", "/api/v1/runtime/metadata/ui/panels/{*}/actions/{*}"),
            route("POST", "/api/runtime/metadata/ui/panels/{*}/actions/{*}"),
            route("POST", "/api/v1/runtime/metadata/ui/panels/{*}/dataSources/{*}/rows"),
            route("POST", "/api/runtime/metadata/ui/panels/{*}/dataSources/{*}/rows"),
            route("DELETE", "/api/v1/runtime/metadata/ui/panels/{*}/dataSources/{*}/rows/{*}"),
            route("DELETE", "/api/runtime/metadata/ui/panels/{*}/dataSources/{*}/rows/{*}"),
            // AggregateApiController (template tree, @RequestMapping({"/api/v1/runtime/aggregate","/api/runtime/aggregate"})).
            route("GET", "/api/v1/runtime/aggregate/{*}/{*}"),
            route("GET", "/api/runtime/aggregate/{*}/{*}"),
            route("POST", "/api/v1/runtime/aggregate/{*}"),
            route("POST", "/api/runtime/aggregate/{*}"),
            route("POST", "/api/v1/runtime/aggregate/{*}/invoke/{*}"),
            route("POST", "/api/runtime/aggregate/{*}/invoke/{*}"),
            // FileUploadController (template tree, @RequestMapping("/api/files")) -- concept resolved
            // by NAME (requireFileField, case-insensitive), a third vocabulary at this same depth.
            route("POST", "/api/files/{*}/{*}")
    );

    private static Pattern route(String method, String pathPattern) {
        String[] segments = pathPattern.split("/");
        StringBuilder regex = new StringBuilder("^").append(Pattern.quote(method)).append(" ");
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                regex.append("/");
            }
            regex.append("{*}".equals(segments[i]) ? "[^/]+" : Pattern.quote(segments[i]));
        }
        return Pattern.compile(regex.append("$").toString());
    }

    private static boolean isRealRoute(String method, String path) {
        String candidate = method + " " + path;
        for (Pattern pattern : REAL_ROUTE_PATTERNS) {
            if (pattern.matcher(candidate).matches()) {
                return true;
            }
        }
        return false;
    }

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
            assertTrue(isRealRoute(method, mainPath),
                    () -> "invocations[] entry '" + id + "' has no matching real route: " + method + " " + mainPath);
            checked++;
            for (JsonNode alias : entry.path("pathAliases")) {
                assertTrue(isRealRoute(method, alias.asText()),
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
