package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.AutoPanelAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R5.6: the four Aggregate Workbench label sites the {@code migrate_label_locales} codemod
 * (NPDevCli/dsl_v2_migration.py) and the schema both cover but {@link LabelLocaleMapTest} does not
 * reach -- {@code transaction.metadata.actions[].label} ({@link com.npdev.dsl.v1.ast.WorkbenchActionAst}),
 * {@code transaction.bandPickers.<name>.label} ({@link com.npdev.dsl.v1.ast.WorkbenchBandPickerAst}),
 * {@code transaction.derivedFields.<name>.label} ({@link com.npdev.dsl.v1.ast.DerivedFieldAst}), and
 * {@code transaction.uiState.<name>.label} ({@link com.npdev.dsl.v1.ast.UiStateControlAst}).
 *
 * <p>Checked at the {@link JsonModelParser} -> {@link ModelAst} boundary directly (rather than
 * through compile + AutoPanelExpander + canonical JSON, a much heavier pipeline for the same
 * question): does the parser actually read the object form's "default" + locale entries into each
 * AST type's {@code labelLocales()}? The compiled-canonical-JSON writer/reader symmetry for these
 * same four {@code Compiled*} types is separately, generically proven by
 * {@link com.npdev.dsl.v1.compiled.CanonicalJsonRoundTripCompletenessTest}'s reflective sweep over
 * every {@code Compiled*} type's widest constructor.
 */
class AutoPanelLabelLocaleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void plainStringLabelsOnEveryWorkbenchSiteParseUnchanged() throws Exception {
        AutoPanelAst.class.getName(); // keep import used even if surface shape changes
        var transaction = parseTransaction(
                "\"Aprovar\"", "\"Selecionar posicao\"", "\"Total\"", "\"Tipo de registro\"", false);

        assertEquals("Aprovar", transaction.actions().get(0).label());
        assertTrue(transaction.actions().get(0).labelLocales().isEmpty());

        assertEquals("Selecionar posicao", transaction.bandPickers().get("posicoes").label());
        assertTrue(transaction.bandPickers().get("posicoes").labelLocales().isEmpty());

        assertEquals("Total", transaction.derivedFields().get(0).label());
        assertTrue(transaction.derivedFields().get(0).labelLocales().isEmpty());

        assertEquals("Tipo de registro", transaction.uiState().get("registro").label());
        assertTrue(transaction.uiState().get("registro").labelLocales().isEmpty());
    }

    @Test
    void objectFormLabelsOnEveryWorkbenchSiteCarryDefaultAndLocales() throws Exception {
        var transaction = parseTransaction(
                "{ \"default\": \"Aprovar\", \"en\": \"Approve\" }",
                "{ \"default\": \"Selecionar posicao\", \"en\": \"Select position\" }",
                "{ \"default\": \"Total\", \"en\": \"Total\" }",
                "{ \"default\": \"Tipo de registro\", \"en\": \"Record type\" }",
                false);

        assertEquals("Aprovar", transaction.actions().get(0).label());
        assertEquals(Map.of("en", "Approve"), transaction.actions().get(0).labelLocales());

        assertEquals("Selecionar posicao", transaction.bandPickers().get("posicoes").label());
        assertEquals(Map.of("en", "Select position"), transaction.bandPickers().get("posicoes").labelLocales());

        assertEquals("Total", transaction.derivedFields().get(0).label());
        assertEquals(Map.of("en", "Total"), transaction.derivedFields().get(0).labelLocales());

        assertEquals("Tipo de registro", transaction.uiState().get("registro").label());
        assertEquals(Map.of("en", "Record type"), transaction.uiState().get("registro").labelLocales());
    }

    private static com.npdev.dsl.v1.ast.AutoPanelSurfaceAst parseTransaction(
            String actionLabel, String bandPickerLabel, String derivedFieldLabel, String uiStateLabel,
            boolean unused) throws Exception {
        String json = """
                {
                  "dslVersion": "1.0.0", "namespace": "wms.workbenchlabels", "version": "1.0",
                  "concepts": [
                    { "name": "Movimento", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "situacao", "type": "string" } ] },
                    { "name": "MovimentoItem", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "movimentoId", "type": "uuid" },
                      { "name": "quantidade", "type": "integer" } ] },
                    { "name": "MovimentoItemPosicao", "fields": [
                      { "name": "id", "type": "uuid", "id": true, "required": true },
                      { "name": "movimentoItemId", "type": "uuid" },
                      { "name": "papel", "type": "string" } ] }
                  ],
                  "aggregates": [
                    { "name": "Movimento", "root": "Movimento", "collections": [
                      { "name": "itens", "concept": "MovimentoItem", "childField": "movimentoId",
                        "ownership": "owned", "collections": [
                          { "name": "posicoes", "concept": "MovimentoItemPosicao",
                            "childField": "movimentoItemId", "ownership": "owned" } ] } ] }
                  ],
                  "procedures": [
                    { "name": "AprovarMovimento", "steps": [ { "name": "ret", "type": "return", "value": "$input" } ] }
                  ],
                  "autoPanels": [
                    { "aggregate": "Movimento", "transaction": {
                        "actions": [ { "procedure": "AprovarMovimento", "label": %s } ],
                        "bandPickers": { "posicoes": { "panel": "PosicaoSelection", "label": %s } },
                        "derivedFields": { "total": { "expression": "1+1", "label": %s } },
                        "uiState": { "registro": { "values": ["A", "B"], "label": %s } }
                      } }
                  ]
                }
                """.formatted(actionLabel, bandPickerLabel, derivedFieldLabel, uiStateLabel);

        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        assertTrue(new SemanticValidator().validate(ast).isEmpty(),
                "expected no validation errors: " + new SemanticValidator().validate(ast));
        return ast.getAutoPanels().get(0).transaction();
    }
}
