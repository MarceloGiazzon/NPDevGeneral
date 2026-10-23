package com.finalexec.config;

import com.finalexec.ai.ExternalAiPromptRunner;
import com.npdev.adapters.externalai.http.ExternalAiCatalogue;
import com.npdev.adapters.externalai.http.ExternalAiVendorProfile;
import com.npdev.adapters.externalai.http.HttpExternalAiCapabilityAdapter;
import com.npdev.adapters.externalai.inproc.InProcExternalAiCapabilityAdapter;
import com.npdev.dsl.v1.compiled.CompiledExternalAi;
import com.npdev.dsl.v1.compiled.CompiledExternalAiProvider;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.ports.ExternalAiGenerationRequest;
import com.npdev.kernel.ports.ExternalAiGenerationResult;
import com.npdev.kernel.ports.ExternalAiPackSubmission;
import com.npdev.kernel.ports.ExternalAiRunResult;
import com.npdev.kernel.ports.ExternalAiStructuredRequest;
import com.npdev.kernel.ports.ExternalAiStructuredResult;
import com.npdev.kernel.ports.ExternalAiToolChatRequest;
import com.npdev.kernel.ports.ExternalAiToolChatResult;
import com.npdev.kernel.ports.ExternalAiVendorSummary;
import com.npdev.kernel.ports.ExternalAiVerdictRecord;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

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

    /** Parsed once: the shipped catalogue never changes while the app runs. */
    private static final Supplier<ExternalAiCatalogue> BUNDLED = memoize(ExternalAiCatalogue::bundled);

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
            ModelHolder modelHolder,
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
        // AIP-3 (3.6, D4): plus the model's own externalAi.providers. Rebuilt on a model reload (REG-208),
        // so a provider added by a hot swap is served without a restart.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        Function<CompiledModel, ExternalAiCapabilityContract> build = model -> new HttpExternalAiCapabilityAdapter(
                catalogueFor(model).appVendorProfiles(environment::getProperty),
                httpClient,
                System::getenv,
                Duration.ofMillis(requestTimeoutMs),
                maxRetries,
                Duration.ofMillis(retryBackoffMs)
        );
        AtomicReference<ExternalAiCapabilityContract> current = new AtomicReference<>(build.apply(modelHolder.get()));
        modelHolder.addReloadListener((previous, next) -> current.set(build.apply(next)));
        return new Delegating(current::get);
    }

    /** The shipped catalogue plus the model's own {@code externalAi.providers}; the shipped one alone without. */
    static ExternalAiCatalogue catalogueFor(CompiledModel model) {
        CompiledExternalAi externalAi = model == null ? null : model.getExternalAi();
        if (externalAi == null || externalAi.getProviders().isEmpty()) {
            return BUNDLED.get();
        }
        List<ExternalAiCatalogue.Vendor> own = new java.util.ArrayList<>();
        Map<String, ExternalAiCatalogue.Price> prices = new HashMap<>();
        for (CompiledExternalAiProvider provider : externalAi.getProviders()) {
            own.add(new ExternalAiCatalogue.Vendor(provider.id(), "own", provider.protocol(), provider.baseUrl(),
                    provider.keyEnvVar(), provider.defaultModel()));
            if (provider.inputUsdPerMTok() != null && provider.outputUsdPerMTok() != null) {
                prices.put(provider.id(), new ExternalAiCatalogue.Price(provider.inputUsdPerMTok(),
                        provider.outputUsdPerMTok()));
            }
        }
        return BUNDLED.get().withOwnProviders(own, prices);
    }

    /** Forwards every call to the adapter built for the current model. */
    static final class Delegating implements ExternalAiCapabilityContract {
        private final Supplier<ExternalAiCapabilityContract> target;

        Delegating(Supplier<ExternalAiCapabilityContract> target) {
            this.target = target;
        }

        @Override
        public ExternalAiRunResult submitPack(ExternalAiPackSubmission submission) {
            return target.get().submitPack(submission);
        }

        @Override
        public ExternalAiVerdictRecord ingestVerdict(String missionId, String vendorId, String verdictJson) {
            return target.get().ingestVerdict(missionId, vendorId, verdictJson);
        }

        @Override
        public ExternalAiGenerationResult generateText(ExternalAiGenerationRequest request) {
            return target.get().generateText(request);
        }

        @Override
        public ExternalAiStructuredResult generateStructured(ExternalAiStructuredRequest request) {
            return target.get().generateStructured(request);
        }

        @Override
        public ExternalAiToolChatResult chatWithTools(ExternalAiToolChatRequest request) {
            return target.get().chatWithTools(request);
        }

        @Override
        public List<ExternalAiVendorSummary> configuredVendors() {
            return target.get().configuredVendors();
        }
    }

    private static <T> Supplier<T> memoize(Supplier<T> source) {
        AtomicReference<T> value = new AtomicReference<>();
        return () -> {
            T existing = value.get();
            if (existing == null) {
                value.compareAndSet(null, source.get());
                existing = value.get();
            }
            return existing;
        };
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
                // Per call, for the model as it is now: an own provider added by a reload is priced too.
                (vendor, model) -> cataloguePricing(catalogueFor(modelHolder.get()), environment::getProperty)
                        .priceOf(vendor, model),
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
