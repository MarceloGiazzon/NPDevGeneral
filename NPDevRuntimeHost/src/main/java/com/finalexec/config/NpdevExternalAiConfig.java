package com.finalexec.config;

import com.npdev.adapters.externalai.http.ExternalAiVendorProfile;
import com.npdev.adapters.externalai.http.HttpExternalAiCapabilityAdapter;
import com.npdev.adapters.externalai.inproc.InProcExternalAiCapabilityAdapter;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * ADR-0009: wires the {@link ExternalAiCapabilityContract} adapter by config --
 * {@code npdev.externalai.provider: inproc | http}, defaulting to {@code inproc} (air-gapped
 * paste-transport -- writes a pack to disk, no network egress possible) so an unconfigured app
 * cannot accidentally send anything anywhere. Setting {@code http} switches to the real
 * vendor-calling adapter (D2); vendor API keys come from env vars, never committed (D1, revised
 * 2026-07-26: NVIDIA Build + Gemini, replacing the original OpenAI + xAI Grok answer). Same
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
            @Value("${npdev.externalai.http.nvidia.apiKeyEnvVar:NPDEV_EXTERNALAI_NVIDIA_API_KEY}") String nvidiaKeyEnvVar,
            @Value("${npdev.externalai.http.nvidia.model:meta/llama-3.3-70b-instruct}") String nvidiaModel,
            @Value("${npdev.externalai.http.gemini.apiKeyEnvVar:NPDEV_EXTERNALAI_GEMINI_API_KEY}") String geminiKeyEnvVar,
            @Value("${npdev.externalai.http.gemini.model:gemini-3.5-flash}") String geminiModel,
            @Value("${npdev.externalai.http.anthropic.apiKeyEnvVar:NPDEV_EXTERNALAI_ANTHROPIC_API_KEY}") String anthropicKeyEnvVar,
            @Value("${npdev.externalai.http.anthropic.model:claude-opus-5}") String anthropicModel,
            @Value("${npdev.externalai.http.openai.apiKeyEnvVar:NPDEV_EXTERNALAI_OPENAI_API_KEY}") String openaiKeyEnvVar,
            @Value("${npdev.externalai.http.openai.model:gpt-4o-mini}") String openaiModel
    ) {
        // All four are always CONFIGURED here; only the ones whose key env var is actually set are
        // reachable -- the adapter denies the rest with EGRESS_DENIED_NO_API_KEY rather than sending.
        // So listing a vendor costs nothing and grants nothing; the key is the switch.
        List<ExternalAiVendorProfile> vendors = List.of(
                ExternalAiVendorProfile.anthropic(anthropicKeyEnvVar, anthropicModel),
                ExternalAiVendorProfile.openai(openaiKeyEnvVar, openaiModel),
                ExternalAiVendorProfile.nvidiaBuild(nvidiaKeyEnvVar, nvidiaModel),
                ExternalAiVendorProfile.gemini(geminiKeyEnvVar, geminiModel)
        );
        return new HttpExternalAiCapabilityAdapter(vendors);
    }
}
