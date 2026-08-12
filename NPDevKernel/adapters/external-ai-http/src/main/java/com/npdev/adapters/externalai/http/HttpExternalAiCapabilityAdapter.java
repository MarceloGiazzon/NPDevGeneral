package com.npdev.adapters.externalai.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiEgressDeniedException;
import com.npdev.kernel.ports.ExternalAiGenerationRequest;
import com.npdev.kernel.ports.ExternalAiGenerationResult;
import com.npdev.kernel.ports.ExternalAiPackSubmission;
import com.npdev.kernel.ports.ExternalAiPayload;
import com.npdev.kernel.ports.ExternalAiRunResult;
import com.npdev.kernel.ports.ExternalAiVendorSummary;
import com.npdev.kernel.ports.ExternalAiVerdictRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * ADR-0009 / D2's API-key transport: a real HTTPS call to an external AI vendor. Config-driven
 * per vendor ({@link ExternalAiVendorProfile}) rather than one hardcoded SDK per vendor, since the
 * D1 vendors reduce to two request/response shapes (OpenAI-compatible chat, Gemini generateContent).
 *
 * <p><b>Fail-closed, same as the port's own default:</b> a mission naming a vendor this adapter has
 * no profile for, or whose configured API-key env var is unset, is denied -- it never silently no-ops
 * or sends with an empty key. No real network call happens until both a profile AND a real key exist.</p>
 */
public final class HttpExternalAiCapabilityAdapter implements CapabilityAdapter, ExternalAiCapabilityContract {

    /**
     * Anthropic's required API-version header. It is a dated contract version, not a model version:
     * pinning it is how the request keeps meaning the same thing after the API evolves.
     */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** See the comment at its use site -- caps thinking + response text together, not just the answer. */
    private static final int ANTHROPIC_MAX_TOKENS = 16000;

    private final Map<String, ExternalAiVendorProfile> vendorsById;

    /**
     * The same profiles as {@link #vendorsById}, in the order the caller configured them.
     *
     * <p>This exists because {@code Map.copyOf} does NOT preserve insertion order -- it randomizes
     * iteration per JVM instance. Looking a vendor up by id does not care, but
     * {@link #configuredVendors()} feeds a UI whose first entry is the default selection, so reading
     * the order off the map would change which provider a page pre-selects on every restart.
     */
    private final List<ExternalAiVendorProfile> vendorsInOrder;
    private final HttpClient httpClient;
    private final Function<String, String> apiKeyLookup;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ExternalAiVerdictRecord> verdictsByMissionId = new ConcurrentHashMap<>();

    public HttpExternalAiCapabilityAdapter(List<ExternalAiVendorProfile> vendors) {
        this(vendors, HttpClient.newHttpClient(), System::getenv);
    }

    public HttpExternalAiCapabilityAdapter(
            List<ExternalAiVendorProfile> vendors,
            HttpClient httpClient,
            Function<String, String> apiKeyLookup
    ) {
        Objects.requireNonNull(vendors, "vendors");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.apiKeyLookup = Objects.requireNonNull(apiKeyLookup, "apiKeyLookup");
        Map<String, ExternalAiVendorProfile> byId = new LinkedHashMap<>();
        for (ExternalAiVendorProfile vendor : vendors) {
            if (byId.putIfAbsent(vendor.vendorId(), vendor) != null) {
                throw new IllegalArgumentException("Duplicate vendorId in configuration: " + vendor.vendorId());
            }
        }
        this.vendorsById = Map.copyOf(byId);
        this.vendorsInOrder = List.copyOf(byId.values());
    }

    @Override
    public String adapterId() {
        return "external-ai-http";
    }

    @Override
    public String capability() {
        return "externalAi";
    }

    @Override
    public String capabilityType() {
        return "ExternalAiCapability";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        return switch (call.operation()) {
            case "submitPack" -> CapabilityResult.success(submitPackPayload(call.args()));
            case "ingestVerdict" -> CapabilityResult.success(ingestVerdictPayload(call.args()));
            default -> CapabilityResult.failure(
                    "EXTERNAL_AI_OPERATION_UNSUPPORTED",
                    "Unsupported externalAi operation: " + call.operation(),
                    CapabilityErrorKind.CONTRACT,
                    Map.of("operation", call.operation())
            );
        };
    }

