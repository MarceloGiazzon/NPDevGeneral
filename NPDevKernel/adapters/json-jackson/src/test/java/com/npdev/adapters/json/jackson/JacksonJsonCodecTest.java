package com.npdev.adapters.json.jackson;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JacksonJsonCodecTest {
    @Test
    void roundTripsStructuredObjects() {
        JacksonJsonCodec codec = new JacksonJsonCodec();

        String json = codec.toJson(Map.of("id", "u-1", "count", 3));
        Object decoded = codec.fromJsonToObject(json);

        assertTrue(decoded instanceof Map<?, ?>);
        Map<?, ?> map = (Map<?, ?>) decoded;
        assertEquals("u-1", map.get("id"));
        assertEquals(3, ((Number) map.get("count")).intValue());
    }
}
