package com.npdev.generator.emitters;

import com.npdev.generator.output.GeneratedSourceWriter;

/**
 * Emits {@code application-npdev-auth.properties}, translating the resolved {@code auth.mode} setting
 * into the runtime Spring properties consumed by the RuntimeHost
 * ({@code npdev.auth.enabled} / {@code npdev.auth.mode}).
 *
 * <p>The file is loaded via {@code spring.config.import} in the RuntimeHost profiles (the same
 * mechanism as the generated {@code application-npdev-db.properties}). It is only emitted when the
 * model explicitly personalizes {@code auth.mode}; default-config apps emit nothing and keep the
 * RuntimeHost profile defaults, so existing behaviour is unchanged.</p>
 */
public final class RuntimeAuthPropertiesEmitter {

    public static final String RELATIVE_PATH = "src/main/resources/application-npdev-auth.properties";

    private final GeneratedSourceWriter writer;

    public RuntimeAuthPropertiesEmitter(GeneratedSourceWriter writer) {
        this.writer = writer;
    }

    public void emit(String authMode) {
        writer.writeRelative(RELATIVE_PATH, properties(authMode));
    }

    /** Maps the resolved {@code auth.mode} value to RuntimeHost Spring properties. */
    static String properties(String authMode) {
        String mode = authMode == null ? "" : authMode.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("# Generated from the resolved NPDev auth.mode setting.\n");
        sb.append("# Loaded via spring.config.import in the RuntimeHost profiles.\n");
        if ("none".equalsIgnoreCase(mode)) {
            // No authentication: the runtime falls back to the trial/dev principal.
            sb.append("npdev.auth.enabled=false\n");
            return sb.toString();
        }
        // apiKey (default) and jwt both enable auth; map to the RuntimeHost mode token.
        String runtimeMode = "jwt".equalsIgnoreCase(mode) ? "jwt" : "apikey";
        sb.append("npdev.auth.enabled=true\n");
        sb.append("npdev.auth.mode=").append(runtimeMode).append("\n");
        return sb.toString();
    }
}
