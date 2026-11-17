package com.npdev.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** FORM P3-T9 (D15): config.json's optional {@code adapters} object, capability name -> adapter name. */
class GeneratorMainAdapterOverridesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode config(String json) throws Exception {
        return JSON.readTree(json);
    }

    @Test
    void textualNonBlankValuesAreKeptAndTrimmed() throws Exception {
        Map<String, String> overrides = GeneratorMain.readAdapterOverrides(
                config("{\"adapters\": {\"persistence\": \" memory \", \"audit\": \"plugin:java-source\"}}"));
        assertEquals(Map.of("persistence", "memory", "audit", "plugin:java-source"), overrides);
    }

    @Test
    void blankAndNonTextualValuesAreIgnored() throws Exception {
        Map<String, String> overrides = GeneratorMain.readAdapterOverrides(
                config("{\"adapters\": {\"persistence\": \"   \", \"eventBus\": 7, \"notify\": null, \"keep\": \"x\"}}"));
        assertEquals(Map.of("keep", "x"), overrides);
    }

    @Test
    void aMissingNullOrNonObjectAdaptersEntryYieldsNoOverrides() throws Exception {
        assertTrue(GeneratorMain.readAdapterOverrides(null).isEmpty());
        assertTrue(GeneratorMain.readAdapterOverrides(config("{}")).isEmpty());
        assertTrue(GeneratorMain.readAdapterOverrides(config("{\"adapters\": [\"persistence\"]}")).isEmpty());
    }
}
