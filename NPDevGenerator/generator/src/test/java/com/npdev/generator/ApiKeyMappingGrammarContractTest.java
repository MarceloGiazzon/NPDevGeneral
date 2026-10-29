package com.npdev.generator;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-314: docs/CONFIGURATION.md documents the {@code npdev.auth.api-keys} grammar
 * ({@code apiKey=tenant:actor[:ROLE[|ROLE...]]}, entries separated by {@code ;}, tenant and actor
 * mandatory, roles upper-cased and defaulting to USER). The parser is the generated
 * RuntimeApiKeyAuthFilter, so a change to its separators, defaults or drop rule has to arrive with a
 * change to that page. The page used to say {@code key=tenantOrRole}, a shape the parser silently
 * discards (every request then 401s as invalid_api_key while StartupValidator, which only looks for
 * an '=', lets the app boot), and nothing noticed.
 */
class ApiKeyMappingGrammarContractTest {

    private static final String TEMPLATE = "/npdev-templates/npdev-runtime-api-key-auth-filter.mustache";

    @Test
    void parserGrammarMatchesWhatConfigurationMdDocuments() throws Exception {
        String filter;
        try (InputStream in = getClass().getResourceAsStream(TEMPLATE)) {
            assertNotNull(in, "template resource missing: " + TEMPLATE);
            filter = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        pin(filter, "raw.split(\";\")", "entries are separated by ';'");
        pin(filter, "normalized.indexOf('=')", "the api key is everything before the first '='");
        pin(filter, "normalized.split(\":\")", "the mapping is tenant:actor[:roles]");
        pin(filter, "parts[2].split(\"\\\\|\")", "roles are separated by '|'");
        pin(filter, ".map(String::toUpperCase)", "roles are upper-cased, so matching is case-insensitive");
        pin(filter, ": Set.of(\"USER\")", "an omitted role part means the single role USER");
        pin(filter, "tenantId == null || actorId == null || roles.isEmpty()",
                "an entry without both tenant and actor is dropped, not rejected");
        pin(filter, "\"actor_id\", actorId", "the actor part is what the claims carry as actor_id ($user.id)");
    }

    private static void pin(String filter, String fragment, String documented) {
        assertTrue(filter.contains(fragment),
                "RuntimeApiKeyAuthFilter no longer contains `" + fragment + "` (" + documented
                        + "). Update the npdev.auth.api-keys grammar in docs/CONFIGURATION.md and this test together.");
    }
}
