package com.npdev.adapters.externalai.http;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AIP-3 (3.1): the catalogue bundled into this jar is the one {@code npdev ai} reads. */
class ExternalAiCatalogueTest {

    @Test
    void bundledCatalogueLoadsAndEveryProtocolItUsesIsImplemented() {
        ExternalAiCatalogue catalogue = ExternalAiCatalogue.bundled();

        assertFalse(catalogue.appVendors().isEmpty());
        assertTrue(catalogue.protocols().containsAll(ExternalAiCatalogue.PROTOCOLS.keySet()),
                "every protocol the adapter implements is declared by the catalogue: " + catalogue.protocols());
        for (ExternalAiCatalogue.Vendor vendor : catalogue.appVendors()) {
            assertTrue(ExternalAiCatalogue.PROTOCOLS.containsKey(vendor.protocol()), vendor.id());
            assertFalse(vendor.baseUrl().endsWith("/"), vendor.id());
        }
    }

    @Test
    void bundledCatalogueCarriesTheFreeAndLocalVendors() {
        ExternalAiCatalogue catalogue = ExternalAiCatalogue.bundled();

        for (String id : List.of("groq", "cerebras", "openrouter", "mistral", "ollama", "anthropic")) {
            assertTrue(catalogue.appVendor(id).isPresent(), id + " is an app vendor");
        }
        assertNull(catalogue.appVendor("ollama").orElseThrow().keyEnvVar(), "a local server needs no key");
    }

    @Test
    void profilesUseTheProtocolEndpointAndHonourOperatorOverrides() {
        ExternalAiCatalogue catalogue = ExternalAiCatalogue.parse(catalogue("""
                {"id":"groq","kind":"cloud","protocol":"openai-chat","baseUrl":"https://api.groq.com/openai/v1/",
                 "keyEnvVar":"GROQ_KEY","defaultModel":"llama","appVendor":true},
                {"id":"anthropic","kind":"cloud","protocol":"anthropic-messages","baseUrl":"https://api.anthropic.com/v1",
                 "keyEnvVar":"ANT_KEY","defaultModel":"claude","appVendor":true,
                 "extraHeaders":{"anthropic-version":"2023-06-01"}},
                {"id":"nomodel","kind":"cloud","protocol":"openai-chat","baseUrl":"https://x.example/v1",
                 "keyEnvVar":"X_KEY","appVendor":true},
                {"id":"cli-only","kind":"cloud","protocol":"openai-chat","baseUrl":"https://y.example/v1",
                 "keyEnvVar":"Y_KEY","defaultModel":"m"}
                """));
        Map<String, String> overrides = Map.of(
                "npdev.externalai.http.groq.model", "llama-big",
                "npdev.externalai.http.anthropic.apiKeyEnvVar", "MY_ANT_KEY");

        List<ExternalAiVendorProfile> profiles = catalogue.appVendorProfiles(overrides::get);

        assertEquals(2, profiles.size(), "no model -> left out; not appVendor -> left out");
        ExternalAiVendorProfile groq = profiles.get(0);
        assertEquals("https://api.groq.com/openai/v1/chat/completions", groq.baseUrl());
        assertEquals("llama-big", groq.model());
        assertEquals("GROQ_KEY", groq.apiKeyEnvVar());
        assertEquals(ExternalAiRequestFormat.OPENAI_CHAT, groq.requestFormat());
        ExternalAiVendorProfile anthropic = profiles.get(1);
        assertEquals("https://api.anthropic.com/v1/messages", anthropic.baseUrl());
        assertEquals("MY_ANT_KEY", anthropic.apiKeyEnvVar());
        assertEquals(ExternalAiRequestFormat.ANTHROPIC_MESSAGES, anthropic.requestFormat());
    }

    @Test
    void pricesAreReadPerModel() {
        ExternalAiCatalogue catalogue = ExternalAiCatalogue.parse("""
                {"schemaVersion":"npdev-ai-config.v1","protocols":[{"id":"openai-chat"}],"providers":[],
                 "models":[{"provider":"groq","id":"llama","price":{"in":0.59,"out":0.79}},
                           {"provider":"groq","id":"half","price":{"in":1}}]}
                """);

        assertEquals(new BigDecimal("0.79"), catalogue.price("groq", "llama").orElseThrow().outputUsdPerMTok());
        assertTrue(catalogue.price("groq", "half").isEmpty(), "a half-known price is no price");
    }

