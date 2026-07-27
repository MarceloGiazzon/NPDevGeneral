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
import com.npdev.kernel.ports.ExternalAiPackSubmission;
import com.npdev.kernel.ports.ExternalAiPayload;
import com.npdev.kernel.ports.ExternalAiRunResult;
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

    private final Map<String, ExternalAiVendorProfile> vendorsById;
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
        ExternalAiVendorProfile profile = vendorsById.get(submission.vendorId());
        if (profile == null) {
            throw new ExternalAiEgressDeniedException(
                    "EGRESS_DENIED_NO_VENDOR",
                    "No configured vendor '" + submission.vendorId() + "' for mission "
                            + submission.missionId() + "; denying rather than sending unchecked.");
        }
        String apiKey = apiKeyLookup.apply(profile.apiKeyEnvVar());
        if (apiKey == null || apiKey.isBlank()) {
            throw new ExternalAiEgressDeniedException(
                    "EGRESS_DENIED_NO_API_KEY",
                    "No API key configured (env var " + profile.apiKeyEnvVar() + ") for vendor '"
                            + profile.vendorId() + "'; denying mission " + submission.missionId()
                            + " rather than sending unchecked.");
        }

        HttpRequest request = buildRequest(profile, apiKey, submission.packJson());
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

        String verdictJson = extractVerdictText(profile.requestFormat(), response.body());
        ExternalAiVerdictRecord record = validateAndWrap(
                submission.missionId(), profile.vendorId(), profile.model(), verdictJson);
        verdictsByMissionId.put(submission.missionId(), record);

        return ExternalAiRunResult.run(submission.missionId(), submission.packManifestSha256(), profile.vendorId());
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

    private HttpRequest buildRequest(ExternalAiVendorProfile profile, String apiKey, String packJson) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        String body;
        try {
            body = switch (profile.requestFormat()) {
                case OPENAI_CHAT -> {
                    builder.uri(URI.create(profile.baseUrl()))
                            .header("Authorization", "Bearer " + apiKey);
                    yield objectMapper.writeValueAsString(Map.of(
                            "model", profile.model(),
                            "messages", List.of(Map.of("role", "user", "content", packJson))
                    ));
                }
                case GEMINI_GENERATE_CONTENT -> {
                    builder.uri(URI.create(profile.baseUrl() + "/models/" + profile.model() + ":generateContent"))
                            .header("x-goog-api-key", apiKey);
                    yield objectMapper.writeValueAsString(Map.of(
                            "contents", List.of(Map.of("parts", List.of(Map.of("text", packJson))))
                    ));
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

    private String extractVerdictText(ExternalAiRequestFormat format, String responseBody) {
        JsonNode root = readTree(responseBody);
        JsonNode textNode = switch (format) {
            case OPENAI_CHAT -> root.path("choices").path(0).path("message").path("content");
            case GEMINI_GENERATE_CONTENT ->
                    root.path("candidates").path(0).path("content").path("parts").path(0).path("text");
        };
        if (!textNode.isTextual()) {
            throw new IllegalStateException(
                    "External AI vendor response did not contain the expected text field for format "
                            + format + ": " + responseBody);
        }
        return textNode.asText();
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
