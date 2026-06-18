package com.npdev.generator.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigSettingsReaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SettingResolver resolverFrom(String json) throws Exception {
        return new SettingResolver(new ConfigSettingsReader().read(MAPPER.readTree(json)));
    }

    @Test
    void absentEnvelopeYieldsPlatformDefaults() throws Exception {
        SettingResolver resolver = resolverFrom("{ \"scenario\": { \"name\": \"x\" } }");

        assertTrue(resolver.value(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app()));
        assertEquals("info", resolver.value(NpdevSettings.LOG_LEVEL, SettingTarget.app()));
    }

    @Test
    void readsAppDefaults() throws Exception {
        SettingResolver resolver = resolverFrom("""
                {
                  "defaults": { "ui.generateBusinessUi": false, "log.level": "debug" }
                }
                """);

        assertFalse(resolver.value(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app()));
        assertEquals("debug", resolver.value(NpdevSettings.LOG_LEVEL, SettingTarget.app()));
        assertEquals(SettingScope.APP,
                resolver.resolve(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app()).sourceScope());
    }

    @Test
    void overrideSelectorsAreScopedAndMoreSpecific() throws Exception {
        SettingResolver resolver = resolverFrom("""
                {
                  "defaults": { "ui.generateBusinessUi": true },
                  "overrides": {
                    "concept:Order": { "ui.generateBusinessUi": false },
                    "field:Order.rating": { "field.widget": "stars" }
                  }
                }
                """);

        // Concept override wins for Order; the app default still applies to other concepts.
        assertFalse(resolver.value(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.concept("Order")));
        assertTrue(resolver.value(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.concept("Invoice")));

        var orderResolved = resolver.resolve(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.concept("Order"));
        assertEquals(SettingScope.CONCEPT, orderResolved.sourceScope());
        assertEquals("concept:Order", orderResolved.sourceSelector());

        // Field selector resolves at field scope.
        assertEquals("stars", resolver.value(NpdevSettings.FIELD_WIDGET, SettingTarget.field("Order", "rating")));
        assertEquals(SettingScope.FIELD,
                resolver.resolve(NpdevSettings.FIELD_WIDGET, SettingTarget.field("Order", "rating")).sourceScope());
    }

    @Test
    void ignoresNullAndNonScalarEnvelopeValues() throws Exception {
        SettingResolver resolver = resolverFrom("""
                {
                  "defaults": { "ui.generateBusinessUi": null, "junk": { "nested": 1 }, "log.level": "warn" }
                }
                """);

        // null and object values are ignored, so the platform default stands for ui.generateBusinessUi.
        assertTrue(resolver.value(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app()));
        assertEquals("warn", resolver.value(NpdevSettings.LOG_LEVEL, SettingTarget.app()));
    }

    @Test
    void legacyEmitUiAssetsSeedsBusinessUiDefault() throws Exception {
        SettingResolver resolver = resolverFrom("""
                {
                  "generator": { "emitUiAssets": false }
                }
                """);

        assertFalse(resolver.value(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app()));
        assertEquals(SettingScope.APP,
                resolver.resolve(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app()).sourceScope());
    }

    @Test
    void explicitDefaultWinsOverLegacyEmitUiAssets() throws Exception {
        SettingResolver resolver = resolverFrom("""
                {
                  "generator": { "emitUiAssets": true },
                  "defaults": { "ui.generateBusinessUi": false }
                }
                """);

        assertFalse(resolver.value(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app()));
    }
}
