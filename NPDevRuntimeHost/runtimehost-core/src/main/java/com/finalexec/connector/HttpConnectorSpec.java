package com.finalexec.connector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * INT-2: typed view over the compiled {@code http} maps INT-1 puts on a capability and on each of
 * its operations. Parsing is defensive: unknown or missing keys fall back to defaults, the schema and
 * HttpConnectorValidation already rejected malformed models at authoring time.
 */
public record HttpConnectorSpec(String baseUrl, String baseUrlProperty, Auth auth,
                                Map<String, String> headers, long timeoutMs, int maxRetries) {

    /** {@code secretEnvVar}/{@code usernameEnvVar} are environment variable NAMES, never values. */
    public record Auth(String type, String secretEnvVar, String usernameEnvVar, String name) {
    }

    public record Operation(String method, String path, List<String> params, Map<String, String> query,
                            Map<String, String> headers, Object body, String bodyEncoding,
                            String responsePointer) {

        static Operation from(Map<String, Object> http) {
            Map<String, Object> source = http == null ? Map.of() : http;
            String encoding = text(source.get("bodyEncoding"));
            Object response = source.get("response");
            String pointer = response instanceof Map<?, ?> map ? text(map.get("pointer")) : "";
            String method = text(source.get("method")).toUpperCase(Locale.ROOT);
            return new Operation(
                    method.isEmpty() ? "GET" : method,
                    text(source.get("path")),
                    stringList(source.get("params")),
                    stringMap(source.get("query")),
                    stringMap(source.get("headers")),
                    source.get("body"),
                    encoding.isEmpty() ? "json" : encoding,
                    pointer);
        }
    }

    static HttpConnectorSpec from(Map<String, Object> http) {
        Map<String, Object> source = http == null ? Map.of() : http;
        Auth auth = new Auth("none", "", "", "");
        if (source.get("auth") instanceof Map<?, ?> raw) {
            String type = text(raw.get("type"));
            auth = new Auth(type.isEmpty() ? "none" : type, text(raw.get("secretEnvVar")),
                    text(raw.get("usernameEnvVar")), text(raw.get("name")));
        }
        return new HttpConnectorSpec(
                text(source.get("baseUrl")),
                text(source.get("baseUrlProperty")),
                auth,
                stringMap(source.get("headers")),
                source.get("timeoutMs") instanceof Number n ? n.longValue() : 0L,
                source.get("maxRetries") instanceof Number n ? n.intValue() : 0);
    }

    /**
     * Positional args map onto {@code params} in declared order. Exception: ONE map argument that
     * either carries every declared param name or arrives with two or more declared params is taken
     * as NAMED args (flows often pass a single {@code $input} map; orchestration callCapability
     * always passes exactly one mapped payload). Nothing else is unwrapped.
     */
    static Map<String, Object> bindArgs(List<String> params, List<Object> args) {
        Map<String, Object> named = new LinkedHashMap<>();
        List<Object> actual = args == null ? List.of() : args;
        if (actual.size() == 1 && actual.get(0) instanceof Map<?, ?> map
                && (params.size() >= 2 || (!params.isEmpty() && params.stream().allMatch(map::containsKey)))) {
            map.forEach((k, v) -> named.put(String.valueOf(k), v));
            return named;
        }
        for (int i = 0; i < params.size() && i < actual.size(); i++) {
            named.put(params.get(i), actual.get(i));
        }
        return named;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static List<String> stringList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            list.forEach(item -> out.add(String.valueOf(item)));
        }
        return out;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
        }
        return out;
    }
}
