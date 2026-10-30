package com.npdev.generator;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-314 / REG-319: the {@code npdev.auth.api-keys} grammar documented in docs/CONFIGURATION.md
 * ({@code apiKey=tenant:actor[:ROLE[|ROLE...]]}) is parsed by exactly one class,
 * {@code com.npdev.kernel.ports.ApiKeyMappings}, which both StartupValidator (to refuse a value that
 * locks every caller out) and the generated RuntimeApiKeyAuthFilter call. The grammar itself is pinned by
 * behaviour in the kernel's ApiKeyMappingsTest. What can still go wrong here is the filter growing its
 * own parser again: the two would drift, and the validator would once more accept what the filter
 * drops (the app booting clean and answering every request 401 invalid_api_key).
 */
class ApiKeyMappingGrammarContractTest {

    private static final String TEMPLATE = "/npdev-templates/npdev-runtime-api-key-auth-filter.mustache";

    @Test
    void filterParsesThroughTheSharedParserAndCarriesNoGrammarOfItsOwn() throws Exception {
        String filter;
        try (InputStream in = getClass().getResourceAsStream(TEMPLATE)) {
            assertNotNull(in, "template resource missing: " + TEMPLATE);
            filter = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(filter.contains("ApiKeyMappings.parse(encodedMappings)"),
                "RuntimeApiKeyAuthFilter must build its key lookup from ApiKeyMappings.parse (the one grammar)");
        assertTrue(filter.contains("parsed.dropped()"),
                "RuntimeApiKeyAuthFilter must log, at startup, how many entries were dropped (REG-319)");
        for (String ownGrammar : new String[] {"split(\";\")", "indexOf('=')", "split(\":\")", "split(\"\\\\|\")", "toUpperCase"}) {
            assertFalse(filter.contains(ownGrammar),
                    "RuntimeApiKeyAuthFilter parses `" + ownGrammar + "` itself; move it into "
                            + "com.npdev.kernel.ports.ApiKeyMappings so StartupValidator cannot drift from it.");
        }
    }
}
