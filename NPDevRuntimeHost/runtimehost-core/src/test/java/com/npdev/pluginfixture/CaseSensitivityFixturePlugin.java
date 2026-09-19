package com.npdev.pluginfixture;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REG-218 regression fixture: a real, compiled {@code plugin:java-source}-shaped POJO with a
 * camelCase operation method, used by {@code ManifestDrivenJavaSourcePluginHandlerTest} to prove
 * the handler's manifest lookup binds a camelCase {@code call.operation()} against the
 * generator's always-lowercased manifest keys.
 */
public final class CaseSensitivityFixturePlugin {

    public Map<String, Object> doThing(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("received", input);
        result.put("handledBy", "CaseSensitivityFixturePlugin");
        result.put("overload", "one-arg");
        return result;
    }

    // REG-223 fixture: a second overload sharing the same name, mirroring
    // FiscalImportCapability.importarRomaneio's real 1-arg/3-arg overload pair. A handler that
    // hardcodes a single-Map-arg getMethod lookup can only ever resolve the one-arg overload above,
    // silently dropping "second" whenever a caller declares this 2-arg shape.
    public Map<String, Object> doThing(Map<String, Object> first, Map<String, Object> second) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("first", first);
        result.put("second", second);
        result.put("handledBy", "CaseSensitivityFixturePlugin");
        result.put("overload", "two-arg");
        return result;
    }
}
