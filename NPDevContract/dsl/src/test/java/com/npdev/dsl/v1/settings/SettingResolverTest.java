package com.npdev.dsl.v1.settings;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingResolverTest {

    @Test
    void fallsBackToPlatformDefaultWhenNoLayers() {
        SettingResolver resolver = new SettingResolver(SettingStore.empty());

        ResolvedSetting<Boolean> resolved =
                resolver.resolve(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app());

        assertEquals(Boolean.TRUE, resolved.value());
        assertEquals(SettingScope.PLATFORM, resolved.sourceScope());
        assertEquals(SettingResolver.PLATFORM_SELECTOR, resolved.sourceSelector());
        assertFalse(resolved.isOverridden());
    }

    @Test
    void appLayerOverridesPlatformDefault() {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.UI_GENERATE_BUSINESS_UI.id(), false), "config.json defaults")
                .build();

        ResolvedSetting<Boolean> resolved =
                new SettingResolver(store).resolve(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.app());

        assertEquals(Boolean.FALSE, resolved.value());
        assertEquals(SettingScope.APP, resolved.sourceScope());
        assertTrue(resolved.isOverridden());
    }

    @Test
    void conceptOverrideBeatsAppDefaultButOnlyForThatConcept() {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.UI_GENERATE_BUSINESS_UI.id(), false), "config.json defaults")
                .layer(SettingScope.CONCEPT, "concept:Order",
                        Map.of(NpdevSettings.UI_GENERATE_BUSINESS_UI.id(), true), "overrides[concept:Order]")
                .build();
        SettingResolver resolver = new SettingResolver(store);

        ResolvedSetting<Boolean> order =
                resolver.resolve(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.concept("Order"));
        assertEquals(Boolean.TRUE, order.value());
        assertEquals(SettingScope.CONCEPT, order.sourceScope());
        assertEquals("concept:Order", order.sourceSelector());

        // A different concept is unaffected and inherits the app default.
        ResolvedSetting<Boolean> invoice =
                resolver.resolve(NpdevSettings.UI_GENERATE_BUSINESS_UI, SettingTarget.concept("Invoice"));
        assertEquals(Boolean.FALSE, invoice.value());
        assertEquals(SettingScope.APP, invoice.sourceScope());
    }

    @Test
    void fieldOverrideIsMostSpecific() {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.FIELD_WIDGET.id(), "text"), "config.json defaults")
                .layer(SettingScope.CONCEPT, "concept:Order",
                        Map.of(NpdevSettings.FIELD_WIDGET.id(), "select"), "overrides[concept:Order]")
                .layer(SettingScope.FIELD, "field:Order.rating",
                        Map.of(NpdevSettings.FIELD_WIDGET.id(), "stars"), "overrides[field:Order.rating]")
                .build();
        SettingResolver resolver = new SettingResolver(store);

        assertEquals("stars",
                resolver.value(NpdevSettings.FIELD_WIDGET, SettingTarget.field("Order", "rating")));
        // A sibling field inherits the concept-level override, not the field-specific one.
        assertEquals("select",
                resolver.value(NpdevSettings.FIELD_WIDGET, SettingTarget.field("Order", "note")));
    }

    @Test
    void moduleOverrideBeatsAppDefaultButConceptOverrideBeatsModule() {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.LOG_LEVEL.id(), "info"), "config.json defaults")
                .layer(SettingScope.MODULE, "module:billing",
                        Map.of(NpdevSettings.LOG_LEVEL.id(), "debug"), "overrides[module:billing]")
                .layer(SettingScope.CONCEPT, "concept:Invoice",
                        Map.of(NpdevSettings.LOG_LEVEL.id(), "trace"), "overrides[concept:Invoice]")
                .build();
        SettingResolver resolver = new SettingResolver(store);

        // A concept declared inside the module, with no concept-specific override, inherits the
        // module-level override over the app default.
        ResolvedSetting<String> payment =
                resolver.resolve(NpdevSettings.LOG_LEVEL, SettingTarget.conceptInModule("billing", "Payment"));
        assertEquals("debug", payment.value());
        assertEquals(SettingScope.MODULE, payment.sourceScope());
        assertEquals("module:billing", payment.sourceSelector());

        // A concept in the same module that also has its own concept-level override wins over
        // the module's, confirming the fallthrough order is concept > module > app.
        ResolvedSetting<String> invoice =
                resolver.resolve(NpdevSettings.LOG_LEVEL, SettingTarget.conceptInModule("billing", "Invoice"));
        assertEquals("trace", invoice.value());
        assertEquals(SettingScope.CONCEPT, invoice.sourceScope());

        // A concept in a different (unconfigured) module falls all the way through to the app default.
        ResolvedSetting<String> shipping =
                resolver.resolve(NpdevSettings.LOG_LEVEL, SettingTarget.conceptInModule("logistics", "Shipment"));
        assertEquals("info", shipping.value());
        assertEquals(SettingScope.APP, shipping.sourceScope());
    }

    @Test
    void layerWithoutTheSettingIsSkipped() {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.LOG_LEVEL.id(), "debug"), "config.json defaults")
                .layer(SettingScope.CONCEPT, "concept:Order",
                        Map.of(NpdevSettings.FIELD_WIDGET.id(), "select"), "overrides[concept:Order]")
                .build();

        // The concept:Order layer does not define log.level, so resolution falls through to app.
        ResolvedSetting<String> resolved =
                new SettingResolver(store).resolve(NpdevSettings.LOG_LEVEL, SettingTarget.concept("Order"));
        assertEquals("debug", resolved.value());
        assertEquals(SettingScope.APP, resolved.sourceScope());
    }

    @Test
    void coercesStringBooleanAndInteger() {
        SettingKey<Integer> pageSize = SettingKey.integer("ui.pageSize", 20, "rows per page");
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR, Map.of(
                        NpdevSettings.CODA_ALLOWED.id(), "true",
                        pageSize.id(), "50"), "config.json defaults")
                .build();
        SettingResolver resolver = new SettingResolver(store);

        assertEquals(Boolean.TRUE, resolver.value(NpdevSettings.CODA_ALLOWED, SettingTarget.app()));
        assertEquals(Integer.valueOf(50), resolver.value(pageSize, SettingTarget.app()));
    }

    @Test
    void invalidBooleanRaisesSettingResolutionException() {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR,
                        Map.of(NpdevSettings.CODA_ALLOWED.id(), "yes-please"), "config.json defaults")
                .build();

        assertThrows(SettingResolutionException.class,
                () -> new SettingResolver(store).value(NpdevSettings.CODA_ALLOWED, SettingTarget.app()));
    }
}
