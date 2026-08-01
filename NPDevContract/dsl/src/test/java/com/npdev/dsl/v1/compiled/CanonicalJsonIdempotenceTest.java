package com.npdev.dsl.v1.compiled;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * REG-97 (Wave 1.1, MASTER_AI_PLATFORM_PROGRAMME_v2.md §3.4). "Canonical" is load-bearing in two
 * places that both assume a byte-stable form -- {@code npdev-generated/} is hash-verified at app
 * startup, and {@code npdev.schema.fingerprint} plus the schema engine's compiled-model comparison
 * are equality-over-canonical-form arguments -- yet
 * {@code toJson(model) != toJson(fromJson(toJson(model)))}.
 *
 * <p>The programme re-rates this from LOW to MEDIUM because {@code LC-C2}'s central DoD is <i>"the
 * resulting metadata is byte-identical to what a full regeneration produces -- one test that runs
 * both and compares"</i>. A canonical form whose value depends on how many round-trips a path has
 * performed makes that test fail INTERMITTENTLY, which is worse than failing.
 *
 * <p>This test is the assertion REG-97's own fix shape asks for: <i>"assert idempotence
 * ({@code toJson(fromJson(toJson(m))) == toJson(m)}) as a real test over a rich fixture model, so
 * this class cannot recur silently for the next field either."</i> It is deliberately whole-document
 * rather than field-scoped -- a field-scoped version is what
 * {@code AutoPanelUiStateValidationTest} had to settle for while this was open.
 */
class CanonicalJsonIdempotenceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("REG-97: write -> read -> write is byte-identical for a model exercising panels, aggregates and autoPanels")
    void canonicalJsonIsIdempotent() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(RICH_MODEL));
        CompiledModel compiled = new ModelCompiler().compile(ast);

        String first = CompiledModelCanonicalJson.toJson(compiled);
        String second = CompiledModelCanonicalJson.toJson(CompiledModelCanonicalJsonReader.fromJson(first));

        if (!first.equals(second)) {
            // A whole-document diff is unreadable in an assertion message; report the first
            // differing lines with their line numbers, which is what actually locates the field.
            String[] left = first.split("\n");
            String[] right = second.split("\n");
            StringBuilder report = new StringBuilder("canonical JSON is not idempotent:\n");
            int shown = 0;
            for (int index = 0; index < Math.max(left.length, right.length) && shown < 10; index++) {
                String a = index < left.length ? left[index] : "<eof>";
                String b = index < right.length ? right[index] : "<eof>";
                if (!a.equals(b)) {
                    report.append("  line ").append(index).append("\n    write1: ").append(a)
                            .append("\n    write2: ").append(b).append('\n');
                    shown++;
                }
            }
            assertEquals(first, second, report.toString());
        }
        assertEquals(first, second);
    }

    /** Rich enough to cover the shapes that actually differed: panels, a layout, an aggregate-bound AutoPanel. */
    private static final String RICH_MODEL = """
        {
          "dslVersion": "1.0.0", "namespace": "reg97.idempotence", "version": "1.0",
          "concepts": [
            { "name": "Movimento", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "situacao", "type": "string" } ] },
            { "name": "MovimentoItem", "fields": [
              { "name": "id", "type": "uuid", "id": true, "required": true },
              { "name": "movimentoId", "type": "uuid" },
              { "name": "quantidade", "type": "integer" } ] }
          ],
          "aggregates": [
            { "name": "Movimento", "root": "Movimento", "collections": [
              { "name": "itens", "concept": "MovimentoItem", "childField": "movimentoId", "ownership": "owned" } ] }
          ],
          "autoPanels": [
            { "aggregate": "Movimento", "transaction": {
                "uiState": { "detalhe": { "values": ["Completo", "Resumo"], "default": "Completo" } },
                "visibleWhen": { "itens": "$ui.detalhe == 'Completo'" } } }
          ],
          "panels": [
            { "name": "MovimentoConsole", "route": "/movimentos/console",
              "dataSources": [ { "name": "movimentos", "concept": "Movimento" } ] }
          ]
        }
        """;
}
