package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.strategy.RegenerationPolicy;
import com.npdev.generator.templates.TemplateEngine;
import com.npdev.generator.themes.ThemeCatalog;
import com.npdev.generator.themes.ThemeCatalog.ThemeException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THM-6: the theme files a generated app ships, and the generation-time proof that every theme is valid and readable.
 * Built-in themes are read from the generator's own classpath (the build copies NPDevContract/themes into it), so
 * these tests also prove that copy exists.
 */
public class ThemeEmitterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STATIC = "src/main/resources/static/";

    private static SettingResolver settings(Map<String, Object> app) {
        return new SettingResolver(SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR, app, "test")
                .build());
    }

    private static Path emit(Map<String, Object> app, Path modelDir) throws IOException {
        Path out = Files.createTempDirectory("npdev-theme-out-");
        new ThemeEmitter(new TemplateEngine("npdev-templates/"), new GeneratedSourceWriter(out, new RegenerationPolicy()))
                .emit(settings(app), modelDir == null ? null : modelDir.resolve("model.json"));
        return out;
    }

    private static Path modelDirWithTheme(String fileName, String json) throws IOException {
        Path dir = Files.createTempDirectory("npdev-theme-model-");
        Files.createDirectories(dir.resolve("themes"));
        Files.writeString(dir.resolve("themes").resolve(fileName), json);
        return dir;
    }

    private static String summerJson(String bgOverride) {
        return """
                {"schema":"npdev-theme.v1","id":"sunny","name":"Sunny","version":"1.0.0","colorScheme":"light",
                 "colors":{"bg":"%s","surface":"#ffffff","surfaceRaised":"#ffffff","border":"#d8cfae","borderStrong":"#7a6f45",
                   "text":"#2b2500","textMuted":"#5a5030","accent":"#0b57d0","onAccent":"#ffffff","positive":"#1e6b2e",
                   "negative":"#a32020","warning":"#8a5a00","info":"#0b5a8a"}}
                """.formatted(bgOverride);
    }

    @Test
    void theBuiltInThemesAreOnTheClasspathAndAllValid() {
        Map<String, JsonNode> all = ThemeCatalog.builtIns();
        assertEquals(ThemeCatalog.BUILT_IN_IDS, List.copyOf(all.keySet()));
        for (Map.Entry<String, JsonNode> e : all.entrySet()) {
            assertEquals(List.of(), ThemeCatalog.validate(e.getValue(), all), e.getKey());
        }
    }

    @Test
    void theDefaultAppShipsItsThemeTheEngineAndTheIndex() throws Exception {
        Path out = emit(Map.of(), null);
        assertTrue(Files.exists(out.resolve(STATIC + "themes/npdev-default.json")));
        assertTrue(Files.exists(out.resolve(STATIC + "npdev-theme.js")));
        assertTrue(Files.exists(out.resolve(STATIC + "npdev-a11y.css")));
        assertTrue(Files.exists(out.resolve(STATIC + "npdev-display.js")));
        JsonNode index = MAPPER.readTree(Files.readString(out.resolve(STATIC + "themes/index.json")));
        assertEquals("npdev-default", index.get("default").asText());
        assertEquals(1, index.get("themes").size(), "only the app's theme ships when nothing else is chosen");
        assertEquals(5, index.get("themes").get(0).get("swatches").size());
        try (Stream<Path> files = Files.list(out.resolve(STATIC + "themes"))) {
            assertEquals(2, files.count(), "npdev-default.json + index.json");
        }
    }

    @Test
    void theEngineIsTheVeryFileTheManagerRuns() throws Exception {
        Path out = emit(Map.of(), null);
        Path canonical = Path.of("").toAbsolutePath().getParent().getParent().resolve("NPDevContract/themes/theme-engine.js");
        if (Files.exists(canonical)) {   // the repo layout; absent only when this test runs from a copied tree
            assertEquals(Files.readString(canonical).replace("\r\n", "\n"),
                    Files.readString(out.resolve(STATIC + "npdev-theme.js")).replace("\r\n", "\n"));
        }
    }

    @Test
    void choicesShipEveryChosenThemeAndItsAncestors() throws Exception {
        Path dir = modelDirWithTheme("sunny.json", summerJson("#fff8e7").replace("\"colors\":{", "\"basedOn\":\"npdev-light\",\"colors\":{"));
        Path out = emit(Map.of("ui.theme.name", "sunny", "ui.theme.choices", "npdev-default, high-contrast"), dir);
        JsonNode index = MAPPER.readTree(Files.readString(out.resolve(STATIC + "themes/index.json")));
        List<String> shipped = new java.util.ArrayList<>();
        index.get("themes").forEach(t -> shipped.add(t.get("id").asText()));
        assertEquals(List.of("high-contrast", "npdev-default", "npdev-light", "sunny"), shipped, "sorted, ancestor npdev-light included");
        assertEquals(List.of("npdev-default", "high-contrast"), MAPPER.convertValue(index.get("choices"), List.class));
        assertEquals("sunny", index.get("default").asText());
    }

    @Test
    void anAsteriskOffersEveryBundledTheme() throws Exception {
        Path out = emit(Map.of("ui.theme.choices", "*"), null);
        JsonNode index = MAPPER.readTree(Files.readString(out.resolve(STATIC + "themes/index.json")));
        assertEquals(ThemeCatalog.BUILT_IN_IDS.size(), index.get("choices").size());
    }

    @Test
    void anInvalidThemeFailsGenerationNamingTheFileAndThePair() throws Exception {
        Path dir = modelDirWithTheme("sunny.json", summerJson("#fff8e7").replace("\"textMuted\":\"#5a5030\"", "\"textMuted\":\"#f3ecd2\""));
        ThemeException e = assertThrows(ThemeException.class, () -> emit(Map.of(), dir));
        assertTrue(e.getMessage().contains("themes/sunny.json: colors: textMuted on bg is"), e.getMessage());
        assertTrue(e.getMessage().contains("needs 4.5:1"), e.getMessage());
    }

    @Test
    void hostileValuesAreRefusedNotSanitised() throws Exception {
        Path dir = modelDirWithTheme("sunny.json", summerJson("red; background:url(https://evil.example/x)"));
        ThemeException e = assertThrows(ThemeException.class, () -> emit(Map.of(), dir));
        assertTrue(e.getMessage().contains("colors.bg must be #rrggbb"), e.getMessage());
    }

    @Test
    void aFileNameThatIsNotItsIdAndABuiltInIdAreRefused() throws Exception {
        Path wrongName = modelDirWithTheme("other.json", summerJson("#fff8e7"));
        assertTrue(assertThrows(ThemeException.class, () -> emit(Map.of(), wrongName)).getMessage().contains("must be <id>.json"));
        Path takesBuiltIn = modelDirWithTheme("npdev-dark.json", summerJson("#fff8e7").replace("\"sunny\"", "\"npdev-dark\""));
        assertTrue(assertThrows(ThemeException.class, () -> emit(Map.of(), takesBuiltIn)).getMessage().contains("NPDev's own themes"));
    }

    @Test
    void anUnknownThemeNameOrChoiceIsRefused() {
        assertTrue(assertThrows(ThemeException.class, () -> emit(Map.of("ui.theme.name", "nope"), null)).getMessage().contains("ui.theme.name"));
        assertTrue(assertThrows(ThemeException.class, () -> emit(Map.of("ui.theme.choices", "retro,nope"), null)).getMessage().contains("ui.theme.choices"));
    }

    @Test
    void aBasedOnCycleOrMissingParentIsRefused() throws Exception {
        Path dir = modelDirWithTheme("sunny.json", summerJson("#fff8e7").replace("\"colors\":{", "\"basedOn\":\"ghost\",\"colors\":{"));
        assertTrue(assertThrows(ThemeException.class, () -> emit(Map.of(), dir)).getMessage().contains("unknown theme \"ghost\""));
    }

    @Test
    void generationIsDeterministic() throws Exception {
        Path dir = modelDirWithTheme("sunny.json", summerJson("#fff8e7"));
        Map<String, Object> app = Map.of("ui.theme.name", "sunny", "ui.theme.choices", "retro,windows,sunny");
        Path a = emit(app, dir);
        Path b = emit(app, dir);
        try (Stream<Path> files = Files.walk(a.resolve(STATIC))) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                assertEquals(Files.readString(f), Files.readString(b.resolve(a.relativize(f))), f.toString());
            }
        }
    }

    @Test
    void theContrastFunctionMatchesTheWcagFormula() {
        assertEquals(21.0, ThemeCatalog.contrastRatio("#000000", "#ffffff"), 0.001);
        assertEquals(4.38, ThemeCatalog.contrastRatio("#d64545", "#ffffff"), 0.01, "the old --np-danger on white (ACC-6)");
        assertTrue(ThemeCatalog.contrastRatio("#b42323", "#ffffff") >= 4.5, "the new one passes");
        assertFalse(ThemeCatalog.contrastRatio("#777777", "#ffffff") >= 4.6);
    }

    @Test
    void everyBuiltInSwatchIsFiveHexColours() {
        Map<String, JsonNode> all = ThemeCatalog.builtIns();
        for (JsonNode theme : all.values()) {
            JsonNode sw = ThemeCatalog.swatches(theme, all);
            assertEquals(5, sw.size());
            sw.forEach(c -> assertTrue(c.asText().matches("#[0-9a-fA-F]{6}"), c.asText()));
        }
    }
}
