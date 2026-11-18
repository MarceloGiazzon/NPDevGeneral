package com.npdev.dsl.v1;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledGuidePage;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJsonReader;
import com.npdev.dsl.v1.compiled.GuidePageDefaults;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.DeprecationException;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.parser.ModelSourceResolver;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.dsl.v1.validation.SemanticValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FORM P5-T9c: the old top-level {@code appShell} + {@code guidePages} are ONE optional root {@code backOffice}
 * ({@code defaultRoute}, {@code navigation}, {@code pages}), independent of experiences and roles, and a pack
 * contributes its pages through a pack-level {@code backOffice.pages}. The compiled IR ({@code CompiledAppShell},
 * {@code CompiledGuidePage}) must be exactly what the old keys produced, pack-qualified names included.
 */
class BackOfficeConfigThreadingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String BASE = """
              "namespace": "backoffice.demo",
              "dslVersion": "3.0.0",
              "version": "1.0",
              "concepts": [
                { "name": "Widget", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "queries": [
                { "name": "WidgetCount", "concept": "Widget",
                  "aggregates": [ { "name": "total", "fn": "count" } ] }
              ]""";

    @TempDir
    Path temp;

    private static String model(String extraMembers) {
        return "{\n" + BASE + (extraMembers.isEmpty() ? "" : ",\n" + extraMembers) + "\n}";
    }

    /** The mandatory minimal model: one concept, no roles, no experiences, no backOffice. */
    @Test
    void aMinimalModelWithNoRolesExperiencesOrBackOfficeParsesCompilesAndKeepsTheDefaults() throws Exception {
        String json = """
                { "namespace": "backoffice.minimal", "dslVersion": "3.0.0", "version": "1.0",
                  "concepts": [ { "name": "Note", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ] }
                """;
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        assertEquals(List.of(), new SemanticValidator().validate(ast));
        assertNull(ast.getAppShell());
        assertTrue(ast.getGuidePages().isEmpty());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertNull(compiled.getAppShell(), "no shell is synthesized: the UI derives its menu from the concepts");
        assertTrue(compiled.getGuidePages().isEmpty());
        // ... and the generator still shows the three built-in guide pages for it
        List<String> builtIns = GuidePageDefaults.withBuiltins(compiled.getGuidePages()).guidePages().stream()
                .map(CompiledGuidePage::name).toList();
        assertTrue(builtIns.containsAll(List.of("Default", "Minimal", "None")), builtIns.toString());

        Path file = write("minimal.json", json);
        assertNull(new JsonModelParser().parse(file).getAppShell(), "the resolver path agrees with the JsonNode path");
    }

    @Test
    void shellAndPagesCompileToTheSameIrAsTheOldKeys() throws Exception {
        String json = model("""
                  "backOffice": {
                    "defaultRoute": "Widget",
                    "navigation": [ { "label": "Widgets", "target": "Widget", "group": "Ops" }, { "label": "Reference" } ],
                    "pages": [ { "name": "Overview", "default": true,
                                 "gadgets": [ { "name": "k", "type": "kpi", "query": "WidgetCount", "y": "total" } ] } ]
                  }""");
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(json));
        assertEquals(List.of(), new SemanticValidator().validate(ast));
        assertEquals("Widget", ast.getAppShell().getDefaultRoute());
        assertEquals(2, ast.getAppShell().getNavigation().size());
        assertEquals(List.of("Overview"), ast.getGuidePages().stream().map(p -> p.name()).toList());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals("Widget", compiled.getAppShell().getDefaultRoute());
        assertEquals("Overview", compiled.getGuidePages().get(0).name());

        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        assertEquals("Widget", restored.getAppShell().getDefaultRoute());
        assertEquals(1, restored.getGuidePages().size());
    }

    @Test
    void pagesOnlyCreateNoShell() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model("\"backOffice\": { \"pages\": [ { \"name\": \"First\" } ] }")));
        assertNull(ast.getAppShell());
        assertEquals(1, ast.getGuidePages().size());
    }

    @Test
    void anEmptyBackOfficeBehavesLikeNone() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model("\"backOffice\": {}")));
        assertNull(ast.getAppShell());
        assertTrue(ast.getGuidePages().isEmpty());
    }

    @Test
    void backOfficeDoesNotNeedRolesOrExperiencesAndExperienceBackOfficeStaysABoolean() throws Exception {
        String withExperience = """
                "roles": [ { "name": "Operator", "grants": ["EXECUTE_FLOW"], "concepts": { "Widget": ["read"] } } ],
                "screens": [ { "name": "home", "kind": "list", "concept": "Widget" } ],
                "experiences": [ { "name": "ops", "for": ["Operator"], "home": "home", "backOffice": true } ],
                "backOffice": { "defaultRoute": "Widget" }""";
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model(withExperience)));
        assertEquals(List.of(), new SemanticValidator().validate(ast));
        assertEquals(true, ast.getExperiences().get(0).get("backOffice").asBoolean());
        assertEquals("Widget", ast.getAppShell().getDefaultRoute());
    }

    /** FORM P5-T9g: locale + strings live in the optional root backOffice (no roles or experiences needed). */
    @Test
    void backOfficeLocaleAndStringsCompileToTheAppsCatalogueAndRoundTrip() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model(
                "\"backOffice\": { \"locale\": \"pt-BR\", \"strings\": { \"action.save\": \"Gravar\" } }")));
        assertEquals(List.of(), new SemanticValidator().validate(ast));
        assertNull(ast.getAppShell(), "locale/strings alone create no shell");
        assertEquals("pt-BR", ast.getSettings().getLocale());

        CompiledModel compiled = new ModelCompiler().compile(ast);
        assertEquals("pt-BR", compiled.getSettings().getLocale());
        assertEquals("Gravar", compiled.getSettings().resolveString("action.save"));
        assertNotNull(compiled.getSettings().resolveString("action.cancel"), "an id not overridden keeps the platform default");

        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(CompiledModelCanonicalJson.toJson(compiled));
        assertEquals("Gravar", restored.getSettings().resolveString("action.save"));
        assertFalse(CompiledModelCanonicalJson.toJson(compiled).contains("pageRows"), "the dead settings.ui sub-keys are gone from the IR");
    }

    @Test
    void aModelDeclaringNeitherLocaleNorStringsKeepsThePlatformCatalogue() throws Exception {
        ModelAst ast = new JsonModelParser().parse(MAPPER.readTree(model("\"backOffice\": { \"defaultRoute\": \"Widget\" }")));
        assertNull(ast.getSettings());
        assertNull(new ModelCompiler().compile(ast).getSettings().getLocale());
    }

    @Test
    void theRemovedTopLevelKeysAreRefusedWithAPointerToBackOffice() throws Exception {
        for (String key : List.of("\"appShell\": { \"defaultRoute\": \"Widget\" }", "\"guidePages\": [ { \"name\": \"P\" } ]",
                "\"settings\": { \"locale\": \"pt-BR\" }")) {
            String json = model(key);
            DeprecationException refused = assertThrows(DeprecationException.class,
                    () -> new JsonModelParser().parse(MAPPER.readTree(json)), key);
            assertTrue(refused.getMessage().contains("backOffice"), refused.getMessage());
            Path file = write("removed-" + Math.abs(key.hashCode()) + ".json", json);
            assertThrows(DeprecationException.class, () -> new JsonModelParser().parse(file), "parse(Path) " + key);
        }
    }

    @Test
    void aPagesRefIsIncludedLikeAnyOtherArrayEntry() throws Exception {
        write("pages/extra.json", "{ \"name\": \"Included\", \"gadgets\": [] }");
        Path model = write("model.json", model("\"backOffice\": { \"pages\": [ { \"name\": \"Own\" }, { \"$ref\": \"pages/extra.json\" } ] }"));
        ModelAst ast = new JsonModelParser().parse(model);
        assertEquals(List.of("Own", "Included"), ast.getGuidePages().stream().map(p -> p.name()).toList());
    }

    // ------------------------------------------------------------------ packs

    private static final String PACK = """
            {
              "dslVersion": "3.0.0",
              "pack": "support",
              "version": "1.0.0",
              "concepts": [
                { "name": "Ticket", "access": { "read": "true", "write": "true" },
                  "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
              ],
              "queries": [ { "name": "TicketCount", "concept": "Ticket", "aggregates": [ { "name": "total", "fn": "count" } ] } ],
              "backOffice": { "pages": [
                { "name": "TicketOverview", "gadgets": [ { "name": "k", "type": "kpi", "query": "TicketCount", "y": "total" } ] }
              ] }
            }
            """;

    @Test
    void aPacksPagesKeepTheirQualifiedNameAndQueryBindingAndFollowTheModelsOwnPages() throws Exception {
        write("packs/support/pack.json", PACK);
        Path model = write("model.json", model("""
                  "packs": [ { "$ref": "packs/support/pack.json" } ],
                  "backOffice": { "defaultRoute": "Widget", "pages": [ { "name": "Own" } ] }"""));
        ResolvedModelSource resolved = new ModelSourceResolver().resolve(model);
        JsonNode root = resolved.resolvedRoot();
        assertFalse(root.has("guidePages"), "the composed model.json is authoring-shaped: no top-level guidePages");
        assertEquals("Widget", root.get("backOffice").get("defaultRoute").asText());
        JsonNode pages = root.get("backOffice").get("pages");
        assertEquals("Own", pages.get(0).get("name").asText());
        assertEquals("support::TicketOverview", pages.get(1).get("name").asText());
        assertEquals("support::TicketCount", pages.get(1).get("gadgets").get(0).get("query").asText());

        ModelAst ast = new JsonModelParser().parse(resolved);
        assertEquals(List.of(), new SemanticValidator().validate(ast));
        List<CompiledGuidePage> compiled = new ModelCompiler().compile(ast).getGuidePages();
        assertEquals(List.of("Own", "support::TicketOverview"), compiled.stream().map(CompiledGuidePage::name).toList());

        // The composed JSON re-parses to the same pages (idempotent): nothing is lifted twice.
        ModelAst again = new JsonModelParser().parse(root);
        assertEquals(ast.getGuidePages().stream().map(p -> p.name()).toList(),
                again.getGuidePages().stream().map(p -> p.name()).toList());
    }

    @Test
    void aModelWithNoBackOfficeStillGetsItsPacksPagesNoRolesOrExperiencesNeeded() throws Exception {
        write("packs/support/pack.json", PACK);
        Path model = write("model.json", """
                {
                  "namespace": "backoffice.packonly",
                  "dslVersion": "3.0.0",
                  "version": "1.0",
                  "packs": [ { "$ref": "packs/support/pack.json" } ]
                }
                """);
        ResolvedModelSource resolved = new ModelSourceResolver().resolve(model);
        assertEquals("support::TicketOverview", resolved.resolvedRoot().get("backOffice").get("pages").get(0).get("name").asText());
        assertTrue(resolved.warnings().isEmpty(), resolved.warnings().toString());
        ModelAst ast = new JsonModelParser().parse(resolved);
        assertEquals(List.of("support::TicketOverview"), ast.getGuidePages().stream().map(p -> p.name()).toList());
        assertNull(ast.getAppShell());
    }

    @Test
    void aPackDeclaringTheRemovedGuidePagesKeyIsRefused() throws Exception {
        write("packs/support/pack.json", PACK.replace("\"backOffice\": { \"pages\": [", "\"guidePages\": [ \n")
                .replace("] }\n}", "]\n}"));
        Path model = write("model.json", "{ \"namespace\": \"x.y\", \"dslVersion\": \"3.0.0\", \"version\": \"1.0\", "
                + "\"packs\": [ { \"$ref\": \"packs/support/pack.json\" } ] }");
        assertThrows(IOException.class, () -> new ModelSourceResolver().resolve(model));
    }

    private Path write(String relative, String content) throws IOException {
        Path target = temp.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
        return target;
    }
}
