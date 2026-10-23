package com.npdev.adapters.externalai.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The AI catalogue NPDev ships ({@code NPDevCli/ai-providers.json}, schema
 * {@code schemas/ai/ai-config.schema.json}), bundled into this adapter's jar as {@value #RESOURCE} by
 * its build -- a copy made at build time from the one file {@code npdev ai} reads, never a second
 * hand-kept vendor list (AIP-3, step 3.1). A generated app builds its vendor profiles from it: every
 * provider marked {@code appVendor}, each driven by its {@code protocol}, so an OpenAI-compatible
 * provider (Groq, Cerebras, OpenRouter, Mistral, a local Ollama) needs no Java of its own.
 *
 * <p>The SSRF rule is unchanged: endpoints come from here, server-side, and a caller still names only
 * a vendor id. A catalogue an app vendor cannot be served from -- a protocol this adapter does not
 * implement, or wire options it does not apply (fixed headers other than {@code anthropic-version},
 * query parameters, a model in the path) -- is refused at load time, never half-applied.</p>
 */
public final class ExternalAiCatalogue {

    public static final String RESOURCE = "npdev/ai-providers.json";

    /**
     * The closed protocol list of the catalogue, mapped to the wire format this adapter speaks. The
     * Python client ({@code NPDevCli/npdev_ai_client.py}) implements the same three; twin-pair rule
     * {@code ai-protocols-three-place} keeps the catalogue, that client and this map in step.
     */
    static final Map<String, ExternalAiRequestFormat> PROTOCOLS = Map.of(
            "openai-chat", ExternalAiRequestFormat.OPENAI_CHAT,
            "anthropic-messages", ExternalAiRequestFormat.ANTHROPIC_MESSAGES,
            "gemini-native", ExternalAiRequestFormat.GEMINI_GENERATE_CONTENT);

    /** The one fixed header this adapter sets itself (see HttpExternalAiCapabilityAdapter). */
    private static final Set<String> HEADERS_THE_ADAPTER_SETS = Set.of("anthropic-version");

    /** USD per million tokens, as the catalogue's model entries carry them. */
    public record Price(BigDecimal inputUsdPerMTok, BigDecimal outputUsdPerMTok) {
    }

    /** One provider a generated app may call. {@code keyEnvVar} is null for a keyless (local) one. */
    public record Vendor(String id, String kind, String protocol, String baseUrl, String keyEnvVar,
                         String defaultModel) {
    }

    private final List<String> protocols;
    private final List<Vendor> appVendors;
    private final Map<String, Price> prices;

    private ExternalAiCatalogue(List<String> protocols, List<Vendor> appVendors, Map<String, Price> prices) {
        this.protocols = List.copyOf(protocols);
        this.appVendors = List.copyOf(appVendors);
        this.prices = Map.copyOf(prices);
    }

