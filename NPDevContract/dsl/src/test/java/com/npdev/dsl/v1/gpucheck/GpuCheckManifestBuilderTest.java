package com.npdev.dsl.v1.gpucheck;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GPU-1 (G2.1/G2.2): pins the GPU check manifest and its WGSL against a real model through the real
 * parser, validator and compiler -- every field-rule kind, every invariant shape the translator
 * accepts, and the honest "skipped" verdict for every shape it refuses. The bit-exact agreement with
 * the numpy twin is the CLI's job (G3); this proves the Java half emits what it claims to.
 */
class GpuCheckManifestBuilderTest {

    private static final String MODEL = """
            {
              "namespace": "gpu.check.demo",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                {
                  "name": "Customer",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "name", "type": "string", "required": true, "maxLength": 80 }
                  ]
                },
                {
                  "name": "Note",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "writtenOn", "type": "date" }
                  ]
                },
                {
                  "name": "Order",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "qty", "type": "int", "required": true, "min": 1, "max": 100 },
                    { "name": "price", "type": "decimal", "precision": 10, "scale": 2, "min": 0.5 },
                    { "name": "code", "type": "string", "required": true, "minLength": 3, "maxLength": 10,
                      "unique": true },
                    { "name": "status", "type": "enum", "enumValues": ["OPEN", "CLOSED"] },
                    { "name": "active", "type": "boolean" },
                    { "name": "placedAt", "type": "datetime", "required": true },
                    { "name": "customer", "type": "reference", "ref": "Customer" }
                  ],
                  "invariants": [
                    { "name": "positiveTotal", "expression": "qty + 1 > 1 && price >= 0.5" },
                    { "name": "openOrActive", "expression": "status == 'OPEN' || active" },
                    { "name": "codeNotEmpty", "expression": "!(code == '')" },
                    { "name": "statusKnown", "expression": "status != null" },
                    { "name": "negatedQty", "expression": "-qty < 0" },
                    { "name": "closedNotOpen", "expression": "'CLOSED' == status || status == 'GONE'" },
                    { "name": "doubleQty", "expression": "qty * 2 > 3" },
                    { "name": "upperCode", "expression": "upper(code) == 'X'" },
                    { "name": "datedOrder", "expression": "placedAt != null" },
                    { "name": "codeIsAbc", "expression": "code == 'abc'" },
                    { "name": "codeStatusUnique", "type": "unique", "fields": ["code", "status"] }
                  ]
                }
              ]
            }
            """;