    @Override
    public ExternalAiRunResult submitPack(ExternalAiPackSubmission submission) {
        String mission = "mission " + submission.missionId();
        ExternalAiVendorProfile profile = requireConfiguredVendor(submission.vendorId(), mission);
        String apiKey = requireApiKey(profile, mission);

        HttpResponse<String> response = send(
                buildGenerationRequest(profile, apiKey, profile.model(), null, submission.packJson()), profile);

        String verdictJson = extractAssistantText(profile.requestFormat(), response.body());
        ExternalAiVerdictRecord record = validateAndWrap(
                submission.missionId(), profile.vendorId(), profile.model(), verdictJson);
        verdictsByMissionId.put(submission.missionId(), record);

        return ExternalAiRunResult.run(submission.missionId(), submission.packManifestSha256(), profile.vendorId());
    }

    @Override
    public ExternalAiGenerationResult generateText(ExternalAiGenerationRequest request) {
        ExternalAiVendorProfile profile = requireConfiguredVendor(request.vendorId(), "prompt");
        String apiKey = requireApiKey(profile, "prompt");
        String model = (request.model() == null || request.model().isBlank())
                ? profile.model()
                : request.model();

        HttpResponse<String> response = send(
                buildGenerationRequest(profile, apiKey, model, request.effort(), request.prompt()), profile);
        return new ExternalAiGenerationResult(
                profile.vendorId(), model, extractAssistantText(profile.requestFormat(), response.body()),
                response.body());
    }

    @Override
    public List<ExternalAiVendorSummary> configuredVendors() {
        return vendorsInOrder.stream()
                .map(profile -> new ExternalAiVendorSummary(
                        profile.vendorId(),
                        profile.model(),
                        profile.apiKeyEnvVar(),
                        isPresent(apiKeyLookup.apply(profile.apiKeyEnvVar())),
                        profile.supportsEffort()))
                .toList();
    }

    private static boolean isPresent(String apiKey) {
        return apiKey != null && !apiKey.isBlank();
    }

    private ExternalAiVendorProfile requireConfiguredVendor(String vendorId, String what) {
        ExternalAiVendorProfile profile = vendorsById.get(vendorId);
        if (profile == null) {
            throw new ExternalAiEgressDeniedException(
                    "EGRESS_DENIED_NO_VENDOR",
                    "No configured vendor '" + vendorId + "' for this " + what
                            + "; denying rather than sending unchecked.");
        }
        return profile;
    }

    private String requireApiKey(ExternalAiVendorProfile profile, String what) {
        String apiKey = apiKeyLookup.apply(profile.apiKeyEnvVar());
        if (!isPresent(apiKey)) {
            throw new ExternalAiEgressDeniedException(
                    "EGRESS_DENIED_NO_API_KEY",
                    "No API key configured (env var " + profile.apiKeyEnvVar() + ") for vendor '"
                            + profile.vendorId() + "'; denying this " + what
                            + " rather than sending unchecked.");
        }
        return apiKey;
    }

