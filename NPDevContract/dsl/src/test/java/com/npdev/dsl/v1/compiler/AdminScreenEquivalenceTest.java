package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
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
 * D-1: a concept's generated back-office pages are a {@code screens[]} entry's {@code admin} block (kind "list") and an
 * aggregate's workbench is the same block on a kind "workbench" screen -- what the root {@code autoPanels[]} key used to
 * declare. The old key is refused with the replacement named. (Phase 1 proved the new spelling compiled byte-for-byte
 * like the old one over every sample and built-in pack; this keeps the compiled shape pinned now the old spelling is gone.)
 */
class AdminScreenEquivalenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONCEPTS = """
        "concepts": [
          { "name": "Ticket", "fields": [
            { "name": "id", "type": "uuid", "id": true, "required": true },
            { "name": "subject", "type": "string" },
            { "name": "status", "type": "string" } ] },
          { "name": "Order", "fields": [
            { "name": "id", "type": "uuid", "id": true, "required": true } ],
            "composition": { "collections": [
              { "name": "lines", "concept": "OrderLine", "childField": "orderId", "ownership": "owned" } ] } },
          { "name": "OrderLine", "fields": [
            { "name": "id", "type": "uuid", "id": true, "required": true },
            { "name": "orderId", "type": "uuid" } ] } ]""";

    private static final String ADMIN_BODY = """
        "route": "/tickets", "surfaces": ["selection", "detail", "transaction"],
        "selection": { "columns": ["subject", "status"], "filters": ["status"] }""";

    private static CompiledModel compile(String json) throws Exception {
        var ast = new JsonModelParser().parse(MAPPER.readTree(json));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "got: " + errors);
        return new ModelCompiler().compile(ast);
    }

    private static String model(String screens) {
        return """
            { "dslVersion": "3.0.0", "namespace": "d1.admin", "version": "1.0", %s, "screens": [ %s ] }
            """.formatted(CONCEPTS, screens);
    }

    private static List<String> panelNames(CompiledModel model) {
        return model.getPanels().stream().map(CompiledPanel::name).toList();
    }

    @Test
    void aListScreenWithAdminExpandsToTheConceptsGeneratedPages() throws Exception {
        CompiledModel model = compile(model("{ \"name\": \"Tickets\", \"kind\": \"list\", \"concept\": \"Ticket\", "
                + "\"admin\": { " + ADMIN_BODY + " } }"));
        assertEquals(1, model.getAutoPanels().size());
        assertEquals("Tickets", model.getAutoPanels().get(0).name());
        assertEquals("/tickets", model.getAutoPanels().get(0).route());
        assertTrue(panelNames(model).containsAll(List.of("TicketsSelection", "TicketsDetail", "TicketsForm")), panelNames(model).toString());
    }

    @Test
    void aWorkbenchScreenWithAdminExpandsToTheAggregateWorkbench() throws Exception {
        CompiledModel model = compile(model("{ \"name\": \"OrderDesk\", \"kind\": \"workbench\", \"concept\": \"Order\", "
                + "\"admin\": { \"route\": \"/orders\" } }"));
        assertEquals(1, model.getAutoPanels().size());
        assertEquals("OrderDesk", model.getAutoPanels().get(0).name());
        assertEquals("Order", model.getAutoPanels().get(0).aggregate());
        assertTrue(panelNames(model).contains("OrderDeskWorkbench"), panelNames(model).toString());
    }

    @Test
    void aScreenWithoutAdminIsNotAnAutoPanel() throws Exception {
        CompiledModel model = compile(model("{ \"name\": \"Tickets\", \"kind\": \"list\", \"concept\": \"Ticket\", "
                + "\"columns\": [\"subject\"] }"));
        assertEquals(0, model.getAutoPanels().size());
    }

    @Test
    void aPacksAdminScreenIsPackQualifiedLikeTheAutoPanelItReplaced(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("packs/helpdesk"));
        Files.writeString(dir.resolve("packs/helpdesk/pack.json"), """
            { "dslVersion": "3.0.0", "pack": "helpdesk", "version": "1.0.0", %s,
              "screens": [ { "name": "Tickets", "kind": "list", "concept": "Ticket", "admin": { %s } },
                           { "name": "Desk", "kind": "workbench", "concept": "Order", "admin": { "route": "/orders" } } ] }
            """.formatted(CONCEPTS, ADMIN_BODY));
        Path app = dir.resolve("model.json");
        Files.writeString(app, """
            { "dslVersion": "3.0.0", "namespace": "d1.app", "version": "1.0",
              "packs": [ { "$ref": "packs/helpdesk/pack.json", "as": "helpdesk" } ] }
            """);
        var ast = new JsonModelParser().parse(new ModelSourceResolver().resolve(app));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "got: " + errors);
        CompiledModel model = new ModelCompiler().compile(ast);
        var byName = new java.util.TreeMap<String, com.npdev.dsl.v1.compiled.CompiledAutoPanel>();
        model.getAutoPanels().forEach(p -> byName.put(p.name(), p));
        assertEquals(List.of("helpdesk::Desk", "helpdesk::Tickets"), List.copyOf(byName.keySet()));
        assertEquals("helpdesk::Ticket", byName.get("helpdesk::Tickets").concept());
        assertEquals("helpdesk::Order", byName.get("helpdesk::Desk").aggregate());
    }

    @Test
    void theRootAutoPanelsKeyIsRefusedNamingTheReplacement() throws Exception {
        JsonNode old = MAPPER.readTree("""
            { "dslVersion": "3.0.0", "namespace": "d1.old", "version": "1.0", %s,
              "autoPanels": [ { "name": "Tickets", "concept": "Ticket" } ] }
            """.formatted(CONCEPTS));
        IOException refusal = assertThrows(IOException.class, () -> new JsonModelParser().parse(old));
        assertTrue(refusal.getMessage().contains("'autoPanels' was removed")
                && refusal.getMessage().contains("screens[]") && refusal.getMessage().contains("admin"), refusal.getMessage());
        assertTrue(refusal instanceof com.npdev.dsl.v1.parser.DeprecationException
                && ((com.npdev.dsl.v1.parser.DeprecationException) refusal).getDiagnostic().getSuggestedFix().contains("\"kind\""),
                "the suggested fix spells out the replacement");
    }
}
