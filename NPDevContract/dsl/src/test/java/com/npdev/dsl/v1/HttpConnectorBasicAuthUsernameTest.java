package com.npdev.dsl.v1;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An outside API that wants a username and a password (HTTP basic) needs two environment variable NAMES, one for each
 * half; the secret holds the password and {@code usernameEnvVar} the username. A connector that declares basic
 * authentication but names no username variable could never sign a call, so the validator must refuse it at model
 * time and accept it once the name is given.
 */
class HttpConnectorBasicAuthUsernameTest {

    private static final String MODEL = """
            {
              "namespace": "basic.auth.demo",
              "dslVersion": "3.0.0",
              "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [{ "name": "id", "type": "uuid", "id": true, "required": true }] }
              ],
              "capabilities": [
                { "name": "ledger", "type": "HttpConnector", "adapter": "http-connector",
                  "http": { "baseUrl": "https://api.example.com/v1",
                            "auth": { %s "secretEnvVar": "LEDGER_PASSWORD" } },
                  "operations": [ { "name": "ping", "http": { "method": "GET", "path": "/ping" } } ] }
              ]
            }
            """;

    private static ValidationResult validate(Path dir, String authPrefix) throws Exception {
        Path modelPath = dir.resolve("model.json");
        Files.writeString(modelPath, MODEL.formatted(authPrefix));
        ModelAst ast = new JsonModelParser().parse(modelPath);
        return new SemanticValidator().validateWithWarnings(ast);
    }

    @Test
    void basicAuthWithoutUsernameVariableIsRefused(@TempDir Path tempDir) throws Exception {
        ValidationResult result = validate(tempDir, "\"type\": \"basic\",");
        assertTrue(result.getErrors().stream().anyMatch(error -> error.contains("usernameEnvVar")),
                "expected an error naming usernameEnvVar, got: " + result.getErrors());
    }

    @Test
    void basicAuthWithUsernameVariableIsAccepted(@TempDir Path tempDir) throws Exception {
        ValidationResult result = validate(tempDir, "\"type\": \"basic\", \"usernameEnvVar\": \"LEDGER_USER\",");
        assertTrue(result.getErrors().isEmpty(), "expected no validation errors, got: " + result.getErrors());
    }
}