    @Test
    @SuppressWarnings("unchecked")
    void buildsPacksHostChecksAndSkipsFromARealModel() throws Exception {
        Map<String, Object> manifest = GpuCheckManifestBuilder.build(compile(MODEL));

        assertEquals("npdev-gpu-check-manifest.v1", manifest.get("schemaVersion"));
        assertEquals("gpu.check.demo", manifest.get("namespace"));
        List<Map<String, Object>> concepts = (List<Map<String, Object>>) manifest.get("concepts");
        assertEquals(List.of("Customer", "Order"), concepts.stream().map(c -> c.get("concept")).toList(),
                "Note has no rule at all, so it gets no entry; the rest sort by name");

        Map<String, Object> order = concepts.get(1);
        assertNotNull(order.get("table"));
        assertNotNull(order.get("idColumn"));

        List<Map<String, Object>> packs = (List<Map<String, Object>>) order.get("packs");
        assertEquals(1, packs.size(), "every packable check fits one pack (<=32 checks, <=31 columns)");
        Map<String, Object> pack = packs.get(0);
        assertEquals("Order-p0", pack.get("packId"));
        assertEquals("Order-p0.wgsl", pack.get("shader"));
        List<Map<String, Object>> columns = (List<Map<String, Object>>) pack.get("columns");
        assertEquals(columns.size() + 1, pack.get("strideWords"), "word 0 is the null mask");
        Map<String, Map<String, Object>> columnByField = columns.stream()
                .collect(Collectors.toMap(c -> (String) c.get("field"), c -> c));
        assertEquals("i32", columnByField.get("qty").get("encoding"));
        assertEquals("fixed32", columnByField.get("price").get("encoding"));
        assertEquals(2, columnByField.get("price").get("scale"));
        assertEquals("str_len", columnByField.get("code").get("encoding"));
        assertEquals("enum_index", columnByField.get("status").get("encoding"));
        assertEquals(List.of("OPEN", "CLOSED"), columnByField.get("status").get("enumValues"));
        assertEquals("bool", columnByField.get("active").get("encoding"));
        assertFalse(columnByField.containsKey("placedAt"), "datetime has no GPU encoding");

        List<Map<String, Object>> checks = (List<Map<String, Object>>) pack.get("checks");
        List<String> checkIds = checks.stream().map(c -> (String) c.get("id")).toList();
        for (String expected : List.of("Order.qty.required", "Order.qty.min", "Order.qty.max", "Order.price.min",
                "Order.code.required", "Order.code.minLength", "Order.code.maxLength", "Order.status.enum",
                "Order.inv.positiveTotal", "Order.inv.openOrActive", "Order.inv.codeNotEmpty",
                "Order.inv.statusKnown", "Order.inv.negatedQty", "Order.inv.closedNotOpen")) {
            assertTrue(checkIds.contains(expected), "missing packed check " + expected + " in " + checkIds);
        }
        for (int i = 0; i < checks.size(); i++) {
            assertEquals(i, checks.get(i).get("bit"), "bits are assigned densely in id order");
        }
        assertEquals("qty + 1 > 1 && price >= 0.5",
                checks.get(checkIds.indexOf("Order.inv.positiveTotal")).get("source"));

        Map<String, String> hostKinds = ((List<Map<String, Object>>) order.get("hostChecks")).stream()
                .collect(Collectors.toMap(h -> (String) h.get("id"), h -> (String) h.get("kind")));
        assertEquals("requiredOpaque", hostKinds.get("Order.placedAt.required"));
        assertEquals("unique", hostKinds.get("Order.code.unique"));
        assertEquals("reference", hostKinds.get("Order.customer.reference"));
        assertEquals("unique", hostKinds.get("Order.inv.codeStatusUnique"));
        Map<String, Object> reference = ((List<Map<String, Object>>) order.get("hostChecks")).stream()
                .filter(h -> "Order.customer.reference".equals(h.get("id"))).findFirst().orElseThrow();
        assertEquals("Customer", reference.get("targetConcept"));
        assertNotNull(reference.get("targetTable"));
        assertNotNull(reference.get("targetIdColumn"));

        Map<String, String> skipReasons = ((List<Map<String, Object>>) order.get("skipped")).stream()
                .collect(Collectors.toMap(s -> (String) s.get("id"), s -> (String) s.get("reason")));
        assertTrue(skipReasons.get("Order.inv.doubleQty").contains("'*'"), skipReasons.toString());
        assertTrue(skipReasons.get("Order.inv.upperCode").contains("'upper'"), skipReasons.toString());
        assertTrue(skipReasons.get("Order.inv.datedOrder").contains("no GPU encoding"), skipReasons.toString());
        // Passes analyze() (str_len is a supported encoding) but the WGSL emitter can only compare a
        // string with ''. It must be skipped HERE -- packed, it would make shaders() throw for the
        // whole manifest, failing generation of every concept's shader, not just this one check.
        assertTrue(skipReasons.get("Order.inv.codeIsAbc").contains("string comparison"), skipReasons.toString());
    }

    @Test
    void shadersRegenerateFromTheManifestAloneWithOneBranchPerCheck() throws Exception {
        Map<String, Object> manifest = GpuCheckManifestBuilder.build(compile(MODEL));

        Map<String, String> shaders = GpuCheckManifestBuilder.shaders(manifest);

        assertEquals(List.of("Customer-p0.wgsl", "Order-p0.wgsl"), List.copyOf(shaders.keySet()));
        String order = shaders.get("Order-p0.wgsl");
        assertTrue(order.startsWith("// npdev gpu-check: generated by NPDev, do not edit. Concept Order, "), order);
        assertTrue(order.contains("@compute @workgroup_size(64)"), order);
        assertTrue(order.contains("fails[row] = f;"), order);
        assertTrue(order.contains("// [0] "), order);
        assertTrue(order.contains("f = f | 1u;"), order);
        assertTrue(order.contains("!= 0xFFFFFFFFu"), "the enum check compares against the not-in-enum sentinel");
        assertTrue(order.contains("== 0xFFFFFFFEu"), "'GONE' is not an enum value, so it can never match");
        assertTrue(order.contains("50"), "price >= 0.5 at scale 2 becomes the integer literal 50: " + order);
        // code's raw str_len word is rescaled to the pack's scale (2, from price) before it meets
        // maxLength's literal 10 -> 1000; unscaled, "length <= 1000" would pass a 900-char code.
        assertTrue(order.contains(" * 100u) <= 1000)"), order);
        assertTrue(GpuCheckManifestBuilder.shaders(Map.of()).isEmpty());
    }

