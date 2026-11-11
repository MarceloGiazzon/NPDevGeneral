package com.npdev.adapters.geo.inproc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcGeoCapabilityAdapterTest {

    private final InProcGeoCapabilityAdapter adapter = new InProcGeoCapabilityAdapter();

    private CapabilityResult call(String operation, Map<String, Object> args) {
        return adapter.invoke(new CapabilityCall("geo", "GeoCapability", "geo-inproc", operation, List.of(args), null, null), Map.of());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> value(CapabilityResult r) {
        assertTrue(r.ok(), () -> "expected success but got " + r);
        return (Map<String, Object>) r.value();
    }

    @Test
    void identifiesItself() {
        assertEquals("geo-inproc", adapter.adapterId());
        assertEquals("geo", adapter.capability());
        assertEquals("GeoCapability", adapter.capabilityType());
    }

    @Test
    void distanceBetweenTwoKnownCitiesIsWithinAKilometre() {
        // Montevideo -> Buenos Aires is about 203 km in a straight line.
        Map<String, Object> out = value(call("distanceKm", Map.of(
                "fromLat", -34.9011, "fromLng", -56.1645, "toLat", -34.6037, "toLng", -58.3816)));
        double km = ((Number) out.get("distanceKm")).doubleValue();
        assertTrue(km > 199 && km < 207, "km=" + km);
    }

    @Test
    void sameSpotIsZeroAndNumbersMayArriveAsStrings() {
        Map<String, Object> out = value(call("distanceKm", Map.of(
                "fromLat", "-34.9", "fromLng", "-56.16", "toLat", -34.9, "toLng", -56.16)));
        assertEquals(0.0, ((Number) out.get("distanceKm")).doubleValue());
    }

    @Test
    void closestRanksNearestFirstAndSkipsUnusableCandidates() {
        Map<String, Object> out = value(call("closest", Map.of(
                "lat", -34.9, "lng", -56.16,
                "candidates", List.of(
                        Map.of("id", "far", "lat", -34.8, "lng", -56.0),
                        Map.of("id", "near", "lat", -34.901, "lng", -56.161),
                        Map.of("id", "mid", "lat", -34.93, "lng", -56.19),
                        Map.of("id", "nopos"),
                        Map.of("lat", -34.9, "lng", -56.16)))));
        assertEquals(true, out.get("found"));
        assertEquals("near", out.get("id"));
        List<?> ranked = (List<?>) out.get("ranked");
        assertEquals(3, ranked.size());
        assertEquals(List.of("near", "mid", "far"), ranked.stream().map(r -> ((Map<?, ?>) r).get("id")).toList());
        assertTrue(((Number) out.get("etaMinutes")).intValue() >= 1);
    }

    @Test
    void closestHonoursMaxKmAndLimit() {
        Map<String, Object> limited = value(call("closest", Map.of(
                "lat", -34.9, "lng", -56.16, "limit", 1, "maxKm", 10,
                "candidates", List.of(
                        Map.of("id", "near", "lat", -34.901, "lng", -56.161),
                        Map.of("id", "mid", "lat", -34.93, "lng", -56.19),
                        Map.of("id", "far", "lat", -34.8, "lng", -56.0)))));
        assertEquals(1, ((List<?>) limited.get("ranked")).size());

        Map<String, Object> none = value(call("closest", Map.of(
                "lat", -34.9, "lng", -56.16, "maxKm", 1,
                "candidates", List.of(Map.of("id", "far", "lat", -34.8, "lng", -56.0)))));
        assertEquals(false, none.get("found"));
        assertFalse(none.containsKey("id"));
        assertTrue(((List<?>) none.get("ranked")).isEmpty());
    }

    @Test
    void closestWithoutCandidatesIsNotFoundNotAnError() {
        Map<String, Object> out = value(call("closest", Map.of("lat", 0, "lng", 0)));
        assertEquals(false, out.get("found"));
    }

    /** A procedure step's object-form args reaches the adapter as the VALUES in key order (ProcedureRunner). */
    @Test
    void positionalArgumentsFromAProcedureStepAreReadInDeclaredOrder() {
        CapabilityResult closest = adapter.invoke(new CapabilityCall("geo", "GeoCapability", "geo-inproc", "closest",
                List.of(-34.9, -56.16, List.of(
                        Map.of("id", "far", "lat", -34.8, "lng", -56.0),
                        Map.of("id", "near", "lat", -34.901, "lng", -56.161))), null, null), Map.of());
        assertEquals("near", value(closest).get("id"));

        CapabilityResult withLimit = adapter.invoke(new CapabilityCall("geo", "GeoCapability", "geo-inproc", "closest",
                List.of(-34.9, -56.16, List.of(
                        Map.of("id", "far", "lat", -34.8, "lng", -56.0),
                        Map.of("id", "near", "lat", -34.901, "lng", -56.161)), 1), null, null), Map.of());
        assertEquals(1, ((List<?>) value(withLimit).get("ranked")).size());

        CapabilityResult distance = adapter.invoke(new CapabilityCall("geo", "GeoCapability", "geo-inproc", "distanceKm",
                List.of(-34.9011, -56.1645, -34.6037, -58.3816), null, null), Map.of());
        double km = ((Number) value(distance).get("distanceKm")).doubleValue();
        assertTrue(km > 199 && km < 207, "km=" + km);
    }

    @Test
    void badArgumentsAreNamedContractErrors() {
        CapabilityResult missing = call("distanceKm", Map.of("fromLat", 1, "fromLng", 1, "toLat", 1));
        assertFalse(missing.ok());
        assertEquals("GEO_ARGS_INVALID", missing.error().code());

        CapabilityResult outOfRange = call("distanceKm", Map.of("fromLat", 95, "fromLng", 1, "toLat", 1, "toLng", 1));
        assertEquals("GEO_ARGS_INVALID", outOfRange.error().code());

        CapabilityResult noArgs = adapter.invoke(new CapabilityCall("geo", "closest", null), Map.of());
        assertEquals("GEO_ARGS_MISSING", noArgs.error().code());

        CapabilityResult unknown = call("teleport", Map.of("lat", 1));
        assertEquals("GEO_OPERATION_UNSUPPORTED", unknown.error().code());
    }
}
