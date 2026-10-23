package com.npdev.dsl.v1.ast;

import java.math.BigDecimal;

/**
 * AIP-3 (3.6, D4): one {@code externalAi.providers[]} entry -- an AI endpoint of this app's own, beyond the
 * providers the NPDev AI catalogue ships. {@code needsKey} null means the default (true); a price half is
 * null when the model gives no {@code price}.
 */
public record ExternalAiProviderAst(
        String id,
        String name,
        String protocol,
        String baseUrl,
        Boolean needsKey,
        String defaultModel,
        BigDecimal inputUsdPerMTok,
        BigDecimal outputUsdPerMTok
) {
}
