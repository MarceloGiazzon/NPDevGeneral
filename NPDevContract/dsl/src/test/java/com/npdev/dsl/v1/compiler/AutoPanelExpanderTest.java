package com.npdev.dsl.v1.compiler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
    void selectionDataSourceProcedureReplacesTheGeneratedRowSource() throws Exception {
        // Move 8 D3 (item G6): selection.dataSource.procedure threads through into the generated
        // Selection panel's "rows" data source as the produce-disposition procedure -- the seam
        // PanelRuntime already executes for hand-authored panels, now reachable from AutoPanel too.
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap.d3", "version": "1.0",
              "concepts": [
                { "name": "Cliente", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "nome", "type": "string" } ] }
              ],
              "procedures": [
                { "name": "ListarClientesAtivos", "steps": [
                  { "name": "ret", "type": "return", "value": "$input" } ] }
              ],
              "autoPanels": [ { "concept": "Cliente",
                "selection": { "dataSource": { "procedure": "ListarClientesAtivos" } } } ]
            }
            """;
        CompiledModel model = compile(json);
        CompiledPanel selection = panel(model, "ClienteSelection");

        assertEquals("ListarClientesAtivos", selection.dataSources().get(0).procedure());
        // concept is still carried unconditionally -- same precedent as hand-authored panels, where
        // concept and procedure may coexist.
        assertEquals("Cliente", selection.dataSources().get(0).concept());
    }

    @Test
    void selectionWithNoDataSourceDeclaredStaysConceptBoundUnchanged() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap.d3", "version": "1.0",
              "concepts": [
                { "name": "Cliente", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "autoPanels": [ { "concept": "Cliente" } ]
            }
            """;
        CompiledModel model = compile(json);
        CompiledPanel selection = panel(model, "ClienteSelection");

        assertNull(selection.dataSources().get(0).procedure());
        assertEquals("Cliente", selection.dataSources().get(0).concept());
    }

    @Test
    void promptSurfaceIsEmittedAsAPicker() throws Exception {
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

        // The prompt surface is part of the default set, alongside selection/detail/transaction.
        CompiledPanel prompt = panel(model, "ClientePrompt");
        assertEquals("table", prompt.layout().type());
        assertEquals("/cliente/prompt", prompt.route());
        assertEquals("prompt", prompt.metadata().get("surface"));
        // Default labelField = first non-id field; returnField = the id.
        assertEquals("email", prompt.metadata().get("labelField"));
        assertEquals("id", prompt.metadata().get("returnField"));
        assertEquals(Boolean.FALSE, prompt.metadata().get("multiSelect"));
        assertTrue(prompt.actions().isEmpty(), "a prompt is a read-only picker");
    }

    @Test
    void promptLabelFieldAndColumnsAreOverridable() throws Exception {
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
                { "concept": "Cliente", "surfaces": ["prompt"],
                  "prompt": { "labelField": "nome", "columns": ["nome", "email"] } }
              ]
            }
            """;
        CompiledModel model = compile(json);

        // Only the prompt surface is emitted.
        assertTrue(model.getPanels().stream().noneMatch(p -> "ClienteSelection".equals(p.name())));
        CompiledPanel prompt = panel(model, "ClientePrompt");
        assertEquals("nome", prompt.metadata().get("labelField"));
        assertEquals(List.of("nome", "email"), prompt.layout().fields());
    }

    @Test
    @SuppressWarnings("unchecked")
    void formForeignKeyFieldsAutoWireToTargetPrompt() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap", "version": "1.0",
              "concepts": [
                { "name": "Provider", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "fullName", "type": "string" } ] },
                { "name": "Appointment", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "providerId", "type": "reference", "reference": { "target": "Provider", "displayField": "fullName" } },
                  { "name": "notes", "type": "string" } ] }
              ],
              "autoPanels": [ { "concept": "Provider" }, { "concept": "Appointment" } ]
            }
            """;
        CompiledModel model = compile(json);

        CompiledPanel form = panel(model, "AppointmentForm");
        List<Map<String, Object>> fkFields = (List<Map<String, Object>>) form.metadata().get("fkFields");
        assertNotNull(fkFields, "the form should declare fkFields for its reference field");
        assertEquals(1, fkFields.size());

        Map<String, Object> fk = fkFields.get(0);
        assertEquals("providerId", fk.get("field"));
        assertEquals("Provider", fk.get("targetConcept"));
        assertEquals("ProviderPrompt", fk.get("prompt"));
        assertEquals("id", fk.get("returnField"));
        assertEquals("fullName", fk.get("labelField"));
    }

    @Test
    void noFkWiringWhenTargetHasNoPrompt() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap", "version": "1.0",
              "concepts": [
                { "name": "Provider", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "fullName", "type": "string" } ] },
                { "name": "Appointment", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "providerId", "type": "reference", "reference": { "target": "Provider" } } ] }
              ],
              "autoPanels": [
                { "concept": "Provider", "surfaces": ["selection"] },
                { "concept": "Appointment" }
              ]
            }
            """;
        CompiledModel model = compile(json);
        // Provider has no prompt surface (surfaces=[selection]), so Appointment's FK cannot wire.
        CompiledPanel form = panel(model, "AppointmentForm");
        assertNull(form.metadata().get("fkFields"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void computedColumnsBecomeLayoutColumnsAndMetadata() throws Exception {
        String json = """
            {
              "dslVersion": "1.0.0", "namespace": "wms.ap", "version": "1.0",
              "concepts": [
                { "name": "Item", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "pos", "type": "integer" },
                  { "name": "cxAvulsas", "type": "integer" } ] }
              ],
              "autoPanels": [
                { "concept": "Item", "surfaces": ["selection", "transaction"],
                  "selection": { "columns": ["pos", "cxAvulsas"],
                                 "computed": [{ "col": "total", "expr": "pos*42 + cxAvulsas" }] },
                  "transaction": { "fields": ["pos", "cxAvulsas"],
                                   "computed": [{ "col": "total", "expr": "pos*42 + cxAvulsas" }] } }
              ]
            }
            """;
        CompiledModel model = compile(json);

        // Selection: computed column appended to the layout, expression recorded in metadata.
        CompiledPanel selection = panel(model, "ItemSelection");
        assertEquals(List.of("pos", "cxAvulsas", "total"), selection.layout().fields());
        List<Map<String, Object>> computed = (List<Map<String, Object>>) selection.metadata().get("computed");
        assertNotNull(computed);
        assertEquals("total", computed.get(0).get("col"));
        assertEquals("pos*42 + cxAvulsas", computed.get(0).get("expr"));

        // Transaction: computed column is a display field, but gets no editable binding.
        CompiledPanel form = panel(model, "ItemForm");
        assertEquals(List.of("pos", "cxAvulsas", "total"), form.layout().fields());
        assertEquals(2, form.fieldBindings().size(), "only the two editable fields have bindings");
        assertTrue(form.fieldBindings().stream().noneMatch(b -> "total".equalsIgnoreCase(b.field())));
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
