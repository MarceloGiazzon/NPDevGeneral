package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelWidget;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.ModelSchemaValidationException;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6-2 operator console / F6-3 dashboard: {@code layout.type} console|dashboard with
 * {@code layout.widgets[]} (tile, chart, actions, history, table) over the panel's own data sources.
 * Must survive parse -> compile -> canonical JSON -> reader unchanged, leave no trace in a model
 * that declares none, and refuse the mistakes that would otherwise draw a silently empty widget.
 */
class PanelLayoutWidgetsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model(String layoutType, String widgetsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "f6.widgets", "version": "1.0",
              "concepts": [
                { "name": "Ticket", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "title", "type": "string" },
                  { "name": "status", "type": "string" },
                  { "name": "priority", "type": "integer" } ] }
              ],
              "panels": [
                { "name": "Ops", "route": "/ops", "title": "Ops",
                  "dataSources": [ { "name": "tickets", "concept": "Ticket" } ],
                  "layout": { "type": "%s"%s } }
              ]
            }
            """.formatted(layoutType, widgetsJson.isEmpty() ? "" : ", \"widgets\": " + widgetsJson);
    }

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> errorsFor(String layoutType, String widgetsJson) throws Exception {
        return new SemanticValidator().validateWithWarnings(parse(model(layoutType, widgetsJson))).getErrors();
    }

    private static boolean anyError(List<String> errors, String fragment) {
        return errors.stream().anyMatch(e -> e.contains(fragment) && e.contains("suggestedFix"));
    }

    private static final String ALL_KINDS = """
        [ { "kind": "tile", "title": "Open", "dataSource": "tickets", "filter": { "field": "status", "equals": "OPEN" }, "span": 2 },
          { "kind": "tile", "title": "Avg priority", "dataSource": "tickets", "aggregate": "avg", "field": "priority" },
          { "kind": "chart", "title": "By status", "dataSource": "tickets", "chart": "donut", "groupBy": "status" },
          { "kind": "chart", "dataSource": "tickets", "chart": "bar", "groupBy": "status", "aggregate": "sum", "field": "priority" },
          { "kind": "actions" }, { "kind": "history" },
          { "kind": "table", "title": "Rows", "dataSource": "tickets" } ]""";

    @Test
    void widgetsSurviveCompileAndCanonicalRoundTrip() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model("dashboard", ALL_KINDS)));
        CompiledPanel panel = compiled.getPanels().stream().filter(p -> "Ops".equals(p.name())).findFirst().orElseThrow();
        assertEquals("dashboard", panel.layout().type());
        assertEquals(7, panel.layout().widgets().size());
        CompiledPanelWidget first = panel.layout().widgets().get(0);
        assertEquals(new CompiledPanelWidget("tile", "Open", "tickets", null, null, null, null, "status", "OPEN", 2), first);

        CompiledModel reread = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        CompiledPanel rereadPanel = reread.getPanels().stream().filter(p -> "Ops".equals(p.name())).findFirst().orElseThrow();
        assertEquals(panel.layout().widgets(), rereadPanel.layout().widgets(), "widgets must round-trip through canonical JSON");
    }

    @Test
    void aValidConsoleAndDashboardHaveNoErrors() throws Exception {
        assertEquals(List.of(), errorsFor("console", ALL_KINDS));
        assertEquals(List.of(), errorsFor("dashboard", ALL_KINDS));
    }

    @Test
    void aModelWithoutWidgetsKeepsItsCanonicalBytes() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model("table", "")));
        assertFalse(CompiledModelCanonicalJson.toJson(compiled).contains("\"widgets\""));
    }

    @Test
    void widgetsNeedAConsoleOrDashboardLayoutAndViceVersa() throws Exception {
        assertTrue(anyError(errorsFor("table", "[ { \"kind\": \"actions\" } ]"), "widgets need layout.type 'console' or 'dashboard'"));
        assertTrue(anyError(errorsFor("console", ""), "needs at least one widget"));
    }

    @Test
    void dataSourceMustExistAndBeNamed() throws Exception {
        assertTrue(anyError(errorsFor("dashboard", "[ { \"kind\": \"tile\", \"dataSource\": \"nope\" } ]"), "is not declared on this panel"));
        assertTrue(anyError(errorsFor("dashboard", "[ { \"kind\": \"tile\" } ]"), "dataSource is required"));
    }

    @Test
    void nonCountAggregateNeedsAFieldThatExists() throws Exception {
        assertTrue(anyError(errorsFor("dashboard", "[ { \"kind\": \"tile\", \"dataSource\": \"tickets\", \"aggregate\": \"sum\" } ]"), "needs a field"));
        assertTrue(anyError(errorsFor("dashboard",
                "[ { \"kind\": \"tile\", \"dataSource\": \"tickets\", \"aggregate\": \"sum\", \"field\": \"missing\" } ]"),
                "field 'missing' is not a field of Ticket"));
        assertTrue(anyError(errorsFor("dashboard",
                "[ { \"kind\": \"tile\", \"dataSource\": \"tickets\", \"filter\": { \"field\": \"ghost\", \"equals\": \"x\" } } ]"),
                "filter.field 'ghost' is not a field of Ticket"));
    }

    @Test
    void chartNeedsAKindAndACategoryField() throws Exception {
        assertTrue(anyError(errorsFor("dashboard", "[ { \"kind\": \"chart\", \"dataSource\": \"tickets\", \"groupBy\": \"status\" } ]"), "chart must be one of"));
        assertTrue(anyError(errorsFor("dashboard", "[ { \"kind\": \"chart\", \"dataSource\": \"tickets\", \"chart\": \"bar\" } ]"), "groupBy is required"));
        assertTrue(anyError(errorsFor("dashboard",
                "[ { \"kind\": \"chart\", \"dataSource\": \"tickets\", \"chart\": \"bar\", \"groupBy\": \"ghost\" } ]"),
                "groupBy 'ghost' is not a field of Ticket"));
    }

    @Test
    void atMostOneActionsAndOneHistoryWidget() throws Exception {
        assertTrue(anyError(errorsFor("console", "[ { \"kind\": \"actions\" }, { \"kind\": \"actions\" } ]"), "at most one 'actions' widget"));
        assertTrue(anyError(errorsFor("console", "[ { \"kind\": \"history\" }, { \"kind\": \"history\" } ]"), "at most one 'history' widget"));
    }

    @Test
    void unknownKindChartOrAggregateIsRejectedBySchema() {
        assertThrows(ModelSchemaValidationException.class, () -> errorsFor("console", "[ { \"kind\": \"gauge\" } ]"));
        assertThrows(ModelSchemaValidationException.class,
                () -> errorsFor("dashboard", "[ { \"kind\": \"chart\", \"dataSource\": \"tickets\", \"chart\": \"pie\", \"groupBy\": \"status\" } ]"));
        assertThrows(ModelSchemaValidationException.class,
                () -> errorsFor("dashboard", "[ { \"kind\": \"tile\", \"dataSource\": \"tickets\", \"aggregate\": \"median\", \"field\": \"priority\" } ]"));
    }
}
