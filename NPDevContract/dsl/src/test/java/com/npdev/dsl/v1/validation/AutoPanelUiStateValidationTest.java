package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 11 W6 (C1, docs/MOVE3_G2_CHECKLISTS.md). {@code transaction.visibleWhen}'s grammar reads
 * persisted ROOT fields only -- {@code $root.<field> == '<literal>'} -- so centro-trabalho's
 * record-type toggle, which is transient screen state and a field of nothing, had nothing for the
 * predicate to read. {@code transaction.uiState} declares that state and {@code $ui.<name>} resolves
 * it, using the SAME grammar rather than a second dialect.
 *
 * <p>The validation half matters as much as the mechanism: {@code evaluateVisibleWhen} fails OPEN by
 * design (an unrecognized predicate leaves the surface visible), so a typo'd toggle name would
 * otherwise be a control that silently does nothing, with no error anywhere.
 */
class AutoPanelUiStateValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("a $ui predicate over a declared control validates clean and survives compile + canonical round-trip")
    void declaredUiStateValidatesAndCompiles() throws Exception {
        ModelAst ast = parse(model("""
                  "uiState": {
                    "registro": {
                      "label": "Tipo de registro",
                      "values": ["Recebimento", "Expedicao"],
                      "default": "Recebimento"
                    }
                  },
                  "visibleWhen": { "itens": "$ui.registro == 'Recebimento'" }
                """));
        assertTrue(new SemanticValidator().validate(ast).isEmpty(),
                "expected no errors: " + new SemanticValidator().validate(ast));

        CompiledModel compiled = new ModelCompiler().compile(ast);

        // The workbench descriptor the client renders must carry the control.
        CompiledPanel workbench = compiled.getPanels().stream()
                .filter(p -> p.name().endsWith("Workbench")).findFirst().orElse(null);
        assertNotNull(workbench, "expected an expanded Aggregate Workbench panel");
        Object uiState = castMap(workbench.metadata().get("workbench")).get("uiState");
        assertTrue(uiState instanceof List<?> list && !list.isEmpty(),
                "expected the workbench descriptor to carry uiState, got: " + uiState);
        Map<?, ?> control = (Map<?, ?>) ((List<?>) uiState).get(0);
        assertEquals("registro", control.get("name"));
        assertEquals("Tipo de registro", control.get("label"));
        assertEquals(List.of("Recebimento", "Expedicao"), control.get("values"));
        assertEquals("Recebimento", control.get("default"));

        // R0.3: canonical JSON is a WRITER and a READER. A field only the writer knows about is a
        // field that silently disappears the moment anything reads a compiled model back.
        //
        // Was scoped to uiState while REG-97 was open (an invariant's empty `fields: []` came back
        // as `["id"]`, so a whole-document assertion failed on something unrelated to this feature).
        // REG-97 is fixed, so the assertion is back to the full document -- which is the one that
        // would actually catch a NEW field the writer knows about and the reader does not.
        CompiledModel roundTripped = CompiledModelCanonicalJsonReader.fromJson(
                CompiledModelCanonicalJson.toJson(compiled));
        assertEquals(CompiledModelCanonicalJson.toJson(compiled),
                CompiledModelCanonicalJson.toJson(roundTripped),
                "compiled model must survive a canonical write -> read -> write round trip unchanged");
    }

    @Test
    @DisplayName("REG-99: a BAND's visibleWhen, keyed by its derived address, actually reaches the band descriptor")
    void bandVisibleWhenReachesTheBandDescriptor() throws Exception {
        // Found live: `itens.posicoes` -- the ONLY spelling PanelValidation.derivedAddresses accepts --
        // validated and was then silently dropped, because the expander looked up the bare band name.
        // A bare `posicoes` key would have been read but rejected by validation. Band-level
        // visibleWhen was unreachable in every spelling.
        ModelAst ast = parse(model("""
                  "uiState": {
                    "detalhe": { "values": ["Completo", "Resumo"], "default": "Completo" }
                  },
                  "visibleWhen": { "itens.posicoes": "$ui.detalhe == 'Completo'" }
                """, true));
        assertTrue(new SemanticValidator().validate(ast).isEmpty(),
                "expected no errors: " + new SemanticValidator().validate(ast));

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledPanel workbench = compiled.getPanels().stream()
                .filter(p -> p.name().endsWith("Workbench")).findFirst().orElseThrow();
        List<?> sections = (List<?>) castMap(workbench.metadata().get("workbench")).get("sections");
        Map<?, ?> section = (Map<?, ?>) sections.get(0);
        List<?> bands = (List<?>) section.get("bands");
        assertTrue(!bands.isEmpty(), "expected the posicoes band in the descriptor");
        Map<?, ?> band = (Map<?, ?>) bands.get(0);

        assertEquals("itens.posicoes", band.get("address"));
        assertEquals("$ui.detalhe == 'Completo'", band.get("visibleWhen"),
                "the band must carry the predicate declared against its own derived address -- "
                        + "otherwise it validates and does nothing (REG-99)");
    }

    @Test
    @DisplayName("a $ui predicate naming an UNDECLARED control is refused, not silently ignored")
    void undeclaredUiStateIsRefused() throws Exception {
        ModelAst ast = parse(model("""
                  "visibleWhen": { "itens": "$ui.registro == 'Recebimento'" }
                """));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("$ui.registro") && e.contains("not declared")),
                "expected an undeclared-$ui error, got: " + errors);
    }

    @Test
    @DisplayName("a $ui predicate comparing against a value the control does not offer is refused")
    void unreachableLiteralIsRefused() throws Exception {
        ModelAst ast = parse(model("""
                  "uiState": {
                    "registro": { "values": ["Recebimento", "Expedicao"] }
                  },
                  "visibleWhen": { "itens": "$ui.registro == 'Transferencia'" }
                """));
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.stream().anyMatch(e -> e.contains("Transferencia") && e.contains("can never be true")),
                "expected an unreachable-literal error, got: " + errors);
    }

    @Test
    @DisplayName("a $root predicate is untouched -- one grammar, two roots, no second dialect")
    void rootPredicatesAreUnaffected() throws Exception {
        ModelAst ast = parse(model("""
                  "visibleWhen": { "itens": "$root.situacao == 'Pendente'" }
                """));
        assertTrue(new SemanticValidator().validate(ast).isEmpty(),
                "a $root predicate must still validate with no uiState declared: "
                        + new SemanticValidator().validate(ast));
    }

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static String model(String transactionBody) {
        return model(transactionBody, false);
    }

    /**
     * A minimal aggregate-bound AutoPanel; {@code transactionBody} is spliced into its transaction.
     * {@code withBand} adds a second nesting level so a {@code "<collection>.<band>"} address exists.
     */
    private static String model(String transactionBody, boolean withBand) {
        String bandConcept = withBand ? """
            ,
                { "name": "MovimentoItemPosicao", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "movimentoItemId", "type": "uuid" },
                  { "name": "papel", "type": "string" } ] }
            """ : "";
        String bandCollection = withBand ? """
            ,
                    "collections": [
                      { "name": "posicoes", "concept": "MovimentoItemPosicao",
                        "childField": "movimentoItemId", "ownership": "owned" } ]
            """ : "";
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.uistate", "version": "1.0",
              "concepts": [
                { "name": "Movimento", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "situacao", "type": "string" } ] },
                { "name": "MovimentoItem", "fields": [
                  { "name": "id", "type": "uuid", "id": true, "required": true },
                  { "name": "movimentoId", "type": "uuid" },
                  { "name": "quantidade", "type": "integer" } ] }%s
              ],
              "aggregates": [
                { "name": "Movimento", "root": "Movimento", "collections": [
                  { "name": "itens", "concept": "MovimentoItem", "childField": "movimentoId",
                    "ownership": "owned"%s } ] }
              ],
              "autoPanels": [
                { "aggregate": "Movimento", "transaction": { %s } }
              ]
            }
            """.formatted(bandConcept, bandCollection, transactionBody);
    }

}
