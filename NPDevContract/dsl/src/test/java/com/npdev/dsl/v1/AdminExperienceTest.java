package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledGuidePage;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.DeprecationException;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-1 phase 4: the generated back office is the admin experience -- an {@code experiences[]} entry with an {@code admin}
 * block ({@code locale, strings, defaultRoute, navigation, pages}), what the root {@code backOffice} key used to hold. It is
 * not a role experience, there is at most one, a pack declares only its pages, and the old key is refused with the
 * replacement named.
 */
class AdminExperienceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CONCEPTS = """
        "concepts": [ { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ]""";

    private static String model(String extra) {
        return "{ \"dslVersion\": \"3.0.0\", \"namespace\": \"d1.admin\", \"version\": \"1.0\", " + CONCEPTS + ", " + extra + " }";
    }

    @Test
    void anAdminExperienceConfiguresTheBackOfficeAndIsNotARoleExperience() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model(
                "\"experiences\": [ { \"name\": \"admin\", \"admin\": { \"defaultRoute\": \"Widget\", \"locale\": \"pt-BR\" } } ]")));
        assertEquals(List.of(), new SemanticValidator().validate(ast));
        assertEquals("Widget", ast.getAppShell().getDefaultRoute());
        assertEquals("pt-BR", ast.getSettings().getLocale());
        assertTrue(ast.getExperiences().isEmpty(), "the admin entry never reaches the role-experience list");
    }

    @Test
    void aModelWithoutAnAdminExperienceHasNoShellSettings() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model("\"roles\": []")));
        assertNull(ast.getAppShell());
        assertNull(ast.getSettings());
    }

    @Test
    void theRootBackOfficeKeyIsRefusedNamingTheReplacement() throws Exception {
        JsonNode old = MAPPER.readTree(model("\"backOffice\": { \"defaultRoute\": \"Widget\" }"));
        IOException refusal = assertThrows(IOException.class, () -> new JsonModelParser().parse(old));
        assertTrue(refusal.getMessage().contains("'backOffice' was removed") && refusal.getMessage().contains("admin"), refusal.getMessage());
        assertTrue(refusal instanceof DeprecationException
                && ((DeprecationException) refusal).getDiagnostic().getSuggestedFix().contains("\"admin\""),
                "the suggested fix spells out the replacement");
    }

    @Test
    void anAdminEntryCarriesNoRoleExperienceKeys() throws Exception {
        assertThrows(Exception.class, () -> new JsonModelParser().parse(MAPPER.readTree(model(
                "\"experiences\": [ { \"name\": \"admin\", \"for\": [\"x\"], \"admin\": { \"defaultRoute\": \"Widget\" } } ]"))));
    }

    @Test
    void moreThanOneAdminEntryIsRefused(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("model.json");
        Files.writeString(app, model("\"experiences\": [ { \"name\": \"admin\", \"admin\": { \"defaultRoute\": \"Widget\" } }, "
                + "{ \"name\": \"admin2\", \"admin\": { \"locale\": \"pt-BR\" } } ]"));
        IOException refusal = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(app));
        assertTrue(refusal.getMessage().contains("more than one experiences[] entry carries an `admin` block"), refusal.getMessage());
    }

    @Test
    void aPacksAdminEntryContributesQualifiedPagesAndNothingElse(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("packs/support"));
        Files.writeString(dir.resolve("packs/support/pack.json"), "{ \"dslVersion\": \"3.0.0\", \"pack\": \"support\", \"version\": \"1.0.0\", "
                + CONCEPTS + ", \"experiences\": [ { \"name\": \"admin\", \"admin\": { \"pages\": [ { \"name\": \"Overview\" } ] } } ] }");
        Path app = dir.resolve("model.json");
        Files.writeString(app, "{ \"dslVersion\": \"3.0.0\", \"namespace\": \"d1.app\", \"version\": \"1.0\", "
                + "\"packs\": [ { \"$ref\": \"packs/support/pack.json\", \"as\": \"support\" } ] }");
        var resolved = new ModelSourceResolver().resolve(app);
        assertFalse(resolved.resolvedRoot().has("backOffice"), "composed JSON is authoring-shaped");
        JsonNode admin = null;
        for (JsonNode experience : resolved.resolvedRoot().path("experiences")) {
            if (experience.has("admin")) {
                admin = experience.get("admin");
            }
        }
        assertEquals("support::Overview", admin.get("pages").get(0).get("name").asText());
        ModelAst ast = new JsonModelParser().parse(resolved);
        assertEquals(List.of("support::Overview"), new ModelCompiler().compile(ast).getGuidePages().stream().map(CompiledGuidePage::name).toList());
        assertTrue(ast.getExperiences().isEmpty());

        Files.writeString(dir.resolve("packs/support/pack.json"), "{ \"dslVersion\": \"3.0.0\", \"pack\": \"support\", \"version\": \"1.0.0\", "
                + CONCEPTS + ", \"experiences\": [ { \"name\": \"admin\", \"admin\": { \"locale\": \"pt-BR\", \"pages\": [ ] } } ] }");
        IOException refusal = assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(app));
        assertTrue(refusal.getMessage().contains("may only declare `pages`"), refusal.getMessage());
    }
}
