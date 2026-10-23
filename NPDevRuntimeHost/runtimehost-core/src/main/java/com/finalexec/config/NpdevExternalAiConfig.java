package com.finalexec.config;

import com.finalexec.ai.ExternalAiPromptRunner;
import com.npdev.adapters.externalai.http.ExternalAiCatalogue;
import com.npdev.adapters.externalai.http.ExternalAiVendorProfile;
import com.npdev.adapters.externalai.http.HttpExternalAiCapabilityAdapter;
import com.npdev.adapters.externalai.inproc.InProcExternalAiCapabilityAdapter;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.FileStoreContract;
import com.npdev.kernel.properties.PropertyResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * ADR-0009: wires the {@link ExternalAiCapabilityContract} adapter by config --
 * {@code npdev.externalai.provider: inproc | http}, defaulting to {@code inproc} (air-gapped
 * paste-transport -- writes a pack to disk, no network egress possible) so an unconfigured app
 * cannot accidentally send anything anywhere. Setting {@code http} switches to the real
 * vendor-calling adapter (D2); vendor API keys come from env vars, never committed. The vendors are
 * the app vendors of the AI catalogue NPDev ships (AIP-3), not a list kept here. Same
 * {@code @ConditionalOnProperty} pattern as {@link NpdevFileStoreConfig}.
 *
 * <p>Deliberately wired as a plain Spring bean, constructor-injected into
 * {@code com.finalexec.review.ReviewAdminController} -- not through {@code CapabilityRegistry} /
 * the model-driven capability-binding system, since D6 scoped this pass to the review missions
 * (a fixed platform/ControlPanel capability) and explicitly deferred the general flow-step
 * primitive that binding system exists for.</p>
 */
@Configuration
public class NpdevExternalAiConfig {

    @Bean
    @ConditionalOnProperty(name = "npdev.externalai.provider", havingValue = "inproc", matchIfMissing = true)
    public ExternalAiCapabilityContract inprocExternalAiCapabilityContract(
            @Value("${npdev.externalai.inproc.packDir:${user.dir}/npdev-external-ai-packs}") String packDir
    ) {
        return new InProcExternalAiCapabilityAdapter(Path.of(packDir));
    }

    @Bean
    @ConditionalOnProperty(name = "npdev.externalai.provider", havingValue = "http")
    public ExternalAiCapabilityContract httpExternalAiCapabilityContract(
            Environment environment,
            // R8d (RUN-4): adapter-owned deadline, independent of CapabilityExecutionPolicy (see
            // HttpExternalAiCapabilityAdapter's javadoc). connectTimeoutMs bounds the TCP handshake;
            // requestTimeoutMs bounds a single attempt end-to-end; maxRetries is retries AFTER the
            // first attempt, only for transport failures and 429/5xx.
            @Value("${npdev.externalai.http.connectTimeoutMs:10000}") long connectTimeoutMs,
            @Value("${npdev.externalai.http.requestTimeoutMs:120000}") long requestTimeoutMs,
            @Value("${npdev.externalai.http.maxRetries:2}") int maxRetries,
            @Value("${npdev.externalai.http.retryBackoffMs:500}") long retryBackoffMs
    ) {
        // AIP-3 (3.1): every app vendor of the AI catalogue NPDev ships (bundled in the adapter jar),
        // each driven by its protocol -- so Groq, Cerebras, OpenRouter, Mistral and a local Ollama work
        // like Gemini, with no Java per vendor. npdev.externalai.http.<id>.model / .apiKeyEnvVar still
        // override. All of them are CONFIGURED; only the ones whose key env var is set (or that need
        // none) are reachable -- the adapter denies the rest with EGRESS_DENIED_NO_API_KEY rather than
        // sending. So listing a vendor costs nothing and grants nothing; the key is the switch.
        List<ExternalAiVendorProfile> vendors = ExternalAiCatalogue.bundled().appVendorProfiles(environment::getProperty);
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        return new HttpExternalAiCapabilityAdapter(
                vendors,
                httpClient,
                System::getenv,
                Duration.ofMillis(requestTimeoutMs),
                maxRetries,
                Duration.ofMillis(retryBackoffMs)
        );
    }

