package com.finalexec.npdev.service;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;
import com.npdev.dsl.v1.validation.ValidationLayer;
import com.npdev.dsl.v1.validation.ValidationResult;
import com.npdev.dsl.v1.validation.ValidationSeverity;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RuntimeMetadataValidationService {

    private static final String VALIDATION_CONTRACT = "validation-diagnostic-v1";
    private static final String SOURCE_MODULE = "runtime:metadata-validation";

    public Map<String, Object> validate(String modelJson) {
        if (modelJson == null || modelJson.isBlank()) {
            return buildResponse(ValidationResult.fromDiagnostics(List.of(
                    new ValidationDiagnostic(
                            ValidationLayer.STRUCTURAL,
                            ValidationSeverity.ERROR,
                            "missing_model_payload",
                            "Model JSON payload is required.",
                            SOURCE_MODULE,
                            "$",
                            null,
                            null,
                            "root",
                            null,
                            "Send the current model.json document in the request body.",
                            "validation.structural.missing_model_payload"
                    )
            )));
        }

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("npdev-runtime-validation-", ".json");
            Files.writeString(tempFile, modelJson, StandardCharsets.UTF_8);

            JsonModelParser parser = new JsonModelParser();
            ModelAst modelAst = parser.parse(tempFile);
            ValidationResult result = new SemanticValidator().validateWithWarnings(modelAst);
            return buildResponse(result);
        } catch (IOException ex) {
            return buildResponse(ValidationResult.fromDiagnostics(List.of(
                    new ValidationDiagnostic(
                            ValidationLayer.STRUCTURAL,
                            ValidationSeverity.ERROR,
                            "invalid_model_json",
                            ex.getMessage(),
                            SOURCE_MODULE,
                            "$",
                            null,
                            null,
                            "root",
                            null,
                            "Fix the JSON/schema issue in the current draft and try validation again.",
                            "validation.structural.invalid_model_json"
                    )
            )));
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup for transient validation temp files.
                }
            }
        }
    }

    private Map<String, Object> buildResponse(ValidationResult result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("contract", VALIDATION_CONTRACT);
        response.put("valid", !result.hasErrors());
        response.put("errorCount", result.getErrors().size());
        response.put("warningCount", result.getWarnings().size());
        response.put("diagnostics", result.getDiagnostics().stream().map(this::toDiagnosticMap).toList());
        return response;
    }

    private Map<String, Object> toDiagnosticMap(ValidationDiagnostic diagnostic) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("layer", diagnostic.getLayer().getExternalName());
        payload.put("severity", diagnostic.getSeverity().getExternalName());
        payload.put("code", diagnostic.getCode());
        payload.put("message", diagnostic.getMessage());
        payload.put("sourceModule", diagnostic.getSourceModule());
        payload.put("path", diagnostic.getPath());
        payload.put("concept", diagnostic.getConcept());
        payload.put("field", diagnostic.getField());
        payload.put("section", diagnostic.getSection());
        payload.put("ruleName", diagnostic.getRuleName());
        payload.put("suggestedFix", diagnostic.getSuggestedFix());
        payload.put("helpKey", diagnostic.getHelpKey());
        return payload;
    }
}
