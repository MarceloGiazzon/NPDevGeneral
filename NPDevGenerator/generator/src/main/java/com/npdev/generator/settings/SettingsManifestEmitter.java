package com.npdev.generator.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.npdev.dsl.v1.settings.NpdevSettings;
import com.npdev.dsl.v1.settings.ResolvedSetting;
import com.npdev.dsl.v1.settings.SettingKey;
import com.npdev.dsl.v1.settings.SettingResolver;
import com.npdev.dsl.v1.settings.SettingTarget;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits {@code resolved-settings.json}: the effective value and provenance of every registered NPDev
 * setting at application scope.
 *
 * <p>This is a read-only provenance manifest — it changes no behaviour. It exists so the resolution
 * cascade is inspectable in the generated app, and it is the artifact a box view reads to show
 * "inherited (platform default) vs overridden here". Content is deterministic (registry order, no
 * timestamps or paths) so it does not perturb the generated-folder signature between runs.</p>
 */
public final class SettingsManifestEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/npdev/resolved-settings.json";
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final GeneratedSourceWriter writer;

    public SettingsManifestEmitter(GeneratedSourceWriter writer) {
        this.writer = writer;
    }

    public void emit(SettingResolver resolver) {
        writer.writeRelative(RELATIVE_PATH, toJson(resolver));
    }

    public static String toJson(SettingResolver resolver) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "npdev-resolved-settings.v1");
        root.put("scope", SettingTarget.APP_SELECTOR);

        List<Map<String, Object>> settings = new ArrayList<>();
        for (SettingKey<?> key : NpdevSettings.all()) {
            ResolvedSetting<?> resolved = resolver.resolve(key, SettingTarget.app());
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", key.id());
            node.put("type", key.type().name());
            node.put("value", resolved.value());
            node.put("platformDefault", key.defaultValue());
            node.put("sourceScope", resolved.sourceScope().name());
            node.put("sourceSelector", resolved.sourceSelector());
            node.put("overridden", resolved.isOverridden());
            node.put("description", key.description());
            settings.add(node);
        }
        root.put("settings", settings);

        try {
            return MAPPER.writeValueAsString(root) + System.lineSeparator();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to serialize resolved settings manifest", exception);
        }
    }
}