    private HttpResponse<String> send(HttpRequest request, ExternalAiVendorProfile profile) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "External AI HTTP call failed for vendor " + profile.vendorId(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "External AI HTTP call interrupted for vendor " + profile.vendorId(), e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "External AI vendor " + profile.vendorId() + " returned HTTP " + response.statusCode()
                            + ": " + response.body());
        }
        return response;
    }

    @Override
    public ExternalAiVerdictRecord ingestVerdict(String missionId, String vendorId, String verdictJson) {
        String model = Optional.ofNullable(vendorsById.get(vendorId)).map(ExternalAiVendorProfile::model).orElse(null);
        ExternalAiVerdictRecord record = validateAndWrap(missionId, vendorId, model, verdictJson);
        verdictsByMissionId.put(missionId, record);
        return record;
    }

    public Optional<ExternalAiVerdictRecord> verdictFor(String missionId) {
        return Optional.ofNullable(verdictsByMissionId.get(missionId));
    }

    private Map<String, Object> submitPackPayload(List<Object> args) {
        ExternalAiPackSubmission submission = ExternalAiPayload.parseSubmission(args);
        ExternalAiRunResult result = submitPack(submission);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("missionId", result.missionId());
        payload.put("runStatus", result.runStatus());
        payload.put("packManifestSha256", result.packManifestSha256());
        payload.put("vendorId", result.vendorId());
        return payload;
    }

    private Map<String, Object> ingestVerdictPayload(List<Object> args) {
        if (args.size() < 3) {
            throw new IllegalArgumentException("ingestVerdict requires missionId, vendorId, verdictJson");
        }
        ExternalAiVerdictRecord record = ingestVerdict(
                String.valueOf(args.get(0)), String.valueOf(args.get(1)), String.valueOf(args.get(2)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("missionId", record.missionId());
        payload.put("vendorId", record.vendorId());
        payload.put("model", record.model());
        payload.put("recordKind", ExternalAiVerdictRecord.RECORD_KIND);
        return payload;
    }

    /**
     * Build one vendor request from a single user prompt. Both callers -- {@code submitPack}'s
     * review pack and {@code generateText}'s free-form prompt -- are exactly that at the wire level,
     * so they share this method rather than keeping two copies of the auth/URL/body triple that
     * could drift when a vendor is added.
     *
     * <p>{@code effort} is honoured only where the vendor has a real equivalent (see
     * {@link ExternalAiVendorProfile#supportsEffort()}); elsewhere it is dropped rather than
     * translated into a parameter the vendor would reject.
     */
    private HttpRequest buildGenerationRequest(
            ExternalAiVendorProfile profile, String apiKey, String model, String effort, String prompt) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        String body;
        try {
            body = switch (profile.requestFormat()) {
                case OPENAI_CHAT -> {
                    builder.uri(URI.create(profile.baseUrl()))
                            .header("Authorization", "Bearer " + apiKey);
                    yield objectMapper.writeValueAsString(Map.of(
                            "model", model,
                            "messages", List.of(Map.of("role", "user", "content", prompt))
                    ));
                }
                case GEMINI_GENERATE_CONTENT -> {
                    builder.uri(URI.create(profile.baseUrl() + "/models/" + model + ":generateContent"))
                            .header("x-goog-api-key", apiKey);
                    yield objectMapper.writeValueAsString(Map.of(
                            "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
                    ));
                }
                case ANTHROPIC_MESSAGES -> {
                    builder.uri(URI.create(profile.baseUrl()))
                            .header("x-api-key", apiKey)
                            .header("anthropic-version", ANTHROPIC_VERSION);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("model", model);
                    // Required by the Messages API, and a hard cap on thinking PLUS response text --
                    // not just the answer. Sized for a full model-change explanation rather than the
                    // 4096 a chat reply needs, because truncation here looks like a bad answer.
                    payload.put("max_tokens", ANTHROPIC_MAX_TOKENS);
                    payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
                    if (effort != null && !effort.isBlank()) {
                        payload.put("output_config", Map.of("effort", effort));
                    }
                    yield objectMapper.writeValueAsString(payload);
                }
            };
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed building external AI request body", e);
        }
        return builder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Pull the assistant's text out of whichever response shape the vendor uses.
     *
     * <p>Anthropic returns a content ARRAY whose blocks are not all text -- with thinking on (the
     * default on current models) {@code content[0]} can be a thinking block whose {@code text} field
     * is absent, so indexing block 0 blindly finds nothing on a perfectly good response. This walks
     * to the first {@code type: "text"} block instead.
     */
    private String extractAssistantText(ExternalAiRequestFormat format, String responseBody) {
        JsonNode root = readTree(responseBody);
        JsonNode textNode = switch (format) {
            case OPENAI_CHAT -> root.path("choices").path(0).path("message").path("content");
            case GEMINI_GENERATE_CONTENT ->
                    root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
            case ANTHROPIC_MESSAGES -> firstAnthropicTextBlock(root);
        };
        if (!textNode.isTextual()) {
            throw new IllegalStateException(
                    "External AI vendor response did not contain the expected text field for format "
                            + format + ": " + responseBody);
        }
        return textNode.asText();
    }

    private static JsonNode firstAnthropicTextBlock(JsonNode root) {
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                return block.path("text");
            }
        }
        return root.path("content").path(0).path("text");
    }

    private ExternalAiVerdictRecord validateAndWrap(String missionId, String vendorId, String model, String verdictJson) {
        JsonNode node = readTree(verdictJson);
        requireField(node, "recordKind", ExternalAiVerdictRecord.RECORD_KIND);
        requireBooleanField(node, "noRepoAccess", true);
        requireBooleanField(node, "autoApplied", false);
        return new ExternalAiVerdictRecord(missionId, null, vendorId, model, verdictJson);
    }

    private JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new IllegalArgumentException("Not valid JSON: " + json, e);
        }
    }

    private void requireField(JsonNode node, String field, String expected) {
        String actual = node.path(field).asText(null);
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    "verdictJson." + field + " must be '" + expected + "', got: " + actual);
        }
    }

    private void requireBooleanField(JsonNode node, String field, boolean expected) {
        JsonNode value = node.path(field);
        if (!value.isBoolean() || value.asBoolean() != expected) {
            throw new IllegalArgumentException(
                    "verdictJson." + field + " must be " + expected + ", got: " + value);
        }
    }
}
