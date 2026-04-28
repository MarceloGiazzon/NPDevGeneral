package com.npdev.adapters.runtime.validation;

import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

public final class NpdevBuildInfoInfoContributor implements InfoContributor {
    private static final String UNKNOWN = "UNKNOWN";
    private static final String BUILD_INFO_RESOURCE = "npdev-build-info.properties";

    private final RuntimeSettings runtimeSettings;
    private final Map<String, String> buildInfo;

    public NpdevBuildInfoInfoContributor(RuntimeSettings runtimeSettings) {
        this.runtimeSettings = Objects.requireNonNull(runtimeSettings, "runtimeSettings");
        this.buildInfo = loadBuildInfo();
    }

    @Override
    public void contribute(Info.Builder builder) {
        builder.withDetail("npdev.mode", runtimeSettings.mode());
        builder.withDetail("npdev.schedulerEnabled", runtimeSettings.schedulerEnabled());
        builder.withDetail("npdev.schedulerBatchLimit", runtimeSettings.schedulerBatchLimit());
        builder.withDetail("npdev.schedulerTickMillis", runtimeSettings.schedulerTickMillis());
        builder.withDetail("npdev.authEnabled", runtimeSettings.authEnabled());

        builder.withDetail("npdev.version", buildInfo.get("npdev.version"));
        builder.withDetail("npdev.commit", buildInfo.get("npdev.commit"));
        builder.withDetail("npdev.builtAt", buildInfo.get("npdev.builtAt"));
        builder.withDetail("npdev.generator.version", buildInfo.get("npdev.generator.version"));
        builder.withDetail("npdev.generator.tag", buildInfo.get("npdev.generator.tag"));
    }

    private static Map<String, String> loadBuildInfo() {
        Properties properties = new Properties();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = NpdevBuildInfoInfoContributor.class.getClassLoader();
        }
        if (classLoader != null) {
            try (InputStream inputStream = classLoader.getResourceAsStream(BUILD_INFO_RESOURCE)) {
                if (inputStream != null) {
                    properties.load(inputStream);
                }
            } catch (Exception ignored) {
                // Keep unknown fallback values when resource is missing or unreadable.
            }
        }

        Map<String, String> values = new LinkedHashMap<>();
        values.put("npdev.version", normalizeOrUnknown(properties.getProperty("npdev.version")));
        values.put("npdev.commit", normalizeOrUnknown(properties.getProperty("npdev.commit")));
        values.put("npdev.builtAt", normalizeOrUnknown(properties.getProperty("npdev.builtAt")));
        values.put("npdev.generator.version", normalizeOrUnknown(properties.getProperty("npdev.generator.version")));
        values.put("npdev.generator.tag", normalizeOrUnknown(properties.getProperty("npdev.generator.tag")));
        return Map.copyOf(values);
    }

    private static String normalizeOrUnknown(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? UNKNOWN : trimmed;
    }
}
