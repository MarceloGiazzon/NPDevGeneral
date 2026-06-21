package com.npdev.adapters.json.jackson;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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

    @Test
    void preservesDateTimeAsIso8601TextNotEpochNumber() {
        // Found live: a flow/event payload carrying a datetime field round-tripped through
        // fromJsonToObject as a bare epoch-seconds BigDecimal, which then corrupted a second,
        // capability-dispatched persistence write of the same entity (NPDev schema column is
        // TIMESTAMP WITH TIME ZONE; a BigDecimal cannot bind to it). The fix is for the codec's
        // ObjectMapper to serialize java.time types as ISO-8601 text, not as numeric timestamps.
        JacksonJsonCodec codec = new JacksonJsonCodec();
        OffsetDateTime original = OffsetDateTime.parse("2026-04-15T20:00:00-03:00");

        String json = codec.toJson(Map.of("reservationAt", original));
        Object decoded = codec.fromJsonToObject(json);

        Map<?, ?> map = (Map<?, ?>) decoded;
        Object decodedValue = map.get("reservationAt");
        assertInstanceOf(String.class, decodedValue, "datetime must round-trip as ISO-8601 text, not a numeric timestamp");
        assertEquals(original, OffsetDateTime.parse((String) decodedValue));
    }
}
