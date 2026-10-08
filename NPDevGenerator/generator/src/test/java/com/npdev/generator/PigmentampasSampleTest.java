package com.npdev.generator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.pack.PackCache;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.validation.SemanticValidator;
import com.npdev.generator.api.GeneratorFacade;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.settings.ConfigSettingsReader;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S6 (NPDEV_MEGA_ROADMAP.md): pins the Pigmentampas sample -- the beer-cap mosaic studio (rebuilt 2026-10-07).
 * The model must parse, pass the semantic validator, compile with all seven concepts, and generate
 * without error through the real GeneratorFacade pipeline.
 */
class PigmentampasSampleTest {

    private static final Path SAMPLE_MODEL = Path.of(
            "..", "..", "NPDevSamples", "pigmentampas", "Input", "model.json").normalize();

    private static String previousCacheRoot;
    private static String previousMirrors;

    /** A fresh machine: an empty pack cache, and the repo's own NPDevContract/packs as the offline
     *  mirror (REG-250). The committed npdev.lock is read as-is -- never rewritten. */
    @BeforeAll
    static void startFromAnEmptyPackCache() throws Exception {
        previousCacheRoot = System.getProperty(PackCache.PROPERTY_ROOT_OVERRIDE);
        previousMirrors = System.getProperty(PackCache.PROPERTY_MIRRORS);
        System.setProperty(PackCache.PROPERTY_ROOT_OVERRIDE,
                Files.createTempDirectory("npdev-pigmentampas-pack-cache-").toString());
        System.setProperty(PackCache.PROPERTY_MIRRORS,
                Path.of("..", "..", "NPDevContract", "packs").toAbsolutePath().normalize().toString());
    }

    @AfterAll
    static void restorePackCacheProperties() {
        restore(PackCache.PROPERTY_ROOT_OVERRIDE, previousCacheRoot);
        restore(PackCache.PROPERTY_MIRRORS, previousMirrors);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void pigmentampasModelParsesValidatesAndGenerates() throws Exception {
        assertTrue(Files.exists(SAMPLE_MODEL),
                "Expected Pigmentampas sample model at: " + SAMPLE_MODEL.toAbsolutePath());

        JsonModelParser parser = new JsonModelParser();
        ModelAst ast = parser.parse(SAMPLE_MODEL);

        List<String> errors = new SemanticValidator().validate(ast);
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: " + errors);

        CompiledModel compiled = new ModelCompiler().compile(ast);

        CompiledConcept mosaic = compiled.findConcept("Mosaic").orElse(null);
        assertNotNull(mosaic, "Mosaic concept must compile");
        // PACK-23: identity/tracing/workspace are consumed by remote coordinate (S6's own design),
        // so this counts the REAL published packs' full concept sets, not a locally-trimmed stand-in:
        // 10 domain concepts (Member, Brewery, Cap, CollectionItem, Mosaic, MosaicCell, Credential,
        // Trade, Like, Challenge) + identity 1.2.0 (User, Role, UserRole, PasswordResetToken,
        // UserRolePermission, ExternalIdentity = 6) + tracing 1.0.2 (TraceEntry = 1) + workspace 1.1.0
        // (Menu, PropertyValue = 2) = 19.
        assertEquals(19, compiled.getConcepts().size(),
                "Pigmentampas must compile its 10 domain concepts plus the full identity+tracing+workspace pack sets (9)");

        Path out = Files.createTempDirectory("npdev-pigmentampas-");
        Path migrations = Files.createTempDirectory("npdev-pigmentampas-migrations-");
        TemplateEngine templates = new TemplateEngine("npdev-templates/");
        GeneratedSourceWriter writer = new GeneratedSourceWriter(out, new RegenerationPolicy());
        // The sample's own config.json, as GeneratorMain reads it: agentAccess.channels.telegram is
        // only legal under its defaults.auth.mode=jwt, which an empty SettingStore never supplies.
        SettingResolver settings = new SettingResolver(new ConfigSettingsReader().read(
                new ObjectMapper().readTree(SAMPLE_MODEL.resolveSibling("config.json").toFile())));
        new GeneratorFacade(templates, writer, settings).generate(compiled, out, migrations, SAMPLE_MODEL);

        for (String entity : List.of("Member", "Brewery", "Cap", "CollectionItem",
                "Mosaic", "MosaicCell")) {
            Path java = out.resolve("src/main/java/com/npdev/generated/entities/" + entity + ".java");
            assertTrue(Files.exists(java), "Expected generated entity: " + java);
        }
        Path uiManifest = out.resolve("src/main/resources/static/npdev-business-ui/generated-ui-manifest.json");
        assertTrue(Files.exists(uiManifest),
                "Expected generated business UI manifest: " + uiManifest);
        String manifest = Files.readString(uiManifest);
        for (String route : List.of("/mosaics", "/collection", "/caps", "/breweries",
                "/members")) {
            assertTrue(manifest.contains(route), "UI manifest must contain route " + route);
        }
        // G6: every "$file:" seed value is packaged with the app for SeedDataService to store.
        for (String image : List.of("branik.png", "lech-pils.png", "la-bierre.png", "plain-silver.png")) {
            Path packaged = out.resolve("src/main/resources/npdev-seed/files/seed-files/" + image);
            assertTrue(Files.exists(packaged), "Expected packaged seed file: " + packaged);
        }
    }
}