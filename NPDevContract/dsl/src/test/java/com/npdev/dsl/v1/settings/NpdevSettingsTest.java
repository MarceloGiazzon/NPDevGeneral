package com.npdev.dsl.v1.settings;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpdevSettingsTest {

    @Test
    void identitySettingsHavePlatformDefaults() {
        SettingResolver resolver = new SettingResolver(SettingStore.empty());

        assertEquals("apiKey", resolver.value(NpdevSettings.AUTH_MODE, SettingTarget.app()));
        assertEquals("ADMIN", resolver.value(NpdevSettings.SECURITY_SUPER_USER_ROLE, SettingTarget.app()));
        assertTrue(resolver.value(NpdevSettings.SECURITY_TENANT_ISOLATION, SettingTarget.app()));
    }

    @Test
    void identitySettingsAreRegistered() {
        assertTrue(NpdevSettings.all().contains(NpdevSettings.AUTH_MODE));
        assertTrue(NpdevSettings.all().contains(NpdevSettings.SECURITY_SUPER_USER_ROLE));
        assertTrue(NpdevSettings.all().contains(NpdevSettings.SECURITY_TENANT_ISOLATION));
    }

    @Test
    void identitySettingsAreOverridable() {
        SettingStore store = SettingStore.builder()
                .layer(SettingScope.APP, SettingTarget.APP_SELECTOR, Map.of(
                        NpdevSettings.AUTH_MODE.id(), "none",
                        NpdevSettings.SECURITY_SUPER_USER_ROLE.id(), "ROOT",
                        NpdevSettings.SECURITY_TENANT_ISOLATION.id(), false), "config.json defaults")
                .build();
        SettingResolver resolver = new SettingResolver(store);

        assertEquals("none", resolver.value(NpdevSettings.AUTH_MODE, SettingTarget.app()));
        assertEquals("ROOT", resolver.value(NpdevSettings.SECURITY_SUPER_USER_ROLE, SettingTarget.app()));
        assertFalse(resolver.value(NpdevSettings.SECURITY_TENANT_ISOLATION, SettingTarget.app()));
    }
}
