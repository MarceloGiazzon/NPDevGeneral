package com.npdev.dsl.v1.compiled;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * AIP-3 (3.6, D4): an {@code externalAi.providers[]} entry, compiled from {@code ExternalAiProviderAst}. The
 * key variable is derived from the id ({@link #keyEnvVar()}), never authored, so a model cannot aim its own
 * endpoint at another provider's key.
 */
public record CompiledExternalAiProvider(
        String id,
        String name,
        String protocol,
        String baseUrl,
        boolean needsKey,
        String defaultModel,
        BigDecimal inputUsdPerMTok,
        BigDecimal outputUsdPerMTok
) {
    /** The catalogue's own convention ({@code npdev_ai_config.key_env_var}); null for a keyless endpoint. */
    public String keyEnvVar() {
        return needsKey ? keyEnvVarFor(id) : null;
    }

    public static String keyEnvVarFor(String id) {
        return "NPDEV_EXTERNALAI_" + id.toUpperCase(Locale.ROOT).replace('-', '_') + "_API_KEY";
    }
}