    /** The copy bundled into this jar. Missing means a broken build, so it fails loudly. */
    public static ExternalAiCatalogue bundled() {
        try (InputStream in = ExternalAiCatalogue.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the classpath -- the external-ai-http "
                        + "build copies it from NPDevCli/ai-providers.json; rebuild the adapter jar");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + RESOURCE, e);
        }
    }

    public static ExternalAiCatalogue parse(String json) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(json);
        } catch (IOException e) {
            throw new IllegalStateException("the AI catalogue is not valid JSON: " + e.getMessage(), e);
        }
        if (!"npdev-ai-config.v1".equals(root.path("schemaVersion").asText())) {
            throw new IllegalStateException("the AI catalogue is not an npdev-ai-config.v1 file");
        }
        List<String> protocols = new ArrayList<>();
        root.path("protocols").forEach(p -> protocols.add(p.path("id").asText()));
        List<Vendor> vendors = new ArrayList<>();
        for (JsonNode p : root.path("providers")) {
            if (!p.path("appVendor").asBoolean(false) || p.path("disabled").asBoolean(false)) {
                continue;
            }
            String id = p.path("id").asText();
            String protocol = p.path("protocol").asText();
            if (!PROTOCOLS.containsKey(protocol) || !protocols.contains(protocol)) {
                throw new IllegalStateException("app vendor '" + id + "' uses protocol '" + protocol
                        + "', which external-ai-http does not implement (supported: " + PROTOCOLS.keySet() + ")");
            }
            List<String> unsupported = new ArrayList<>();
            p.path("extraHeaders").fieldNames().forEachRemaining(h -> {
                if (!HEADERS_THE_ADAPTER_SETS.contains(h)) {
                    unsupported.add("extraHeaders." + h);
                }
            });
            if (p.path("queryParams").size() > 0) {
                unsupported.add("queryParams");
            }
            if (p.path("modelInPath").asBoolean(false)) {
                unsupported.add("modelInPath");
            }
            if (!unsupported.isEmpty()) {
                throw new IllegalStateException("app vendor '" + id + "' needs " + unsupported
                        + ", which external-ai-http does not apply -- it cannot be an app vendor yet");
            }
            vendors.add(new Vendor(id, p.path("kind").asText(), protocol,
                    p.path("baseUrl").asText().replaceAll("/+$", ""), textOrNull(p.path("keyEnvVar")),
                    textOrNull(p.path("defaultModel"))));
        }
        Map<String, Price> prices = new LinkedHashMap<>();
        for (JsonNode m : root.path("models")) {
            JsonNode in = m.path("price").path("in");
            JsonNode out = m.path("price").path("out");
            if (in.isNumber() && out.isNumber()) {
                prices.put(m.path("provider").asText() + "/" + m.path("id").asText(),
                        new Price(in.decimalValue(), out.decimalValue()));
            }
        }
        return new ExternalAiCatalogue(protocols, vendors, prices);
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    public List<String> protocols() {
        return protocols;
    }

    public List<Vendor> appVendors() {
        return appVendors;
    }

    public Optional<Vendor> appVendor(String id) {
        return appVendors.stream().filter(v -> v.id().equals(id)).findFirst();
    }

    /** The catalogue price of one model, when both halves are known. */
    public Optional<Price> price(String vendorId, String model) {
        return Optional.ofNullable(prices.get(vendorId + "/" + model));
    }

    /**
     * The adapter's vendor profiles. {@code property} reads the operator's overrides --
     * {@code npdev.externalai.http.<id>.model} and {@code npdev.externalai.http.<id>.apiKeyEnvVar} --
     * and answers null when one is not set. A vendor with neither a catalogue default model nor a
     * model override is left out: there is nothing to call.
     */
    public List<ExternalAiVendorProfile> appVendorProfiles(Function<String, String> property) {
        List<ExternalAiVendorProfile> out = new ArrayList<>();
        for (Vendor vendor : appVendors) {
            String model = firstNonBlank(property.apply("npdev.externalai.http." + vendor.id() + ".model"),
                    vendor.defaultModel());
            if (model == null) {
                continue;
            }
            String keyEnvVar = firstNonBlank(property.apply("npdev.externalai.http." + vendor.id() + ".apiKeyEnvVar"),
                    vendor.keyEnvVar());
            out.add(new ExternalAiVendorProfile(vendor.id(), endpointFor(vendor.protocol(), vendor.baseUrl()), model,
                    keyEnvVar, PROTOCOLS.get(vendor.protocol())));
        }
        return out;
    }

    /** The URL the adapter posts to: Gemini's native API gets its model path appended at call time. */
    static String endpointFor(String protocol, String baseUrl) {
        return switch (protocol) {
            case "openai-chat" -> baseUrl + "/chat/completions";
            case "anthropic-messages" -> baseUrl + "/messages";
            case "gemini-native" -> baseUrl;
            default -> throw new IllegalStateException("unknown protocol " + protocol);
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b != null && !b.isBlank() ? b : null;
    }
}
