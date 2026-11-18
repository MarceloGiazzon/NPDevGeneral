package com.finalexec.experience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** FORM P5-T7: the web app manifest built from a resolved experience. */
class ManifestBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode experience(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void aMobileExperienceIsStandalonePortraitWithItsScopeAndFourIcons() throws Exception {
        Map<String, Object> manifest = ManifestBuilder.build(experience(
                "{\"name\":\"rider\",\"title\":\"Ride with us now\",\"device\":\"mobile\",\"themeColor\":\"#AA00FF\",\"backgroundColor\":\"#101010\"}")).orElseThrow();
        assertEquals("/x/rider/", manifest.get("start_url"));
        assertEquals("/x/rider/", manifest.get("scope"));
        assertEquals("/x/rider/", manifest.get("id"));
        assertEquals("standalone", manifest.get("display"));
        assertEquals("portrait", manifest.get("orientation"));
        assertEquals("Ride with us now", manifest.get("name"));
        assertEquals("rider", manifest.get("short_name"), "a short identifier beats a long title");
        assertEquals("#aa00ff", manifest.get("theme_color"));
        assertEquals("#101010", manifest.get("background_color"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> icons = (List<Map<String, Object>>) manifest.get("icons");
        assertEquals(4, icons.size());
        assertEquals("/x/rider/icon-192.png", icons.get(0).get("src"));
        assertEquals("192x192", icons.get(0).get("sizes"));
        assertEquals("image/png", icons.get(0).get("type"));
        assertEquals("any", icons.get(0).get("purpose"));
        assertEquals("/x/rider/icon-512.png", icons.get(3).get("src"));
        assertEquals("maskable", icons.get(3).get("purpose"));
    }

    @Test
    void deviceDefaultsToBothWhichIsInstallableWithoutAForcedOrientation() throws Exception {
        Map<String, Object> manifest = ManifestBuilder.build(experience("{\"name\":\"ops\"}")).orElseThrow();
        assertFalse(manifest.containsKey("orientation"));
        assertEquals("ops", manifest.get("name"), "no title: the name");
        assertEquals(ManifestBuilder.DEFAULT_THEME_COLOR, manifest.get("theme_color"));
        assertEquals(ManifestBuilder.DEFAULT_BACKGROUND_COLOR, manifest.get("background_color"));
    }

    @Test
    void aMalformedColourFallsBackToTheShellDefaultAndALongNameIsShortened() throws Exception {
        Map<String, Object> manifest = ManifestBuilder.build(experience(
                "{\"name\":\"warehouse_operations\",\"title\":\"Warehouse operations desk\",\"device\":\"both\",\"themeColor\":\"red\"}")).orElseThrow();
        assertEquals(ManifestBuilder.DEFAULT_THEME_COLOR, manifest.get("theme_color"));
        assertEquals("warehouse_op", manifest.get("short_name"));
    }

    @Test
    void aWebExperienceOrAnUnnamedOneHasNoManifest() throws Exception {
        assertTrue(ManifestBuilder.build(experience("{\"name\":\"desk\",\"device\":\"web\"}")).isEmpty());
        assertTrue(ManifestBuilder.build(experience("{\"device\":\"mobile\"}")).isEmpty());
        assertTrue(ManifestBuilder.build(null).isEmpty());
        assertFalse(ManifestBuilder.installable(experience("{\"name\":\"desk\",\"device\":\"web\"}")));
    }

    @Test
    void theIconStemIsASanitisedFileName() {
        assertEquals("a_b_c", ManifestBuilder.iconStem("a b/c"));
        assertEquals("rider-1_x", ManifestBuilder.iconStem("rider-1_x"));
    }
}
