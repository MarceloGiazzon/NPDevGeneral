package com.npdev.generator.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.SettingScope;
import com.npdev.dsl.v1.settings.SettingStore;
import com.npdev.dsl.v1.settings.SettingTarget;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the optional {@code defaults}/{@code overrides} envelope from a parsed {@code config.json}
 * into a {@link SettingStore}.
 *
 * <p>The {@code defaults} object becomes the application-wide layer (selector {@code "app"}); each
 * entry under {@code overrides} becomes a more specific layer whose scope is inferred from its
 * selector prefix ({@code module:}, {@code concept:}, {@code field:}). Scalar JSON values are kept
 * as-is and coerced to the setting's declared type lazily at resolve time; objects and arrays are
 * ignored because settings are scalar.</p>
 */
public final class ConfigSettingsReader {

    public SettingStore read(JsonNode config) {
        SettingStore.Builder builder = SettingStore.builder();
        if (config == null || !config.isObject()) {
            return builder.build();
        }

        Map<String, Object> appValues = new LinkedHashMap<>();
        JsonNode defaults = config.get("defaults");
        if (defaults != null && defaults.isObject()) {
            appValues.putAll(toValueMap(defaults));
        }
        applyLegacyAliases(config, appValues);
        if (!appValues.isEmpty()) {
            builder.layer(SettingScope.APP, SettingTarget.APP_SELECTOR, appValues, "config.json defaults");
        }

        JsonNode overrides = config.get("overrides");
        if (overrides != null && overrides.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> entries = overrides.fields();
            while (entries.hasNext()) {
                Map.Entry<String, JsonNode> entry = entries.next();
                String selector = entry.getKey();
                JsonNode body = entry.getValue();
                if (selector == null || selector.isBlank() || body == null || !body.isObject()) {
                    continue;
                }
                Map<String, Object> values = toValueMap(body);
                if (values.isEmpty()) {
                    continue;
                }
                builder.layer(scopeForSelector(selector), selector, values,
                        "config.json overrides[" + selector + "]");
            }
        }

        return builder.build();
    }

    /**
     * Folds recognised legacy config flags into the application defaults so a default always exists
     * even when the new {@code defaults} envelope is absent. Explicit {@code defaults} values win,
     * because they are the more intentional declaration.
     */
    private static void applyLegacyAliases(JsonNode config, Map<String, Object> appValues) {
        JsonNode generator = config.get("generator");
        if (generator != null && generator.isObject()) {
            JsonNode emitUiAssets = generator.get("emitUiAssets");
            if (emitUiAssets != null && emitUiAssets.isBoolean()
                    && !appValues.containsKey(NpdevSettings.UI_GENERATE_BUSINESS_UI.id())) {
                appValues.put(NpdevSettings.UI_GENERATE_BUSINESS_UI.id(), emitUiAssets.booleanValue());
            }
        }
    }

    private static SettingScope scopeForSelector(String selector) {
        if (SettingTarget.APP_SELECTOR.equals(selector)) {
            return SettingScope.APP;
        }
        if (selector.startsWith("module:")) {
            return SettingScope.MODULE;
        }
        if (selector.startsWith("concept:")) {
            return SettingScope.CONCEPT;
        }
        if (selector.startsWith("field:")) {
            return SettingScope.FIELD;
        }
        // Unknown prefix: keep the selector but report it at APP specificity for provenance.
        return SettingScope.APP;
    }

    private static Map<String, Object> toValueMap(JsonNode object) {
        Map<String, Object> values = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> entries = object.fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            Object value = toScalar(entry.getValue());
            if (value != null) {
                values.put(entry.getKey(), value);
            }
        }
        return values;
    }

    private static Object toScalar(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.numberValue();
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        // Objects/arrays are not valid scalar setting values.
        return null;
    }
}
