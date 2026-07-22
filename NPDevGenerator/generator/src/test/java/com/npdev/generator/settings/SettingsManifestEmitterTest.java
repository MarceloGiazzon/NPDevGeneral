package com.npdev.generator.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.dsl.v1.settings.SettingTarget;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsManifestEmitterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void manifestListsEverySettingWithPlatformProvenance() throws Exception {
        JsonNode root = MAPPER.readTree(SettingsManifestEmitter.toJson(new SettingResolver(SettingStore.empty())));

        assertEquals("npdev-resolved-settings.v1", root.path("schemaVersion").asText());
        assertEquals("app", root.path("scope").asText());
        assertEquals(NpdevSettings.all().size(), root.path("settings").size());

        JsonNode businessUi = findSetting(root, NpdevSettings.UI_GENERATE_BUSINESS_UI.id());
        assertNotNull(businessUi, "manifest must include ui.generateBusinessUi");
        assertTrue(businessUi.path("value").asBoolean());
        assertEquals("PLATFORM", businessUi.path("sourceScope").asText());
        assertFalse(businessUi.path("overridden").asBoolean());
    }

    @Test
    void manifestReflectsOverrideProvenance() throws Exception {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.UI_GENERATE_BUSINESS_UI.id(), false), "config.json defaults")
                .build();

        JsonNode root = MAPPER.readTree(SettingsManifestEmitter.toJson(new SettingResolver(store)));
        JsonNode businessUi = findSetting(root, NpdevSettings.UI_GENERATE_BUSINESS_UI.id());

        assertNotNull(businessUi);
        assertFalse(businessUi.path("value").asBoolean());
        assertEquals("APP", businessUi.path("sourceScope").asText());
        assertEquals("app", businessUi.path("sourceSelector").asText());
        assertTrue(businessUi.path("overridden").asBoolean());
        assertTrue(businessUi.path("platformDefault").asBoolean(), "platform default is still reported alongside the override");
    }

    private static JsonNode findSetting(JsonNode root, String id) {
        for (JsonNode node : root.path("settings")) {
            if (id.equals(node.path("id").asText())) {
                return node;
            }
        }
        return null;
    }
}
