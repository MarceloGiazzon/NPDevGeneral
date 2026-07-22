package com.npdev.generator.emitters;

import com.npdev.generator.output.GeneratedSourceWriter;

/**
 * Emits {@code application-npdev-log.properties}, translating the resolved {@code log.enabled} /
 * {@code log.level} settings into the real Spring Boot {@code logging.level.root} property.
 *
 * <p>The file is loaded via {@code spring.config.import} in the RuntimeHost profiles (the same
 * mechanism as the generated {@code application-npdev-auth.properties}/{@code -db.properties}). It
 * is only emitted when the model explicitly personalizes {@code log.enabled} or {@code log.level};
 * default-config apps emit nothing and keep the RuntimeHost profile defaults, so existing behaviour
 * is unchanged.</p>
 */
public final class RuntimeLogPropertiesEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/application-npdev-log.properties";

    private final GeneratedSourceWriter writer;

    public RuntimeLogPropertiesEmitter(GeneratedSourceWriter writer) {
        this.writer = writer;
    }

    public void emit(boolean logEnabled, String logLevel) {
        writer.writeRelative(RELATIVE_PATH, properties(logEnabled, logLevel));
    }

    /** Maps the resolved log.enabled/log.level settings to a real logging.level.root property. */
    static String properties(boolean logEnabled, String logLevel) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated from the resolved NPDev log.enabled/log.level settings.\n");
        sb.append("# Loaded via spring.config.import in the RuntimeHost profiles.\n");
        if (!logEnabled) {
            sb.append("logging.level.root=OFF\n");
            return sb.toString();
        }
        String level = (logLevel == null || logLevel.isBlank() ? "info" : logLevel.trim()).toUpperCase();
        sb.append("logging.level.root=").append(level).append("\n");
        return sb.toString();
    }
}
