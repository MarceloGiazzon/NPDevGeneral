package com.npdev.generator.emitters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeAuthPropertiesEmitterTest {

    @Test
    void noneDisablesAuth() {
        String properties = RuntimeAuthPropertiesEmitter.properties("none");
        assertTrue(properties.contains("npdev.auth.enabled=false"), properties);
        assertFalse(properties.contains("npdev.auth.mode="), properties);
    }

    @Test
    void apiKeyEnablesApikeyMode() {
        String properties = RuntimeAuthPropertiesEmitter.properties("apiKey");
        assertTrue(properties.contains("npdev.auth.enabled=true"), properties);
        assertTrue(properties.contains("npdev.auth.mode=apikey"), properties);
    }

    @Test
    void jwtEnablesJwtMode() {
        String properties = RuntimeAuthPropertiesEmitter.properties("jwt");
        assertTrue(properties.contains("npdev.auth.enabled=true"), properties);
        assertTrue(properties.contains("npdev.auth.mode=jwt"), properties);
    }

    @Test
    void unknownModeDefaultsToApikey() {
        String properties = RuntimeAuthPropertiesEmitter.properties("weird");
        assertTrue(properties.contains("npdev.auth.enabled=true"), properties);
        assertTrue(properties.contains("npdev.auth.mode=apikey"), properties);
    }
}
