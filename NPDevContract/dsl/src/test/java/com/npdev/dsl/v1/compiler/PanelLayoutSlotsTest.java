package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelSlot;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.ModelSchemaValidationException;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F6-1 layoutSlot (ADR-0004 L5): {@code panels[].layout.slots[]} declares hand-authored regions inside a
 * generated panel. It must survive parse -> compile -> canonical JSON -> reader unchanged, a model
 * without slots must keep canonical bytes with no {@code slots} key at all, and bad slots are errors.
 */
class PanelLayoutSlotsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String model(String slotsJson) {
        return """
            {
              "dslVersion": "1.0.0", "namespace": "f6.layoutslot", "version": "1.0",
              "concepts": [
                { "name": "Ticket", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "title", "type": "string" } ] }
              ],
              "panels": [
                { "name": "TicketBoard", "route": "/ticket-board", "title": "Tickets",
                  "dataSources": [ { "name": "tickets", "concept": "Ticket" } ],
                  "layout": { "type": "table"%s } }
              ]
            }
            """.formatted(slotsJson.isEmpty() ? "" : ", \"slots\": " + slotsJson);
    }

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> errorsFor(String slotsJson) throws Exception {
        return new SemanticValidator().validateWithWarnings(parse(model(slotsJson))).getErrors();
    }

    @Test
    void slotsSurviveCompileAndCanonicalRoundTrip() throws Exception {
        String slots = """
            [ { "placement": "above", "component": "ticket-kpis", "label": "KPIs" },
              { "placement": "below", "component": "ticket-timeline" } ]""";
        CompiledModel compiled = new ModelCompiler().compile(parse(model(slots)));
        CompiledPanel panel = compiled.getPanels().stream()
                .filter(p -> "TicketBoard".equals(p.name())).findFirst().orElseThrow();
        assertEquals(List.of(
                new CompiledPanelSlot("above", "ticket-kpis", "KPIs"),
                new CompiledPanelSlot("below", "ticket-timeline", null)), panel.layout().slots());

        CompiledModel reread = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        CompiledPanel rereadPanel = reread.getPanels().stream()
                .filter(p -> "TicketBoard".equals(p.name())).findFirst().orElseThrow();
        assertEquals(panel.layout().slots(), rereadPanel.layout().slots(), "slots must round-trip through canonical JSON");
    }

    @Test
    void aModelWithoutSlotsKeepsItsCanonicalBytes() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(parse(model("")));
        String json = CompiledModelCanonicalJson.toJson(compiled);
        assertFalse(json.contains("\"slots\""), "an undeclared slots list must not appear in canonical JSON");
        CompiledPanel panel = compiled.getPanels().get(0);
        assertTrue(panel.layout().slots().isEmpty());
        assertNull(panel.layout().metadata().get("slots"));
    }

    @Test
    void aValidSlotModelHasNoErrors() throws Exception {
        assertEquals(List.of(), errorsFor("""
            [ { "placement": "replace", "component": "ticket_kanban" } ]"""));
    }

    @Test
    void unknownPlacementIsRejectedBySchema() {
        ModelSchemaValidationException ex = assertThrows(ModelSchemaValidationException.class,
                () -> errorsFor("[ { \"placement\": \"left\", \"component\": \"x\" } ]"));
        assertTrue(ex.getMessage().contains("slots[0].placement"), "got: " + ex.getMessage());
    }

    @Test
    void invalidComponentNameIsRejectedBySchema() {
        ModelSchemaValidationException ex = assertThrows(ModelSchemaValidationException.class,
                () -> errorsFor("[ { \"placement\": \"above\", \"component\": \"../evil\" } ]"));
        assertTrue(ex.getMessage().contains("slots[0].component"), "got: " + ex.getMessage());
    }

    @Test
    void sameComponentTwiceAndTwoReplaceSlotsAreErrors() throws Exception {
        List<String> twice = errorsFor("[ { \"placement\": \"above\", \"component\": \"a\" }, { \"placement\": \"below\", \"component\": \"a\" } ]");
        assertTrue(twice.stream().anyMatch(e -> e.contains("mounted twice")), "got: " + twice);
        List<String> replaces = errorsFor("[ { \"placement\": \"replace\", \"component\": \"a\" }, { \"placement\": \"replace\", \"component\": \"b\" } ]");
        assertTrue(replaces.stream().anyMatch(e -> e.contains("only one slot may use placement 'replace'")), "got: " + replaces);
    }
}