    /** AIP-3 (3.6, D4): an app's own provider is served like a shipped one, priced flat per provider. */
    @Test
    void anAppsOwnProvidersAreServedNextToTheShippedOnesAndCannotReplaceOne() {
        ExternalAiCatalogue bundled = ExternalAiCatalogue.bundled();
        ExternalAiCatalogue merged = bundled.withOwnProviders(
                List.of(new ExternalAiCatalogue.Vendor("acme-gw", "own", "openai-chat", "https://ai.acme.example/v1/",
                        "NPDEV_EXTERNALAI_ACME_GW_API_KEY", "acme-large")),
                Map.of("acme-gw", new ExternalAiCatalogue.Price(new BigDecimal("0.5"), new BigDecimal("1.25"))));

        ExternalAiVendorProfile acme = merged.appVendorProfiles(key -> null).stream()
                .filter(p -> p.vendorId().equals("acme-gw")).findFirst().orElseThrow();
        assertEquals("https://ai.acme.example/v1/chat/completions", acme.baseUrl());
        assertEquals("acme-large", acme.model());
        assertEquals("NPDEV_EXTERNALAI_ACME_GW_API_KEY", acme.apiKeyEnvVar());
        assertEquals(new BigDecimal("1.25"), merged.price("acme-gw", "any-model").orElseThrow().outputUsdPerMTok());
        assertEquals(bundled.appVendors().size() + 1, merged.appVendors().size());
        assertTrue(bundled.appVendor("acme-gw").isEmpty(), "the bundled catalogue is not changed");

        assertThrows(IllegalStateException.class, () -> bundled.withOwnProviders(List.of(new ExternalAiCatalogue.Vendor(
                "gemini", "own", "openai-chat", "https://evil.example", "NPDEV_EXTERNALAI_GEMINI_API_KEY", "m")), Map.of()));
        assertThrows(IllegalStateException.class, () -> bundled.withOwnProviders(List.of(new ExternalAiCatalogue.Vendor(
                "odd", "own", "grpc", "https://ai.example", null, "m")), Map.of()));
    }

    @Test
    void anAppVendorTheAdapterCannotServeIsRefusedAtLoad() {
        assertThrows(IllegalStateException.class, () -> ExternalAiCatalogue.parse(catalogue("""
                {"id":"odd","protocol":"bedrock","baseUrl":"https://b.example","appVendor":true,"defaultModel":"m"}
                """)));
        assertThrows(IllegalStateException.class, () -> ExternalAiCatalogue.parse(catalogue("""
                {"id":"hdr","protocol":"openai-chat","baseUrl":"https://h.example","appVendor":true,"defaultModel":"m",
                 "extraHeaders":{"x-custom":"1"}}
                """)));
        assertThrows(IllegalStateException.class, () -> ExternalAiCatalogue.parse(catalogue("""
                {"id":"qp","protocol":"openai-chat","baseUrl":"https://q.example","appVendor":true,"defaultModel":"m",
                 "queryParams":{"api-version":"1"}}
                """)));
        assertThrows(IllegalStateException.class, () -> ExternalAiCatalogue.parse("{\"schemaVersion\":\"v0\"}"));
    }

    @Test
    void aDisabledAppVendorIsNotLoaded() {
        ExternalAiCatalogue catalogue = ExternalAiCatalogue.parse(catalogue("""
                {"id":"off","protocol":"bedrock","baseUrl":"https://b.example","appVendor":true,"disabled":true}
                """));

        assertTrue(catalogue.appVendors().isEmpty());
    }

    private static String catalogue(String providers) {
        return "{\"schemaVersion\":\"npdev-ai-config.v1\",\"protocols\":[{\"id\":\"openai-chat\"},"
                + "{\"id\":\"anthropic-messages\"},{\"id\":\"gemini-native\"}],\"providers\":[" + providers
                + "],\"models\":[]}";
    }
}
