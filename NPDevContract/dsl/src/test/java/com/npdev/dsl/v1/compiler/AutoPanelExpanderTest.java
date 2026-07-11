package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Verifies a concept-bound AutoPanel expands into wired Selection/Detail/Transaction panels (P1 slice 2). */
class AutoPanelExpanderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CompiledModel compile(String json) throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        return new ModelCompiler().compile(ast);
    }

    private static CompiledPanel panel(CompiledModel model, String name) {
        Optional<CompiledPanel> found = model.getPanels().stream()
                .filter(p -> name.equals(p.name())).findFirst();
        assertTrue(found.isPresent(), "expected synthesized panel " + name
                + " among " + model.getPanels().stream().map(CompiledPanel::name).toList());
        return found.get();
    }

    @Test
    void slimConceptAutoPanelExpandsToThreeSurfaces() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap", "version": "1.0",
              "concepts": [
                { "name": "Cliente", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "nome", "type": "string" },
                  { "name": "email", "type": "string" } ] }
              ],
              "autoPanels": [ { "concept": "Cliente" } ]
            }
            """;
        CompiledModel model = compile(json);

        // AutoPanel is retained as declarative intent...
        assertEquals(1, model.getAutoPanels().size());
        // ...and expanded into three real panels.
        CompiledPanel selection = panel(model, "ClienteSelection");
        CompiledPanel detail = panel(model, "ClienteDetail");
        CompiledPanel form = panel(model, "ClienteForm");

        // Selection: a table over the concept, all fields as columns, a "new" action.
        assertEquals("table", selection.layout().type());
        assertEquals("/cliente", selection.route());
        // Columns follow the concept's normalized (alphabetical) field order.
        assertEquals(List.of("email", "id", "nome"), selection.layout().fields());
        assertEquals("Cliente", selection.dataSources().get(0).concept());
        assertEquals("autoPanel", selection.metadata().get("generatedBy"));
        assertTrue(selection.actions().stream().anyMatch(a -> "new".equals(a.name())));

        // Detail: a read-only detail view.
        assertEquals("detail", detail.layout().type());
        assertEquals("/cliente/{id}", detail.route());
        assertFalse(detail.fieldBindings().isEmpty());
        assertTrue(detail.fieldBindings().stream().noneMatch(b -> b.editable()));

        // Transaction: an editable form; the id field is dropped from the editable set.
        assertEquals("form", form.layout().type());
        assertEquals("/cliente/edit", form.route());
        assertEquals(List.of("email", "nome"), form.layout().fields());
        assertTrue(form.fieldBindings().stream().allMatch(b -> b.editable()));
        assertTrue(form.actions().stream().anyMatch(a -> "save".equals(a.name())));
        assertTrue(form.actions().stream().anyMatch(a -> "delete".equals(a.name())));
    }

    @Test
    void surfacesAndOverridesAreHonored() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap", "version": "1.0",
              "concepts": [
                { "name": "Cliente", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "nome", "type": "string" },
                  { "name": "email", "type": "string" } ] }
              ],
              "autoPanels": [
                { "name": "Clientes", "concept": "Cliente", "route": "/clientes",
                  "surfaces": ["selection"],
                  "selection": { "columns": ["nome"] } }
              ]
            }
            """;
        CompiledModel model = compile(json);

        // Only the selection surface is emitted, with the override columns + custom route/name.
        assertTrue(model.getPanels().stream().anyMatch(p -> "ClientesSelection".equals(p.name())));
        assertTrue(model.getPanels().stream().noneMatch(p -> "ClientesDetail".equals(p.name())));
        assertTrue(model.getPanels().stream().noneMatch(p -> "ClientesForm".equals(p.name())));
        CompiledPanel selection = panel(model, "ClientesSelection");
        assertEquals("/clientes", selection.route());
        assertEquals(List.of("nome"), selection.layout().fields());
    }
}
