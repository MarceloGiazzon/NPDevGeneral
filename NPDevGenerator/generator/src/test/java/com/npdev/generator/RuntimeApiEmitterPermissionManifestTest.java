package com.npdev.generator;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeApiEmitterPermissionManifestTest {

    @Test
    void shouldEmitGeneratedPermissionManifestAndRuntimeWiring() throws Exception {
        Path model = Path.of("..", "test-models", "user-minimal", "model.json").normalize();

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(model);
        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);
        Path out = Files.createTempDirectory("npdev-runtime-permission-");
        Path migrations = Files.createTempDirectory("npdev-runtime-permission-migrations-");

        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        new GeneratorFacade(templates, writer).generate(compiled, out, migrations, model);

        Path runtimeConfig = out.resolve("src/main/java/com/npdev/generated/runtime/config/NPDevRuntimeConfig.java");
        Path permissionLoader = out.resolve("src/main/java/com/npdev/generated/runtime/config/GeneratedPermissionManifestLoader.java");
        Path permissionManifest = out.resolve("src/main/resources/npdev/security/dev.permissions.json");
        Path uiPolicyManifest = out.resolve("src/main/resources/npdev/security/dev.ui-metadata-policy.json");

        assertTrue(Files.exists(runtimeConfig), "Expected generated runtime config");
        assertTrue(Files.exists(permissionLoader), "Expected generated permission manifest loader");
        assertTrue(Files.exists(permissionManifest), "Expected generated permission manifest resource");
        assertTrue(Files.exists(uiPolicyManifest), "Expected generated UI metadata policy manifest resource");

        String runtimeConfigContent = Files.readString(runtimeConfig);
        String permissionLoaderContent = Files.readString(permissionLoader);
        String permissionManifestContent = Files.readString(permissionManifest);
        String uiPolicyManifestContent = Files.readString(uiPolicyManifest);

        assertTrue(runtimeConfigContent.contains("@Configuration"),
                "Expected generated runtime config marker");
        assertTrue(runtimeConfigContent.contains("class NPDevRuntimeConfig"),
                "Expected generated runtime config class");
        assertTrue(permissionLoaderContent.contains("npdev/security/dev.permissions.json"),
                "Expected generated loader to read runtime permission manifest from classpath");
        assertTrue(permissionManifestContent.contains("flow.execute"),
                "Expected generated permission manifest to contain flow execution grant");
        assertTrue(permissionManifestContent.contains("\"tenantId\": \"dev\""),
                "Expected generated permission manifest to target the dev runtime tenant");
        assertTrue(permissionManifestContent.contains("create:user"),
                "Expected generated permission manifest to grant create on the persisted User concept");
        assertTrue(permissionManifestContent.contains("update:user"),
                "Expected generated permission manifest to grant update on the persisted User concept");
        assertTrue(permissionManifestContent.contains("delete:user"),
                "Expected generated permission manifest to grant delete on the persisted User concept");
        assertTrue(uiPolicyManifestContent.contains("\"policyVersion\": \"1.0.0\""),
                "Expected generated UI metadata policy manifest version");
    }
}
