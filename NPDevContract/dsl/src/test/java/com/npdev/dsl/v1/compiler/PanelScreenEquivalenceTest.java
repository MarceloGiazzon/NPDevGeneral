package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.parser.DeprecationException;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
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
 * D-1 phase 3: a hand-written panel is a {@code screens[]} entry of kind "custom" with a {@code panel} block -- what the
 * root {@code panels[]} key used to declare. The old key is refused with the replacement named, a pack's panel screen is
 * pack-qualified like the panel it replaced, and a panel screen is not something an experience can navigate to.
 */
class PanelScreenEquivalenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONCEPTS = """
        "concepts": [
          { "name": "Ticket", "fields": [
            { "name": "id", "type": "uuid", "id": true, "required": true },
            { "name": "subject", "type": "string" } ] } ]""";

    private static final String PANEL_BODY = """
        "route": "/ticket-board", "title": "Tickets",
        "dataSources": [ { "name": "rows", "concept": "Ticket" } ],
        "layout": { "type": "table" }""";

    private static CompiledModel compile(String json) throws Exception {
        var ast = new JsonModelParser().parse(MAPPER.readTree(json));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "got: " + errors);
        return new ModelCompiler().compile(ast);
    }

    private static List<String> validate(String json) throws Exception {
        return new SemanticValidator().validate(new JsonModelParser().parse(MAPPER.readTree(json)));
    }

    private static String model(String screens) {
        return """
            { "dslVersion": "3.0.0", "namespace": "d1.panel", "version": "1.0", %s, "screens": [ %s ] }
            """.formatted(CONCEPTS, screens);
    }

    @Test
    void aCustomScreenWithAPanelBlockCompilesToThatPanel() throws Exception {
        CompiledModel model = compile(model("{ \"name\": \"TicketBoard\", \"kind\": \"custom\", \"panel\": { " + PANEL_BODY + " } }"));
        List<CompiledPanel> panels = model.getPanels();
        assertEquals(List.of("TicketBoard"), panels.stream().map(CompiledPanel::name).toList());
        assertEquals("/ticket-board", panels.get(0).route());
        assertEquals("Tickets", panels.get(0).title());
        assertEquals("Ticket", panels.get(0).dataSources().get(0).concept());
    }

    @Test
    void thePanelFallsBackToTheScreensTitle() throws Exception {
        CompiledModel model = compile(model("{ \"name\": \"TicketBoard\", \"kind\": \"custom\", \"title\": \"The board\", "
                + "\"panel\": { \"route\": \"/ticket-board\", \"dataSources\": [ { \"name\": \"rows\", \"concept\": \"Ticket\" } ] } }"));
        assertEquals("The board", model.getPanels().get(0).title());
    }

    @Test
    void aPanelNeedsNoComponentAndIsNotAClientScreen() throws Exception {
        List<String> errors = validate(model("{ \"name\": \"TicketBoard\", \"kind\": \"custom\", \"component\": \"x\", "
                + "\"panel\": { " + PANEL_BODY + " } }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("cannot also have a 'component'") && e.contains("suggestedFix")), errors.toString());
    }

    @Test
    void aPanelIsOnlyAllowedOnKindCustomAndNeverRepeatsTheName() throws Exception {
        List<String> wrongKind = validate(model("{ \"name\": \"TicketBoard\", \"kind\": \"list\", \"concept\": \"Ticket\", "
                + "\"panel\": { " + PANEL_BODY + " } }"));
        assertTrue(wrongKind.stream().anyMatch(e -> e.contains("'panel' is only allowed on kind \"custom\"")), wrongKind.toString());

        // the schema owns this one: the panel block has no `name` property, so a repeated name never reaches validation.
        var repeated = assertThrows(com.npdev.dsl.v1.validation.ModelSchemaValidationException.class, () -> validate(
                model("{ \"name\": \"TicketBoard\", \"kind\": \"custom\", \"panel\": { \"name\": \"Other\", " + PANEL_BODY + " } }")));
        assertTrue(String.valueOf(repeated.getMessage()).contains("name"), repeated.getMessage());
    }

    @Test
    void aCustomScreenWithNeitherComponentNorPanelIsStillRefused() throws Exception {
        List<String> errors = validate(model("{ \"name\": \"Blank\", \"kind\": \"custom\" }"));
        assertTrue(errors.stream().anyMatch(e -> e.contains("a custom screen needs a 'component'")), errors.toString());
    }

    @Test
    void anExperienceCannotNavigateToAPanelScreen() throws Exception {
        String json = """
            { "dslVersion": "3.0.0", "namespace": "d1.nav", "version": "1.0", %s,
              "roles": [ { "name": "agent" } ],
              "experiences": [ { "name": "desk", "for": ["agent"], "home": "TicketBoard",
                                 "navigation": [ { "screen": "TicketBoard", "label": "Board" } ] } ],
              "screens": [ { "name": "TicketBoard", "kind": "custom", "panel": { %s } } ] }
            """.formatted(CONCEPTS, PANEL_BODY);
        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains("carries a 'panel' block") && e.contains("cannot be")), errors.toString());
    }

    @Test
    void aPacksPanelScreenIsPackQualifiedLikeThePanelItReplaced(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("packs/helpdesk"));
        Files.writeString(dir.resolve("packs/helpdesk/pack.json"), """
            { "dslVersion": "3.0.0", "pack": "helpdesk", "version": "1.0.0", %s,
              "screens": [ { "name": "TicketBoard", "kind": "custom", "panel": { %s } } ] }
            """.formatted(CONCEPTS, PANEL_BODY));
        Path app = dir.resolve("model.json");
        Files.writeString(app, """
            { "dslVersion": "3.0.0", "namespace": "d1.app", "version": "1.0",
              "packs": [ { "$ref": "packs/helpdesk/pack.json", "as": "helpdesk" } ] }
            """);
        var ast = new JsonModelParser().parse(new ModelSourceResolver().resolve(app));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "got: " + errors);
        CompiledModel model = new ModelCompiler().compile(ast);
        assertEquals(List.of("helpdesk::TicketBoard"), model.getPanels().stream().map(CompiledPanel::name).toList());
        assertEquals("helpdesk::Ticket", model.getPanels().get(0).dataSources().get(0).concept());
    }

    @Test
    void theRootPanelsKeyIsRefusedNamingTheReplacement() throws Exception {
        JsonNode old = MAPPER.readTree("""
            { "dslVersion": "3.0.0", "namespace": "d1.old", "version": "1.0", %s,
              "panels": [ { "name": "TicketBoard", "route": "/ticket-board" } ] }
            """.formatted(CONCEPTS));
        IOException refusal = assertThrows(IOException.class, () -> new JsonModelParser().parse(old));
        assertTrue(refusal.getMessage().contains("'panels' was removed")
                && refusal.getMessage().contains("screens[]") && refusal.getMessage().contains("panel"), refusal.getMessage());
        assertTrue(refusal instanceof DeprecationException
                && ((DeprecationException) refusal).getDiagnostic().getSuggestedFix().contains("\"custom\""),
                "the suggested fix spells out the replacement");
    }
}
