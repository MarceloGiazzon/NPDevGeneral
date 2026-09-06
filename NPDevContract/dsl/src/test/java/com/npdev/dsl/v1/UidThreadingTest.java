package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ConceptAst;
import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * REG-209 (B1 lift, {@code ALL_HITTABLE_LIFT_PLAN_2026-09-05.md} package P7): a stable {@code uid}
 * on a concept/field survives {@link JsonModelParser} -> {@link ModelCompiler} at BOTH the AST and
 * compiled layers -- {@link com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReaderTest}'s sibling
 * {@link com.npdev.dsl.v1.compiled.CanonicalJsonRoundTripCompletenessTest} already proves the
 * canonical-JSON writer/reader half of the chain reflectively; this covers the parser/compiler half
 * the reflective test cannot reach (it starts from an already-compiled fixture, not real JSON text).
 */
class UidThreadingTest {

    @Test
    void aDeclaredUidSurvivesParsingAndCompilationOnBothConceptAndField() throws Exception {
        Path modelPath = Files.createTempFile("npdev-uid-threading-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "uid.threading.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Widget",
                      "uid": "wg00000000000001",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "sku", "type": "string", "uid": "wg0f000000000001" }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        ConceptAst widget = ast.getConcepts().stream()
                .filter(c -> "Widget".equals(c.getName())).findFirst().orElseThrow();
        assertEquals("wg00000000000001", widget.getUid());
        FieldAst sku = widget.getFields().stream()
                .filter(f -> "sku".equals(f.getName())).findFirst().orElseThrow();
        assertEquals("wg0f000000000001", sku.getUid());
        FieldAst id = widget.getFields().stream()
                .filter(f -> "id".equals(f.getName())).findFirst().orElseThrow();
        assertNull(id.getUid(), "a field declaring no uid must compile with a null one, not a default value");

        CompiledModel compiled = new ModelCompiler().compile(ast);
        CompiledConcept compiledWidget = compiled.findConcept("Widget").orElseThrow();
        assertEquals("wg00000000000001", compiledWidget.getUid());
        CompiledField compiledSku = compiledWidget.getFields().stream()
                .filter(f -> "sku".equals(f.getName())).findFirst().orElseThrow();
        assertEquals("wg0f000000000001", compiledSku.getUid());
    }

    @Test
    void aModelWithNoUidsAtAllCompilesWithEveryUidNull() throws Exception {
        // The plan's own "Done when" bar: a model with no uids at all behaves exactly as today.
        Path modelPath = Files.createTempFile("npdev-uid-threading-absent-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "uid.threading.absent.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Gadget", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        assertNull(ast.getConcepts().get(0).getUid());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertNull(compiled.findConcept("Gadget").orElseThrow().getUid());
    }
}
