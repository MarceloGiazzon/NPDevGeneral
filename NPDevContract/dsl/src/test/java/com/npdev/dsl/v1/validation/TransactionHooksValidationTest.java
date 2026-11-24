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
 * Move 6 Move B (docs/MOVE6_TYPED_SURFACE_PLAN.md §B.2): an aggregate-bound screen's
 * transaction.hooks.onLoad/onFieldChange/beforeAction is validated against declared procedures the same way
 * the aggregate's own composition.onValidate/onCommit are (AggregateValidation). REG-359: the screen-side
 * spelling of onValidate/onCommit was removed -- the concept-level one always won, so it could never take
 * effect and a sample of it proved nothing -- which leaves composition.onValidate/onCommit as the only way.
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
              "dslVersion": "3.0.0", "namespace": "wms.hooks", "version": "1.0",
              "concepts": [
                { "name": "Movimento", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true } ],
                  "composition": { "collections": [ { "name": "itens", "concept": "MovimentoItem", "childField": "movimentoId", "ownership": "owned" } ]%s } },
                { "name": "MovimentoItem", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "movimentoId", "type": "uuid" } ] }
              ],
              "flows": [{"name": "ValidateMovimento", "expose": "ui", "steps": [{"name": "done", "type": "return", "value": "$input"}]}, {"name": "SyncOcupacao", "expose": "ui", "steps": [{"name": "done", "type": "return", "value": "$input"}]}, {"name": "DirectValidate", "expose": "ui", "steps": [{"name": "done", "type": "return", "value": "$input"}]}, {"name": "DirectCommit", "expose": "ui", "steps": [{"name": "done", "type": "return", "value": "$input"}]}],
              "screens": [ { "name": "Movimento", "kind": "workbench", "concept": "Movimento", "admin": { "transaction": { "hooks": %s } } } ]
            }
            """.formatted(aggregateExtra, hooksJson);
    }

    @Test
    void compositionOnValidateAndOnCommitReachTheCompiledAggregate() throws Exception {
        String json = modelWithHooks("{}", "ValidateMovimento", "SyncOcupacao");

        List<String> errors = validate(json);
        assertTrue(errors.isEmpty(), "expected no validation errors, got: " + errors);

        CompiledAggregate aggregate = compileAggregate(json, "Movimento");
        assertEquals("ValidateMovimento", aggregate.onValidate());
        assertEquals("SyncOcupacao", aggregate.onCommit());
    }

    @Test
    void compositionOnValidateNamingAnUndeclaredProcedureIsRejected() throws Exception {
        String json = modelWithHooks("{}", "NoSuchProcedure", null);

        List<String> errors = validate(json);
        assertTrue(errors.stream().anyMatch(e -> e.contains("onValidate names a procedure not found")
                        && e.contains("NoSuchProcedure")),
                "expected an onValidate-not-found error, got: " + errors);
    }

    @Test
    void compositionOnCommitNamingAnUndeclaredProcedureIsRejected() throws Exception {
        String json = modelWithHooks("{}", null, "NoSuchProcedure");

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
