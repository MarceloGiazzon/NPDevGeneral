package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.dsl.v1.validation.ValidationResult;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-11 (Session 3b): the jwt-mode login/signup screen -- emitted only for jwt-mode apps, carries
 * the Continue-with-Google surface (gated on the server's OAuth config probe), logs the user in
 * against the same /api/auth/login endpoint and storage keys the SPA shell reads, and maps the
 * OAuth callback's error codes to business language. Also pins the manifest default: a jwt-mode app
 * with no hand-authored login path now points the shell's unauthenticated redirect at this page.
 */
class LoginPageEmitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
            {
              "namespace": "login.demo",
              "dslVersion": "1.0.0",
              "version": "1.0",
              "concepts": [
                {
                  "name": "Widget",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "name", "type": "string", "required": true }
                  ]
                }
              ]
            }
            """;

    @Test
    void jwtModeEmitsLoginPageWithOAuthSurface(@TempDir Path tempDir) throws Exception {
        Path out = emitPage(tempDir, "Login Demo", true);
        Path page = out.resolve("src/main/resources/static/login.html");
        assertTrue(Files.isRegularFile(page));
        String html = Files.readString(page);
        assertTrue(html.contains("Login Demo"), "the page must carry the app name");
        assertTrue(html.contains("Continue with \" + p.label"),
                "one button per server-reported provider, built from its label, not a hardcoded provider name");
        assertTrue(html.contains("google:") && html.contains("github:"),
                "both providers this platform ships an adapter for get an icon, regardless of which are configured");
        assertTrue(html.contains("/api/auth/oauth/config"), "the buttons must be gated on the server's config probe");
        assertTrue(html.contains("p.authorizePath") && html.contains("purpose=login"),
                "each button must start the authorize round trip against its own server-provided authorize path");
        assertTrue(html.contains("/api/auth/login"), "the password form must post to the platform login endpoint");
        assertTrue(html.contains("npdev.shell.token"), "a successful login must write the shell's own token key");
        assertTrue(html.contains("email_exists_requires_link"),
                "the settled collision policy must surface in business language on the page");
    }

    @Test
    void apiKeyModeEmitsNoLoginPage(@TempDir Path tempDir) throws Exception {
        Path out = emitPage(tempDir, "Login Demo", false);
        assertFalse(Files.exists(out.resolve("src/main/resources/static/login.html")),
                "apiKey apps sign in through the inline key field; no login screen is emitted");
    }

    @Test
    void blankAppNameFallsBackToPlatformName(@TempDir Path tempDir) throws Exception {
        Path out = emitPage(tempDir, "   ", true);
        assertTrue(Files.readString(out.resolve("src/main/resources/static/login.html")).contains("NPDev Generated App"));
    }

    @Test
    void jwtModeWithoutHandAuthoredLoginPathDefaultsToTheEmittedPage(@TempDir Path tempDir) throws Exception {
        Path out = emitBusinessUi(tempDir, jwtStore(null));
        JsonNode manifest = MAPPER.readTree(
                out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json").toFile());
        assertEquals("jwt", manifest.path("auth").path("mode").asText());
        assertEquals("/login.html", manifest.path("auth").path("loginPath").asText(),
                "a jwt-mode app without a hand-authored login path must point the shell at the emitted page");
    }

    @Test
    void handAuthoredLoginPathIsKept(@TempDir Path tempDir) throws Exception {
        Path out = emitBusinessUi(tempDir, jwtStore("/custom-login"));
        JsonNode manifest = MAPPER.readTree(
                out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json").toFile());
        assertEquals("/custom-login", manifest.path("auth").path("loginPath").asText());
    }

    // ---------------------------------------------------------------- helpers

    private static Path emitPage(Path tempDir, String appName, boolean jwtMode) throws Exception {
        Path out = Files.createTempDirectory("npdev-login-page-out-");
        new LoginPageEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy())).emit(appName, jwtMode);
        return out;
    }

    private static Path emitBusinessUi(Path tempDir, SettingStore settings) throws Exception {
        Path modelPath = tempDir.resolve("model.json");
        Files.writeString(modelPath, MODEL);
        ModelAst ast = new JsonModelParser().parse(modelPath);
        ValidationResult validation = new SemanticValidator().validateWithWarnings(ast);
        assertTrue(validation.getErrors().isEmpty(), "expected no validation errors, got: " + validation.getErrors());
        CompiledModel compiled = new ModelCompiler().compile(ast);

        Path out = Files.createTempDirectory("npdev-login-manifest-out-");
        new BusinessUiEmitter(new TemplateEngine("npdev-templates/"),
                new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(compiled, "ADMIN", new SettingResolver(settings));
        return out;
    }

    private static SettingStore jwtStore(String loginPath) {
        SettingStore.Builder builder = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.AUTH_MODE.id(), "jwt"), "test settings");
        if (loginPath != null) {
            builder.layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                    Map.of(NpdevSettings.AUTH_LOGIN_PATH.id(), loginPath), "test settings");
        }
        return builder.build();
    }
}