    @Test
    void emitterTranslatesEveryValueAndComparisonShape() {
        GpuWgslEmitter emitter = new GpuWgslEmitter(List.of(
                new GpuManifestColumn(1, 0, "n", "n", "i32", null, null),
                new GpuManifestColumn(2, 1, "flag", "flag", "bool", null, null),
                new GpuManifestColumn(3, 2, "s", "s", "str_len", null, null),
                new GpuManifestColumn(4, 3, "e", "e", "enum_index", null, List.of("A", "B"))), 1);

        assertEquals("(i(base, 1u) > 15)", emitter.booleanExpr(tree("n > 1.5")));
        assertEquals("(!isNull(base, 1u) && (u(base, 2u) != 0u))", emitter.booleanExpr(tree("flag")));
        assertEquals("true", emitter.booleanExpr(tree("true")));
        assertEquals("false", emitter.booleanExpr(tree("n")), "a non-boolean value is never truthy");
        assertEquals("((-i(base, 1u)) < (i(base, 1u) - 10))", emitter.booleanExpr(tree("-n < n - 1")));
        assertEquals("(isNull(base, 0u))", emitter.booleanExpr(tree("null == n")));
        assertEquals("(!(isNull(base, 0u)))", emitter.booleanExpr(tree("n != null")));
        assertEquals("(!isNull(base, 3u) && (u(base, 4u) == 1u))", emitter.booleanExpr(tree("'B' == e")));
        assertEquals("(!isNull(base, 2u) && (u(base, 3u) == 0u))", emitter.booleanExpr(tree("s == ''")));
        assertTrue(emitter.booleanExpr(tree("n == n")).startsWith("((isNull(base, 0u) && isNull(base, 0u))"));

        assertThrows(GpuUnsupportedException.class, () -> emitter.booleanExpr(tree("s == 'abc'")));
        assertThrows(GpuUnsupportedException.class, () -> emitter.booleanExpr(tree("upper(s) == 'X'")));
        assertThrows(GpuUnsupportedException.class, () -> emitter.booleanExpr(tree("n + 1")));
    }

    @Test
    void supportAnalysisRefusesWhatItCannotReproduceExactly() {
        Map<String, GpuColumnInfo> columns = Map.of(
                "price", new GpuColumnInfo("fixed32", 2, List.of()),
                "n", new GpuColumnInfo("i32", 0, List.of()),
                "blob", new GpuColumnInfo("bytes", 0, List.of()));

        assertEquals(3, GpuCheckSupport.analyze(tree("price > 0.125"), columns), "the widest decimal wins");
        assertEquals(2, GpuCheckSupport.analyze(tree("!(price > n)"), columns));
        assertEquals(0, GpuCheckSupport.decimalsOf(null));
        assertEquals("1250", GpuCheckSupport.scaledIntLiteral("1.25", 3));

        // The validator already refuses a non-boolean invariant; this is the translator's own guard.
        assertTrue(assertThrows(GpuUnsupportedException.class, () -> GpuCheckSupport.analyze(tree("n + 1"), columns))
                .getMessage().contains("top level"));
        assertThrows(GpuUnsupportedException.class, () -> GpuCheckSupport.analyze(tree("n % 2 == 0"), columns));
        assertThrows(GpuUnsupportedException.class, () -> GpuCheckSupport.analyze(tree("blob == null"), columns));
        assertThrows(GpuUnsupportedException.class, () -> GpuCheckSupport.analyze(tree("ghost > 1"), columns));
        assertThrows(GpuUnsupportedException.class, () -> GpuCheckSupport.analyze(tree("a.b > 1"), columns));
        assertThrows(GpuUnsupportedException.class, () -> GpuCheckSupport.analyze(tree("$user > 1"), columns));
        assertThrows(GpuUnsupportedException.class, () -> GpuCheckSupport.analyze(
                Map.of("k", "bin", "op", "==", "l", Map.of("k", "lambda"), "r", Map.of("k", "lambda")), columns));
    }

    private static Map<String, Object> tree(String expression) {
        return ComputedExpression.toPortableTree(expression);
    }

    private static CompiledModel compile(String json) throws Exception {
        Path modelPath = Files.createTempFile("npdev-gpu-check-", ".json");
        Files.writeString(modelPath, json);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "the fixture model must be valid: " + errors);
        return new ModelCompiler().compile(ast);
    }
}