    /**
     * P4 (G3): the flow-facing {@code externalAi} capability -- registered into the
     * {@code CapabilityRegistry} for a model binding {@code { "capability": "externalAi" }} (see
     * {@code NpdevCapabilityBindingConfig}), on top of whichever contract bean above is active. Prices
     * are USD per million tokens, used only to estimate spend for {@code externalAi.limits
     * .monthlyCostCapUsd}. AIP-3 (3.3): they come from the AI catalogue -- the same table the developer
     * usage log (`npdev ai usage`) prices with -- for the model each prompt asks for (the vendor's
     * default model when it names none); the
     * {@code npdev.externalai.price.<id>.inputUsdPerMTok|outputUsdPerMTok} properties override per
     * deployment. A vendor whose model has no catalogue price and no override is priced at 0, as
     * before for NVIDIA's free tier; set the properties for a billed one.
     */
    @Bean
    public ExternalAiPromptRunner externalAiPromptRunner(
            ModelHolder modelHolder,
            ExternalAiCapabilityContract externalAiCapabilityContract,
            ObjectProvider<PropertyResolver> propertyResolver,
            ObjectProvider<AuditLogStore> auditLogStore,
            ObjectProvider<FileStoreContract> fileStore,
            Environment environment
    ) {
        return new ExternalAiPromptRunner(
                modelHolder::get,
                externalAiCapabilityContract,
                propertyResolver::getIfAvailable,
                auditLogStore.getIfAvailable(),
                fileStore::getIfAvailable,
                cataloguePricing(ExternalAiCatalogue.bundled(), environment::getProperty),
                Clock.systemUTC());
    }

    /**
     * The catalogue price of the vendor and model a call used; an operator's per-vendor price
     * properties win over it, and a model the catalogue does not price (and no property) costs 0.
     */
    static ExternalAiPromptRunner.Pricing cataloguePricing(
            ExternalAiCatalogue catalogue, Function<String, String> property) {
        Map<String, String> defaultModels = new HashMap<>();
        Map<String, BigDecimal> inputOverrides = new HashMap<>();
        Map<String, BigDecimal> outputOverrides = new HashMap<>();
        for (ExternalAiCatalogue.Vendor vendor : catalogue.appVendors()) {
            String override = property.apply("npdev.externalai.http." + vendor.id() + ".model");
            String model = override != null && !override.isBlank() ? override : vendor.defaultModel();
            if (model != null) {
                defaultModels.put(vendor.id(), model);
            }
            BigDecimal in = decimal(property.apply("npdev.externalai.price." + vendor.id() + ".inputUsdPerMTok"), null);
            BigDecimal out = decimal(property.apply("npdev.externalai.price." + vendor.id() + ".outputUsdPerMTok"), null);
            if (in != null) {
                inputOverrides.put(vendor.id(), in);
            }
            if (out != null) {
                outputOverrides.put(vendor.id(), out);
            }
        }
        return (vendor, model) -> {
            String priced = model != null && !model.isBlank() ? model : defaultModels.get(vendor);
            Optional<ExternalAiCatalogue.Price> listed = priced == null ? Optional.empty() : catalogue.price(vendor, priced);
            return new ExternalAiPromptRunner.Price(
                    inputOverrides.getOrDefault(vendor,
                            listed.map(ExternalAiCatalogue.Price::inputUsdPerMTok).orElse(BigDecimal.ZERO)),
                    outputOverrides.getOrDefault(vendor,
                            listed.map(ExternalAiCatalogue.Price::outputUsdPerMTok).orElse(BigDecimal.ZERO)));
        };
    }

    private static BigDecimal decimal(String text, BigDecimal fallback) {
        return text == null || text.isBlank() ? fallback : new BigDecimal(text.trim());
    }
}
