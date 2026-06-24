package com.npdev.generator.emitters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeLogPropertiesEmitterTest {

    @Test
    void disabledLoggingMapsToOffRegardlessOfLevel() {
        String properties = RuntimeLogPropertiesEmitter.properties(false, "debug");
        assertTrue(properties.contains("logging.level.root=OFF"), properties);
    }

    @Test
    void enabledLoggingMapsLevelUppercased() {
        String properties = RuntimeLogPropertiesEmitter.properties(true, "debug");
        assertTrue(properties.contains("logging.level.root=DEBUG"), properties);
    }

    @Test
    void blankLevelDefaultsToInfo() {
        String properties = RuntimeLogPropertiesEmitter.properties(true, "");
        assertTrue(properties.contains("logging.level.root=INFO"), properties);
    }
}
