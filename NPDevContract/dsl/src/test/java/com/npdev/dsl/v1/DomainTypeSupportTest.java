package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledDomainType;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainTypeSupportTest {

    @Test
    void parserValidatorAndCompilerSupportReusableDomainTypes() throws Exception {
        Path modelPath = Files.createTempFile("npdev-domain-types-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "domain.types.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "domainTypes": [
                    {
                      "name": "MRN",
                      "baseType": "string",
                      "validation": {
                        "type": "string",
                        "minLength": 8,
                        "maxLength": 12,
                        "regex": "^[A-Z0-9-]+$"
                      },
                      "normalization": ["trim", "uppercase"],
                      "format": "medical-record-number",
                      "examples": ["MRN-000123"],
                      "ui": {
                        "label": "Medical record number",
                        "widget": "text"
                      }
                    }
                  ],
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "mrn", "type": "string", "domainType": "MRN", "required": true }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        assertEquals(1, ast.getDomainTypes().size(), "Expected one parsed domain type.");
        assertEquals("MRN", ast.getDomainTypes().get(0).getName());
        assertEquals("MRN", ast.getConcepts().get(0).getFields().get(1).getDomainType());

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected semantic validation to accept domain type usage, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals(1, compiled.getDomainTypes().size(), "Expected one compiled domain type.");
        CompiledDomainType compiledDomainType = compiled.getDomainTypes().get(0);
        assertEquals("MRN", compiledDomainType.getName());
        assertEquals("string", compiledDomainType.getBaseType());
        assertEquals("medical-record-number", compiledDomainType.getFormatHint());
        assertNotNull(compiledDomainType.getValidationSchema());
        assertEquals("^[A-Z0-9-]+$", compiledDomainType.getValidationSchema().getRegex());

        CompiledField mrnField = compiled.findConcept("Patient")
                .orElseThrow()
                .getFields()
                .stream()
                .filter(field -> "mrn".equals(field.getName()))
                .findFirst()
                .orElseThrow();
        assertEquals("MRN", mrnField.getDomainType());
        assertNotNull(mrnField.getSchema(), "Expected effective field schema merged from domain type.");
        assertEquals("^[A-Z0-9-]+$", mrnField.getSchema().getRegex());
        assertEquals(8, mrnField.getSchema().getMinLength());
    }

    @Test
    void semanticValidationRejectsUnknownDomainTypeReferences() throws Exception {
        Path modelPath = Files.createTempFile("npdev-domain-types-error-", ".json");
        Files.writeString(modelPath, """
                {
                  "namespace": "domain.types.demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    {
                      "name": "Patient",
                      "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "mrn", "type": "string", "domainType": "UnknownDomainType" }
                      ]
                    }
                  ]
                }
                """);

        ModelAst ast = new JsonModelParser().parse(modelPath);
        List<String> errors = new SemanticValidator().validate(ast);
        assertFalse(errors.isEmpty(), "Expected semantic validation errors for unknown domain type.");
        assertTrue(
                errors.stream().anyMatch(error -> error.contains("domain type not found")),
                "Expected domain type resolution error, got: " + errors
        );
    }
}

