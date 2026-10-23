package com.finalexec.config;

import com.finalexec.ai.ExternalAiPromptRunner;
import com.npdev.adapters.externalai.http.ExternalAiCatalogue;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AIP-3 (3.3): an app prices a call with the catalogue's price for the model the prompt asked for. */
class NpdevExternalAiConfigTest {

    private static final ExternalAiCatalogue CATALOGUE = ExternalAiCatalogue.parse("""
            {"schemaVersion":"npdev-ai-config.v1","protocols":[{"id":"openai-chat"}],
             "providers":[
               {"id":"gemini","protocol":"openai-chat","baseUrl":"https://g.example","appVendor":true,
                "defaultModel":"flash-new"},
               {"id":"groq","protocol":"openai-chat","baseUrl":"https://q.example","appVendor":true,
                "defaultModel":"llama"}],
             "models":[
               {"provider":"gemini","id":"flash-new","price":{"in":0.5,"out":3}},
               {"provider":"gemini","id":"flash-old","price":{"in":0.3,"out":2.5}}]}
            """);

    @Test
    void aPromptNamingAModelIsPricedForThatModelAndOneNamingNoneForTheDefault() {
        ExternalAiPromptRunner.Pricing pricing = NpdevExternalAiConfig.cataloguePricing(CATALOGUE, Map.<String, String>of()::get);

        assertEquals(new ExternalAiPromptRunner.Price(new BigDecimal("0.3"), new BigDecimal("2.5")),
                pricing.priceOf("gemini", "flash-old"));
        assertEquals(new ExternalAiPromptRunner.Price(new BigDecimal("0.5"), new BigDecimal("3")),
                pricing.priceOf("gemini", null));
    }

    @Test
    void anUnpricedModelCostsNothingAndOperatorPropertiesWin() {
        Map<String, String> properties = Map.of(
                "npdev.externalai.http.gemini.model", "flash-old",
                "npdev.externalai.price.groq.outputUsdPerMTok", "0.79");
        ExternalAiPromptRunner.Pricing pricing = NpdevExternalAiConfig.cataloguePricing(CATALOGUE, properties::get);

        assertEquals(new ExternalAiPromptRunner.Price(new BigDecimal("0.3"), new BigDecimal("2.5")),
                pricing.priceOf("gemini", ""), "the operator's model override is the default that answers");
        assertEquals(new ExternalAiPromptRunner.Price(BigDecimal.ZERO, new BigDecimal("0.79")),
                pricing.priceOf("groq", null));
        assertEquals(new ExternalAiPromptRunner.Price(BigDecimal.ZERO, BigDecimal.ZERO),
                pricing.priceOf("gemini", "unlisted"));
    }

    /** AIP-3 (3.6, D4): a model's own provider becomes a vendor profile, keyed by its id, priced flat. */
    @Test
    void aModelsOwnProviderIsServedAndPricedFromTheCompiledModel() throws Exception {
        String json = """
            { "dslVersion": "1.0.0", "namespace": "own.ai", "version": "1.0",
              "concepts": [ { "name": "Cap", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] } ],
              "externalAi": { "egress": "apiEnabled", "vendors": ["acme-gw"], "providers": [
                { "id": "acme-gw", "protocol": "anthropic-messages", "baseUrl": "https://ai.acme.example/v1",
                  "defaultModel": "acme-large", "price": { "in": 2, "out": 8 } } ] } }
            """;
        com.npdev.dsl.v1.compiled.CompiledModel model = new com.npdev.dsl.v1.compiler.ModelCompiler().compile(
                new com.npdev.dsl.v1.parser.JsonModelParser().parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(json)));

        ExternalAiCatalogue catalogue = NpdevExternalAiConfig.catalogueFor(model);
        com.npdev.adapters.externalai.http.ExternalAiVendorProfile acme = catalogue.appVendorProfiles(key -> null).stream()
                .filter(p -> p.vendorId().equals("acme-gw")).findFirst().orElseThrow();
        assertEquals("https://ai.acme.example/v1/messages", acme.baseUrl());
        assertEquals("NPDEV_EXTERNALAI_ACME_GW_API_KEY", acme.apiKeyEnvVar());
        ExternalAiPromptRunner.Price price = NpdevExternalAiConfig.cataloguePricing(catalogue, key -> null)
                .priceOf("acme-gw", null);
        assertEquals(0, new BigDecimal("8").compareTo(price.outputPerMillion()));
        assertEquals(ExternalAiCatalogue.bundled().appVendors().size(),
                NpdevExternalAiConfig.catalogueFor(null).appVendors().size(), "no model: the shipped catalogue");
    }
}
