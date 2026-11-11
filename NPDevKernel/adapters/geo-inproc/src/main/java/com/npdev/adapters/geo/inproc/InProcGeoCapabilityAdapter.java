package com.npdev.adapters.geo.inproc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The built-in {@code geo} capability: great-circle distance and nearest-candidate search over plain
 * latitude/longitude pairs. Pure arithmetic -- no IO, no state, no map provider -- so a model uses it
 * without writing or reviewing any code. Both operations take ONE map argument (a flow's object-form
 * {@code args}):
 *
 * <ul>
 *   <li>{@code distanceKm}: {@code {fromLat, fromLng, toLat, toLng}} -> {@code {distanceKm}}</li>
 *   <li>{@code closest}: {@code {lat, lng, candidates:[{id, lat, lng}], limit?, maxKm?, speedKmh?}} ->
 *       {@code {found, id, distanceKm, etaMinutes, withinCount, ranked:[{id, distanceKm}]}}; candidates without a
 *       usable position are skipped; {@code excludeIds} (ids as strings, or maps with an {@code id} key) are skipped
 *       too; {@code withinCount} is how many usable candidates are within {@code maxKm} (all of them when it is
 *       absent) BEFORE {@code limit} cuts {@code ranked}, which is ordered nearest first.</li>
 * </ul>
 *
 * Straight-line (haversine) distance, not road distance: say so to the person using it.
 */
public final class InProcGeoCapabilityAdapter implements CapabilityAdapter {

    static final double EARTH_RADIUS_KM = 6371.0088;
    static final double DEFAULT_SPEED_KMH = 30.0;
    /** Positional order of each operation's arguments, for a procedure step's object-form args (keys in this order). */
    static final List<String> DISTANCE_ORDER = List.of("fromLat", "fromLng", "toLat", "toLng");
    static final List<String> CLOSEST_ORDER = List.of("lat", "lng", "candidates", "limit", "maxKm", "speedKmh", "excludeIds");

    @Override
    public String adapterId() {
        return "geo-inproc";
    }

    @Override
    public String capability() {
        return "geo";
    }

