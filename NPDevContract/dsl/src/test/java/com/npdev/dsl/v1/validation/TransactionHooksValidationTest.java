package com.npdev.dsl.v1.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.2): an aggregate-bound AutoPanel's
 * transaction.hooks.onValidate/onCommit/onLoad/onFieldChange/beforeAction is validated against
 * declared procedures the same way the pre-existing aggregate.onValidate/onCommit fields already
 * were (AggregateValidation), and folds onto the aggregate's own onValidate/onCommit at compile
 * time when the aggregate itself declares neither directly.
 */
class TransactionHooksValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ModelAst parse(String json) throws Exception {
        return new JsonModelParser().parse(MAPPER.readTree(json));
    }

    private static List<String> validate(String json) throws Exception {
        return new SemanticValidator().validate(parse(json));
    }

    private static CompiledAggregate compileAggregate(String json, String aggregateName) throws Exception {
        CompiledModel model = new ModelCompiler().compile(parse(json));
        Optional<CompiledAggregate> found = model.getAggregates().stream()
                .filter(a -> aggregateName.equals(a.name()))
                .findFirst();
        assertTrue(found.isPresent(), "expected a compiled aggregate named " + aggregateName);
        return found.get();
    }

    private static String modelWithHooks(String hooksJson, String aggregateOnValidate, String aggregateOnCommit) {
        String aggregateExtra =
                (aggregateOnValidate == null ? "" : ", \"onValidate\": \"" + aggregateOnValidate + "\"")
                + (aggregateOnCommit == null ? "" : ", \"onCommit\": \"" + aggregateOnCommit + "\"");
        return """
            {
              "dslVersion": "1.0.0", "namespace": "wms.hooks", "version": "1.0",
              "concepts": [
                { "name": "Movimento", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                { "name": "MovimentoItem", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "movimentoId", "type": "uuid" } ] }
              ],
              "procedures": [
                { "name": "ValidateMovimento", "steps": [ { "name": "ret", "type": "return", "value": "$input" } ] },
                { "name": "SyncOcupacao", "steps": [ { "name": "ret", "type": "return", "value": "$input" } ] },
                { "name": "DirectValidate", "steps": [ { "name": "ret", "type": "return", "value": "$input" } ] },
                { "name": "DirectCommit", "steps": [ { "name": "ret", "type": "return", "value": "$input" } ] }
              ],
              "aggregates": [
                { "name": "Movimento", "root": "Movimento"%s,
                  "collections": [ { "name": "itens", "concept": "MovimentoItem", "childField": "movimentoId", "ownership": "owned" } ] }
              ],
              "autoPanels": [
                { "aggregate": "Movimento",
                  "transaction": { "hooks": %s } }
              ]
            }
            """.formatted(aggregateExtra, hooksJson);
    }

    @Test
    void hooksOnValidateAndOnCommitResolveWhenAggregateDeclaresNeitherDirectly() throws Exception {
        String json = modelWithHooks(
                "{\"onValidate\": \"ValidateMovimento\", \"onCommit\": \"SyncOcupacao\"}", null, null);

        List<String> errors = validate(json);
        assertTrue(errors.isEmpty(), "expected no validation errors, got: " + errors);

        CompiledAggregate aggregate = compileAggregate(json, "Movimento");
        assertEquals("ValidateMovimento", aggregate.onValidate());
        assertEquals("SyncOcupacao", aggregate.onCommit());
    }

    @Test
    void directAggregateOnValidateAndOnCommitWinOverHooks() throws Exception {
        String json = modelWithHooks(
                "{\"onValidate\": \"ValidateMovimento\", \"onCommit\": \"SyncOcupacao\"}",
                "DirectValidate", "DirectCommit");

        List<String> errors = validate(json);
        assertTrue(errors.isEmpty(), "expected no validation errors, got: " + errors);

        CompiledAggregate aggregate = compileAggregate(json, "Movimento");
        assertEquals("DirectValidate", aggregate.onValidate());
        assertEquals("DirectCommit", aggregate.onCommit());
    }

    @Test
    void hooksOnValidateNamingAnUndeclaredProcedureIsRejected() throws Exception {
        String json = modelWithHooks("{\"onValidate\": \"NoSuchProcedure\"}", null, null);

        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains("onValidate names a procedure not found")
                        && e.contains("NoSuchProcedure")),
                "expected an onValidate-not-found error, got: " + errors);
    }

    @Test
    void hooksOnCommitNamingAnUndeclaredProcedureIsRejected() throws Exception {
        String json = modelWithHooks("{\"onCommit\": \"NoSuchProcedure\"}", null, null);

        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains("onCommit names a procedure not found")
                        && e.contains("NoSuchProcedure")),
                "expected an onCommit-not-found error, got: " + errors);
    }

    @Test
    void hooksOnLoadOnFieldChangeAndBeforeActionNamingUndeclaredProceduresAreRejected() throws Exception {
        String json = modelWithHooks(
                "{\"onLoad\": \"NoSuchOnLoad\", \"onFieldChange\": \"NoSuchOnFieldChange\", "
                        + "\"beforeAction\": \"NoSuchBeforeAction\"}", null, null);

        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains("hooks.onLoad") && e.contains("NoSuchOnLoad")),
                "expected an onLoad-not-found error, got: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("hooks.onFieldChange") && e.contains("NoSuchOnFieldChange")),
                "expected an onFieldChange-not-found error, got: " + errors);
        assertTrue(errors.stream().anyMatch(e -> e.contains("hooks.beforeAction") && e.contains("NoSuchBeforeAction")),
                "expected a beforeAction-not-found error, got: " + errors);
    }
}
