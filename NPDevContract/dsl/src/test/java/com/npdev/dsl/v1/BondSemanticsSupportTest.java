package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.FieldAst;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bonds v1: connectable anchors, {@code via} anchor selection and {@code onDelete} integrity policy.
 * "Id binds Id" — a port binds an anchor that must be the id or a connectable/unique key.
 */
class BondSemanticsSupportTest {

    @Test
    void parserAndValidatorAcceptBondViaConnectableAnchor() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bond-ok-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "bond.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "skuId", "type": "string", "unique": true, "connectable": "anchor" },
                        { "name": "name", "type": "string" }
                      ]
                    },
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "quantity", "type": "int" },
                        {
                          "name": "productId",
                          "type": "reference",
                          "required": true,
                          "reference": {
                            "target": "Product",
                            "via": "skuId",
                            "onDelete": "restrict"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        FieldAst productRef = ast.getConcepts().get(1).getFields().get(2);
        assertEquals("Product", productRef.getReferenceTarget());
        assertNotNull(productRef.getReferenceSemantics());
        assertEquals("skuId", productRef.getReferenceSemantics().getVia());
        assertEquals("restrict", productRef.getReferenceSemantics().getOnDelete());

        FieldAst skuAnchor = ast.getConcepts().get(0).getFields().get(1);
        assertEquals("anchor", skuAnchor.getConnectable());

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected valid bond, got: " + errors);
    }

    @Test
    void validatorAcceptsMultipleReferenceBondForJunctionSynthesis() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bond-many-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "bond.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "skuId", "type": "string", "unique": true, "connectable": "anchor" }
                      ]
                    },
                    {
                      "name": "Bundle",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "productId",
                          "type": "reference",
                          "reference": {
                            "target": "Product",
                            "multiple": true,
                            "via": "skuId",
                            "onDelete": "cascade"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        FieldAst productSet = ast.getConcepts().get(1).getFields().get(1);

        assertNotNull(productSet.getReferenceSemantics());
        assertTrue(productSet.getReferenceSemantics().isMultiple());
        assertEquals("skuId", productSet.getReferenceSemantics().getVia());
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected multiple bond to validate for junction synthesis, got: " + errors);
    }

    @Test
    void validatorRejectsPlainUniqueNaturalKeyWithoutConnectableAnchor() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bond-plain-unique-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "bond.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "skuId", "type": "string", "unique": true }
                      ]
                    },
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "productSku",
                          "type": "reference",
                          "reference": {
                            "target": "Product",
                            "via": "skuId"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(
                errors.stream().anyMatch(e -> e.contains("unique=true and connectable:anchor")),
                "Expected strict anchor error, got: " + errors
        );
    }

    @Test
    void validatorAcceptsIdViaWithoutConnectableAnchor() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bond-id-via-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "bond.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "productId",
                          "type": "reference",
                          "reference": {
                            "target": "Product",
                            "via": "id"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(errors.isEmpty(), "Expected id via to validate, got: " + errors);
    }

    @Test
    void validatorRejectsViaNonAnchorAndNonUniqueAnchor() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bond-bad-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "bond.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "label", "type": "string", "connectable": "anchor" },
                        { "name": "name", "type": "string" }
                      ]
                    },
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "productId",
                          "type": "reference",
                          "reference": {
                            "target": "Product",
                            "via": "name",
                            "onDelete": "restrict"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(
                errors.stream().anyMatch(e -> e.contains("connectable anchor field must be unique")),
                "Expected non-unique anchor error, got: " + errors
        );
        assertTrue(
                errors.stream().anyMatch(e -> e.contains("reference via must target a connectable anchor")),
                "Expected via-non-anchor error, got: " + errors
        );
    }

    @Test
    void validatorRejectsNullifyOnRequiredScalarReference() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bond-nullify-required-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "bond.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "productId",
                          "type": "reference",
                          "required": true,
                          "reference": {
                            "target": "Product",
                            "onDelete": "nullify"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(
                errors.stream().anyMatch(e -> e.contains("onDelete=nullify is invalid on a required field")),
                "Expected required nullify error, got: " + errors
        );
    }

    @Test
    void validatorRejectsNullifyOnMultipleReference() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bond-nullify-many-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "bond.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Bundle",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "productIds",
                          "type": "reference",
                          "reference": {
                            "target": "Product",
                            "multiple": true,
                            "onDelete": "nullify"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);

        assertTrue(
                errors.stream().anyMatch(e -> e.contains("onDelete=nullify is invalid on a multiple (N:M) bond")),
                "Expected multiple nullify error, got: " + errors
        );
    }

    @Test
    void schemaRejectsUnknownOnDeletePolicyAtParseTime() throws Exception {
        Path modelPath = Files.createTempFile("npdev-bond-ondelete-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "bond.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true }
                      ]
                    },
                    {
                      "name": "Invoice",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "productId",
                          "type": "reference",
                          "reference": { "target": "Product", "onDelete": "burn" }
                        }
                      ]
                    }
                  ]
                }
                """);

        org.junit.jupiter.api.Assertions.assertThrows(
                Exception.class,
                () -> new JsonModelParser().parse(modelPath),
                "Expected schema validation to reject an unknown onDelete policy at parse time."
        );
    }

    @Test
    void crossPackBondPassesSemanticValidation() throws Exception {
        // BOND-B6: a bond to a pack-namespaced concept (catalog::Product) must validate cleanly
        // through the full JSON -> parse (with pack resolution) -> SemanticValidator pipeline, not
        // just at the pre-built CompiledConcept level.
        Path tempDir = Files.createTempDirectory("npdev-cross-pack-");
        Path packsDir = tempDir.resolve("packs");
        Files.createDirectories(packsDir);
        Files.writeString(packsDir.resolve("catalog.json"), """
                {
                  "pack": "catalog",
                  "version": "1.0.0",
                  "dslVersion": "1.0.0",
                  "concepts": [
                    {
                      "name": "Product",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "skuId", "type": "string", "unique": true, "connectable": "anchor" }
                      ]
                    }
                  ]
                }
                """);
        Path modelFile = tempDir.resolve("model.json");
        Files.writeString(modelFile, """
                {
                  "namespace": "order.app",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "packs": [{ "$ref": "packs/catalog.json" }],
                  "concepts": [
                    {
                      "name": "Order",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        {
                          "name": "productId",
                          "type": "reference",
                          "required": true,
                          "reference": {
                            "target": "catalog::Product",
                            "via": "skuId",
                            "onDelete": "restrict"
                          }
                        }
                      ]
                    }
                  ]
                }
                """);

        ModelAst model = new JsonModelParser().parse(modelFile);
        var result = new SemanticValidator().validateWithWarnings(model);

        assertTrue(!result.hasErrors(),
                "Cross-pack bond with valid anchor should pass validation. Errors: " + result.getErrors());
    }
}