    @Override
    public String capabilityType() {
        return "GeoCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        List<String> order = switch (String.valueOf(call.operation())) {
            case "distanceKm" -> DISTANCE_ORDER;
            case "closest" -> CLOSEST_ORDER;
            default -> null;
        };
        if (order == null) {
            return contract("GEO_OPERATION_UNSUPPORTED", "Unsupported geo operation: " + call.operation(), call.operation());
        }
        Map<String, Object> args = namedArgs(call.args(), order);
        if (args == null) {
            return contract("GEO_ARGS_MISSING", "geo." + call.operation() + " needs its arguments: " + order, call.operation());
        }
        try {
            return switch (call.operation()) {
                case "distanceKm" -> CapabilityResult.success(distanceKm(args));
                case "closest" -> CapabilityResult.success(closest(args));
                default -> contract("GEO_OPERATION_UNSUPPORTED", "Unsupported geo operation: " + call.operation(),
                        call.operation());
            };
        } catch (IllegalArgumentException e) {
            return contract("GEO_ARGS_INVALID", e.getMessage(), call.operation());
        }
    }

    /**
     * A procedure step's object-form {@code args} reaches an adapter as the list of its VALUES in key order
     * (ProcedureRunner), while a flow step or a programmatic caller may hand ONE map read by key -- the same two
     * shapes {@code notify} and {@code mail} accept. Returns null when there is nothing to read.
     */
    private static Map<String, Object> namedArgs(List<Object> args, List<String> order) {
        if (args == null || args.isEmpty()) {
            return null;
        }
        Map<String, Object> named = new LinkedHashMap<>();
        if (args.size() == 1 && args.get(0) instanceof Map<?, ?> single) {
            single.forEach((k, v) -> named.put(String.valueOf(k), v));
            return named;
        }
        for (int i = 0; i < args.size() && i < order.size(); i++) {
            named.put(order.get(i), args.get(i));
        }
        return named;
    }

    public Map<String, Object> distanceKm(Map<String, Object> args) {
        double km = haversineKm(
                coordinate(args.get("fromLat"), "fromLat", 90),
                coordinate(args.get("fromLng"), "fromLng", 180),
                coordinate(args.get("toLat"), "toLat", 90),
                coordinate(args.get("toLng"), "toLng", 180));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("distanceKm", round2(km));
        return out;
    }

    public Map<String, Object> closest(Map<String, Object> args) {
        double lat = coordinate(args.get("lat"), "lat", 90);
        double lng = coordinate(args.get("lng"), "lng", 180);
        double speedKmh = positiveOrDefault(args.get("speedKmh"), DEFAULT_SPEED_KMH, "speedKmh");
        Double maxKm = args.get("maxKm") == null ? null : positiveOrDefault(args.get("maxKm"), 0, "maxKm");
        int limit = args.get("limit") == null ? Integer.MAX_VALUE : (int) positiveOrDefault(args.get("limit"), 1, "limit");

        java.util.Set<String> excluded = new java.util.HashSet<>();
        if (args.get("excludeIds") instanceof List<?> ids) {
            for (Object id : ids) {
                Object value = id instanceof Map<?, ?> m ? m.get("id") : id;
                if (value != null) {
                    excluded.add(String.valueOf(value));
                }
            }
        }
        List<Map<String, Object>> ranked = new ArrayList<>();
        if (args.get("candidates") instanceof List<?> candidates) {
            for (Object row : candidates) {
                if (!(row instanceof Map<?, ?> c) || c.get("id") == null || excluded.contains(String.valueOf(c.get("id")))) {
                    continue;
                }
                Double cLat = optionalCoordinate(c.get("lat"), 90);
                Double cLng = optionalCoordinate(c.get("lng"), 180);
                if (cLat == null || cLng == null) {
                    continue;
                }
                double km = haversineKm(lat, lng, cLat, cLng);
                if (maxKm != null && km > maxKm) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", c.get("id"));
                entry.put("distanceKm", round2(km));
                entry.put("etaMinutes", (int) Math.max(1, Math.ceil(km / speedKmh * 60.0)));
                ranked.add(entry);
            }
        }
        ranked.sort(Comparator.comparingDouble(e -> ((Number) e.get("distanceKm")).doubleValue()));
        int withinCount = ranked.size();
        if (ranked.size() > limit) {
            ranked = new ArrayList<>(ranked.subList(0, limit));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", !ranked.isEmpty());
        if (!ranked.isEmpty()) {
            Map<String, Object> best = ranked.get(0);
            out.put("id", best.get("id"));
            out.put("distanceKm", best.get("distanceKm"));
            out.put("etaMinutes", best.get("etaMinutes"));
        }
        out.put("withinCount", withinCount);
        out.put("ranked", ranked);
        return out;
    }

    static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    private static double coordinate(Object value, String name, double bound) {
        Double v = optionalCoordinate(value, bound);
        if (v == null) {
            throw new IllegalArgumentException(name + " is required and must be a number between -" + (int) bound + " and " + (int) bound);
        }
        return v;
    }

    private static Double optionalCoordinate(Object value, double bound) {
        if (value == null) {
            return null;
        }
        double v;
        try {
            v = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return Double.isNaN(v) || Math.abs(v) > bound ? null : v;
    }

    private static double positiveOrDefault(Object value, double fallback, String name) {
        if (value == null) {
            return fallback;
        }
        try {
            double v = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value).trim());
            if (v > 0) {
                return v;
            }
        } catch (NumberFormatException ignored) {
            // falls through to the error below
        }
        throw new IllegalArgumentException(name + " must be a number greater than 0");
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static CapabilityResult contract(String code, String message, String operation) {
        return CapabilityResult.failure(code, message, CapabilityErrorKind.CONTRACT, Map.of("operation", String.valueOf(operation)));
    }
}
