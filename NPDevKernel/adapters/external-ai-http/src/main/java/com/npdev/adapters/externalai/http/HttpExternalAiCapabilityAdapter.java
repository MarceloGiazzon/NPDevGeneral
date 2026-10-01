package com.npdev.adapters.externalai.http;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.ports.ExternalAiCapabilityContract;
import com.npdev.kernel.ports.ExternalAiChatTurn;
import com.npdev.kernel.ports.ExternalAiEgressDeniedException;
import com.npdev.kernel.ports.ExternalAiGenerationRequest;
import com.npdev.kernel.ports.ExternalAiGenerationResult;
import com.npdev.kernel.ports.ExternalAiPackSubmission;
import com.npdev.kernel.ports.ExternalAiPayload;
import com.npdev.kernel.ports.ExternalAiRunResult;
import com.npdev.kernel.ports.ExternalAiToolCall;
import com.npdev.kernel.ports.ExternalAiToolChatRequest;
import com.npdev.kernel.ports.ExternalAiToolChatResult;
import com.npdev.kernel.ports.ExternalAiToolSpec;
import com.npdev.kernel.ports.ExternalAiVendorSummary;
import com.npdev.kernel.ports.ExternalAiVerdictRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
 *
 * <p><b>R8d (RUN-4): this adapter owns its own deadline, on purpose.</b> Before this, neither this
 * class nor {@code mail-smtp} set a connect/request timeout, and {@code CapabilityExecutionPolicy
 * .defaults()} returns zeros for timeout/retry -- so a vendor that accepted the TCP connection and
 * then never responded hung the calling thread forever. The fix lives entirely here: a per-request
 * {@link HttpRequest.Builder#timeout} plus a bounded, adapter-local retry loop in {@link #send}, both
 * enforced on the calling thread.</p>
 *
 * <p><b>R2.6 (RUN-4, 2026-08-19): the kernel now also has a backstop, and this adapter's own
 * deadline is still the one that matters here.</b> {@code KernelRunner} resolves an undeclared
 * capability timeout to a 600s hang backstop and runs the invocation on its own
 * {@code npdev-capability-N} pool -- deliberately NOT {@code ForkJoinPool.commonPool()}, which does
 * not carry the calling thread's thread-local state (the trace in {@code ledger/items/RUN-4.yml}
 * found a real kernel safety guard being defeated by exactly that). 600s sits far above this
 * adapter's own worst case -- {@code maxRetries}(2)+1 attempts x a 120s request timeout plus
 * backoff -- so the backstop catches a genuine hang without ever truncating a working call. Keep it
 * that way: if this adapter's timeout or retry budget grows, check it against that backstop.</p>
 */
public final class HttpExternalAiCapabilityAdapter implements CapabilityAdapter, ExternalAiCapabilityContract {

    /**
     * Anthropic's required API-version header. It is a dated contract version, not a model version:
     * pinning it is how the request keeps meaning the same thing after the API evolves.
     */
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** See the comment at its use site -- caps thinking + response text together, not just the answer. */
    private static final int ANTHROPIC_MAX_TOKENS = 16000;

    /**
     * R8d (RUN-4): connect timeout for the HttpClient this adapter builds itself (the 1-arg
     * constructor). A caller-supplied HttpClient (the 3-/6-arg constructors -- every test uses one)
     * is used exactly as given; this adapter never reaches into someone else's HttpClient to mutate
     * it, only into the HttpRequest it builds (see {@link #DEFAULT_REQUEST_TIMEOUT}).
     */
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * R8d (RUN-4): per-request deadline, applied via {@link HttpRequest.Builder#timeout} on every
     * request regardless of which HttpClient sends it -- this is what actually bounds a hung vendor
     * call, since a caller-supplied HttpClient's own connectTimeout only covers the TCP handshake,
     * not a connection that opens fine and then never answers. Generous on purpose: a real vendor
     * call (especially "high" reasoning effort) can legitimately run tens of seconds, and the goal is
     * a deadline that exists at all, not the shortest one that could.
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(120);

    /**
     * R8d (RUN-4): bounded retry, adapter-local, for transport-level failures and 429/5xx responses
     * only -- never for a deny (no vendor/no key) or a contract-shape failure (bad verdict JSON),
     * which a retry cannot fix. See {@link #isRetryableStatus}.
     */
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final Duration DEFAULT_RETRY_BACKOFF = Duration.ofMillis(500);

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
    private final Duration requestTimeout;
    private final int maxRetries;
    private final Duration retryBackoff;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ExternalAiVerdictRecord> verdictsByMissionId = new ConcurrentHashMap<>();

    public HttpExternalAiCapabilityAdapter(List<ExternalAiVendorProfile> vendors) {
        this(
                vendors,
                HttpClient.newBuilder().connectTimeout(DEFAULT_CONNECT_TIMEOUT).build(),
                System::getenv,
                DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_MAX_RETRIES,
                DEFAULT_RETRY_BACKOFF
        );
    }

    public HttpExternalAiCapabilityAdapter(
            List<ExternalAiVendorProfile> vendors,
            HttpClient httpClient,
            Function<String, String> apiKeyLookup
    ) {
        this(vendors, httpClient, apiKeyLookup, DEFAULT_REQUEST_TIMEOUT, DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF);
    }

    /**
     * R8d (RUN-4) full constructor: an adapter-owned deadline/retry policy. {@code requestTimeout}
     * bounds a single attempt (connect + full response); {@code maxRetries} is the number of retries
     * AFTER the first attempt (0 = try once, never retry); {@code retryBackoff} is the base delay,
     * multiplied by the attempt number (linear backoff) between retries.
     */
    public HttpExternalAiCapabilityAdapter(
            List<ExternalAiVendorProfile> vendors,
            HttpClient httpClient,
            Function<String, String> apiKeyLookup,
            Duration requestTimeout,
            int maxRetries,
            Duration retryBackoff
    ) {
        Objects.requireNonNull(vendors, "vendors");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.apiKeyLookup = Objects.requireNonNull(apiKeyLookup, "apiKeyLookup");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0");
        }
        this.maxRetries = maxRetries;
        this.retryBackoff = Objects.requireNonNull(retryBackoff, "retryBackoff");
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
    public ExternalAiToolChatResult chatWithTools(ExternalAiToolChatRequest request) {
        ExternalAiVendorProfile profile = requireConfiguredVendor(request.vendorId(), "tool chat");
        String apiKey = requireApiKey(profile, "tool chat");
        String model = (request.model() == null || request.model().isBlank()) ? profile.model() : request.model();

        HttpResponse<String> response = send(buildToolChatRequest(profile, apiKey, model, request), profile);
        ExternalAiChatTurn assistantTurn = parseToolChatResponse(profile.requestFormat(), response.body());
        return new ExternalAiToolChatResult(profile.vendorId(), model, assistantTurn, response.body());
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

    /**
     * R8d (RUN-4): bounded retry loop, entirely local to this adapter. Retries only transport-level
     * failures ({@link IOException}, which covers {@code HttpTimeoutException} when {@link
     * #requestTimeout} expires) and 429/5xx responses ({@link #isRetryableStatus}) -- never a 4xx
     * contract failure, which a retry cannot fix. The same {@code request} object is safe to resend:
     * its body publisher is built from a fixed in-memory string (see {@link #buildGenerationRequest}),
     * not a one-shot stream.
     */
    private HttpResponse<String> send(HttpRequest request, ExternalAiVendorProfile profile) {
        RuntimeException lastFailure = null;
        int totalAttempts = maxRetries + 1;
        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            try {
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (isRetryableStatus(response.statusCode()) && attempt < totalAttempts) {
                    lastFailure = new IllegalStateException(
                            "External AI vendor " + profile.vendorId() + " returned retryable HTTP "
                                    + response.statusCode() + " on attempt " + attempt + "/" + totalAttempts
                                    + ": " + response.body());
                    backoff(attempt);
                    continue;
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException(
                            "External AI vendor " + profile.vendorId() + " returned HTTP " + response.statusCode()
                                    + ": " + response.body());
                }
                return response;
            } catch (IOException e) {
                lastFailure = new UncheckedIOException(
                        "External AI HTTP call failed for vendor " + profile.vendorId()
                                + " on attempt " + attempt + "/" + totalAttempts
                                + " (requestTimeout=" + requestTimeout + ")", e);
                if (attempt >= totalAttempts) {
                    throw lastFailure;
                }
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "External AI HTTP call interrupted for vendor " + profile.vendorId(), e);
            }
        }
        // Unreachable in practice (the loop above always returns or throws on its last iteration),
        // but the compiler cannot prove that from a non-constant loop bound.
        throw lastFailure != null
                ? lastFailure
                : new IllegalStateException("External AI HTTP call exhausted retries with no recorded failure");
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private void backoff(int attempt) {
        long delayMs = retryBackoff.toMillis() * attempt;
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("External AI HTTP retry backoff interrupted", e);
        }
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
     * AGENT-1 (A2.2): the URI + auth headers triple, shared by {@link #buildGenerationRequest} and
     * {@link #buildToolChatRequest} so the two never drift when a vendor is added or a header
     * changes.
     */
    private void applyUriAndAuth(
            HttpRequest.Builder builder, ExternalAiVendorProfile profile, String apiKey, String model) {
        switch (profile.requestFormat()) {
            case OPENAI_CHAT -> builder.uri(URI.create(profile.baseUrl()))
                    .header("Authorization", "Bearer " + apiKey);
            case GEMINI_GENERATE_CONTENT -> builder
                    .uri(URI.create(profile.baseUrl() + "/models/" + model + ":generateContent"))
                    .header("x-goog-api-key", apiKey);
            case ANTHROPIC_MESSAGES -> builder.uri(URI.create(profile.baseUrl()))
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION);
        }
    }

    /**
     * AGENT-1 (A2.2): builds one tool-calling chat request body, format-specific. Reuses
     * {@link #applyUriAndAuth} for the URI/auth triple and {@link #send} for retries/timeouts
     * (both free). See {@code wire-formats.md} section 1 for the exact shapes.
     */
    private HttpRequest buildToolChatRequest(
            ExternalAiVendorProfile profile, String apiKey, String model, ExternalAiToolChatRequest request) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        applyUriAndAuth(builder, profile, apiKey, model);
        String body;
        try {
            body = switch (profile.requestFormat()) {
                case GEMINI_GENERATE_CONTENT -> buildGeminiToolChatBody(model, request);
                case OPENAI_CHAT -> buildOpenAiToolChatBody(model, request);
                case ANTHROPIC_MESSAGES -> buildAnthropicToolChatBody(model, request);
            };
        } catch (IOException e) {
            throw new IllegalStateException("Failed building external AI tool-chat request body", e);
        }
        return builder
                .header("Content-Type", "application/json")
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /**
     * AGENT-1 (A2.3): assistant turns with {@code vendorRaw} are replayed VERBATIM (thought
     * signatures survive); consecutive {@code tool} turns are merged into one {@code user} content
     * with several {@code functionResponse} parts (Gemini requires this).
     * {@code functionResponse.response} must be a JSON object -- {@code AgentApiExecutor} guarantees
     * every {@code resultJson} already is one.
     */
    private String buildGeminiToolChatBody(String model, ExternalAiToolChatRequest request) throws IOException {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        if (hasText(request.systemInstruction())) {
            ObjectNode systemInstruction = JsonNodeFactory.instance.objectNode();
            ArrayNode parts = JsonNodeFactory.instance.arrayNode();
            parts.add(JsonNodeFactory.instance.objectNode().put("text", request.systemInstruction()));
            systemInstruction.set("parts", parts);
            root.set("systemInstruction", systemInstruction);
        }
        ArrayNode contents = JsonNodeFactory.instance.arrayNode();
        List<ExternalAiChatTurn> turns = request.turns();
        int i = 0;
        while (i < turns.size()) {
            ExternalAiChatTurn turn = turns.get(i);
            if ("tool".equals(turn.role())) {
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("role", "user");
                ArrayNode parts = JsonNodeFactory.instance.arrayNode();
                while (i < turns.size() && "tool".equals(turns.get(i).role())) {
                    ExternalAiChatTurn toolTurn = turns.get(i);
                    ObjectNode functionResponse = JsonNodeFactory.instance.objectNode();
                    functionResponse.put("name", toolTurn.toolName());
                    functionResponse.set("response", hasText(toolTurn.resultJson())
                            ? readTree(toolTurn.resultJson()) : JsonNodeFactory.instance.objectNode());
                    ObjectNode partNode = JsonNodeFactory.instance.objectNode();
                    partNode.set("functionResponse", functionResponse);
                    parts.add(partNode);
                    i++;
                }
                node.set("parts", parts);
                contents.add(node);
            } else if ("assistant".equals(turn.role())) {
                if (hasText(turn.vendorRaw())) {
                    contents.add(readTree(turn.vendorRaw()));
                } else {
                    ObjectNode node = JsonNodeFactory.instance.objectNode();
                    node.put("role", "model");
                    ArrayNode parts = JsonNodeFactory.instance.arrayNode();
                    if (turn.text() != null) {
                        parts.add(JsonNodeFactory.instance.objectNode().put("text", turn.text()));
                    }
                    node.set("parts", parts);
                    contents.add(node);
                }
                i++;
            } else {
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("role", "user");
                ArrayNode parts = JsonNodeFactory.instance.arrayNode();
                parts.add(JsonNodeFactory.instance.objectNode().put("text", turn.text() == null ? "" : turn.text()));
                node.set("parts", parts);
                contents.add(node);
                i++;
            }
        }
        root.set("contents", contents);

        ArrayNode functionDeclarations = JsonNodeFactory.instance.arrayNode();
        for (ExternalAiToolSpec tool : request.tools()) {
            ObjectNode declaration = JsonNodeFactory.instance.objectNode();
            declaration.put("name", tool.name());
            if (tool.description() != null) {
                declaration.put("description", tool.description());
            }
            declaration.set("parameters", toJsonNode(tool.parameters()));
            functionDeclarations.add(declaration);
        }
        ObjectNode toolsNode = JsonNodeFactory.instance.objectNode();
        toolsNode.set("functionDeclarations", functionDeclarations);
        root.set("tools", JsonNodeFactory.instance.arrayNode().add(toolsNode));
        ObjectNode functionCallingConfig = JsonNodeFactory.instance.objectNode();
        functionCallingConfig.put("mode", "AUTO");
        ObjectNode toolConfig = JsonNodeFactory.instance.objectNode();
        toolConfig.set("functionCallingConfig", functionCallingConfig);
        root.set("toolConfig", toolConfig);
        return objectMapper.writeValueAsString(root);
    }

    /** AGENT-1 (A2.3): OpenAI gets one {@code {"role":"tool"}} message per tool result (no merging). */
    private String buildOpenAiToolChatBody(String model, ExternalAiToolChatRequest request) throws IOException {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("model", model);
        ArrayNode messages = JsonNodeFactory.instance.arrayNode();
        if (hasText(request.systemInstruction())) {
            messages.add(JsonNodeFactory.instance.objectNode()
                    .put("role", "system").put("content", request.systemInstruction()));
        }
        for (ExternalAiChatTurn turn : request.turns()) {
            switch (turn.role()) {
                case "assistant" -> {
                    if (hasText(turn.vendorRaw())) {
                        messages.add(readTree(turn.vendorRaw()));
                    } else {
                        ObjectNode node = JsonNodeFactory.instance.objectNode();
                        node.put("role", "assistant");
                        if (turn.text() != null) {
                            node.put("content", turn.text());
                        } else {
                            node.putNull("content");
                        }
                        messages.add(node);
                    }
                }
                case "tool" -> {
                    ObjectNode node = JsonNodeFactory.instance.objectNode();
                    node.put("role", "tool");
                    node.put("tool_call_id", turn.toolCallId());
                    node.put("content", turn.resultJson() == null ? "{}" : turn.resultJson());
                    messages.add(node);
                }
                default -> messages.add(JsonNodeFactory.instance.objectNode()
                        .put("role", "user").put("content", turn.text() == null ? "" : turn.text()));
            }
        }
        root.set("messages", messages);

        ArrayNode tools = JsonNodeFactory.instance.arrayNode();
        for (ExternalAiToolSpec tool : request.tools()) {
            ObjectNode function = JsonNodeFactory.instance.objectNode();
            function.put("name", tool.name());
            if (tool.description() != null) {
                function.put("description", tool.description());
            }
            function.set("parameters", toJsonNode(tool.parameters()));
            ObjectNode toolNode = JsonNodeFactory.instance.objectNode();
            toolNode.put("type", "function");
            toolNode.set("function", function);
            tools.add(toolNode);
        }
        root.set("tools", tools);
        return objectMapper.writeValueAsString(root);
    }

    /**
     * AGENT-1 (A2.3): Anthropic merges consecutive {@code tool} turns into ONE {@code user} message
     * with several {@code tool_result} blocks (same reasoning as Gemini's {@code functionResponse}
     * merge). {@code max_tokens} reuses {@link #ANTHROPIC_MAX_TOKENS}.
     */
    private String buildAnthropicToolChatBody(String model, ExternalAiToolChatRequest request) throws IOException {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("model", model);
        root.put("max_tokens", ANTHROPIC_MAX_TOKENS);
        if (hasText(request.systemInstruction())) {
            root.put("system", request.systemInstruction());
        }
        ArrayNode tools = JsonNodeFactory.instance.arrayNode();
        for (ExternalAiToolSpec tool : request.tools()) {
            ObjectNode toolNode = JsonNodeFactory.instance.objectNode();
            toolNode.put("name", tool.name());
            if (tool.description() != null) {
                toolNode.put("description", tool.description());
            }
            toolNode.set("input_schema", toJsonNode(tool.parameters()));
            tools.add(toolNode);
        }
        root.set("tools", tools);

        ArrayNode messages = JsonNodeFactory.instance.arrayNode();
        List<ExternalAiChatTurn> turns = request.turns();
        int i = 0;
        while (i < turns.size()) {
            ExternalAiChatTurn turn = turns.get(i);
            if ("tool".equals(turn.role())) {
                ArrayNode content = JsonNodeFactory.instance.arrayNode();
                while (i < turns.size() && "tool".equals(turns.get(i).role())) {
                    ExternalAiChatTurn toolTurn = turns.get(i);
                    ObjectNode toolResult = JsonNodeFactory.instance.objectNode();
                    toolResult.put("type", "tool_result");
                    toolResult.put("tool_use_id", toolTurn.toolCallId());
                    toolResult.put("content", toolTurn.resultJson() == null ? "{}" : toolTurn.resultJson());
                    content.add(toolResult);
                    i++;
                }
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("role", "user");
                node.set("content", content);
                messages.add(node);
            } else if ("assistant".equals(turn.role())) {
                ObjectNode node = JsonNodeFactory.instance.objectNode();
                node.put("role", "assistant");
                if (hasText(turn.vendorRaw())) {
                    node.set("content", readTree(turn.vendorRaw()));
                } else {
                    ArrayNode content = JsonNodeFactory.instance.arrayNode();
                    if (turn.text() != null) {
                        content.add(JsonNodeFactory.instance.objectNode().put("type", "text").put("text", turn.text()));
                    }
                    node.set("content", content);
                }
                messages.add(node);
                i++;
            } else {
                messages.add(JsonNodeFactory.instance.objectNode()
                        .put("role", "user").put("content", turn.text() == null ? "" : turn.text()));
                i++;
            }
        }
        root.set("messages", messages);
        return objectMapper.writeValueAsString(root);
    }

    private JsonNode toJsonNode(Map<String, Object> value) {
        return objectMapper.valueToTree(value == null ? Map.of() : value);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** AGENT-1 (A2.3): pulls the assistant turn (text and/or tool calls) out of whichever response
     *  shape the vendor uses, keeping the raw vendor JSON in {@code vendorRaw} for verbatim replay. */
    private ExternalAiChatTurn parseToolChatResponse(ExternalAiRequestFormat format, String responseBody) {
        JsonNode root = readTree(responseBody);
        return switch (format) {
            case GEMINI_GENERATE_CONTENT -> parseGeminiToolChatResponse(root);
            case OPENAI_CHAT -> parseOpenAiToolChatResponse(root);
            case ANTHROPIC_MESSAGES -> parseAnthropicToolChatResponse(root);
        };
    }

    private ExternalAiChatTurn parseGeminiToolChatResponse(JsonNode root) {
        JsonNode contentNode = root.path("candidates").path(0).path("content");
        StringBuilder text = new StringBuilder();
        List<ExternalAiToolCall> calls = new ArrayList<>();
        int callIndex = 0;
        for (JsonNode part : contentNode.path("parts")) {
            if (part.path("text").isTextual()) {
                text.append(part.path("text").asText());
            }
            if (part.has("functionCall")) {
                JsonNode functionCall = part.path("functionCall");
                Map<String, Object> args = objectMapper.convertValue(
                        functionCall.path("args"), new TypeReference<Map<String, Object>>() { });
                calls.add(new ExternalAiToolCall(
                        "gemini-" + callIndex, functionCall.path("name").asText(null),
                        args == null ? Map.of() : args));
                callIndex++;
            }
        }
        String vendorRaw = contentNode.isMissingNode() ? null : contentNode.toString();
        return new ExternalAiChatTurn(
                "assistant", text.length() == 0 ? null : text.toString(), calls, vendorRaw, null, null, null);
    }

    private ExternalAiChatTurn parseOpenAiToolChatResponse(JsonNode root) {
        JsonNode messageNode = root.path("choices").path(0).path("message");
        String text = messageNode.path("content").isTextual() ? messageNode.path("content").asText() : null;
        List<ExternalAiToolCall> calls = new ArrayList<>();
        for (JsonNode toolCall : messageNode.path("tool_calls")) {
            String argumentsJson = toolCall.path("function").path("arguments").asText("{}");
            Map<String, Object> args;
            try {
                args = objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() { });
            } catch (IOException e) {
                throw new IllegalStateException(
                        "OpenAI tool_calls.function.arguments was not valid JSON: " + argumentsJson, e);
            }
            calls.add(new ExternalAiToolCall(
                    toolCall.path("id").asText(null), toolCall.path("function").path("name").asText(null), args));
        }
        String vendorRaw = messageNode.isMissingNode() ? null : messageNode.toString();
        return new ExternalAiChatTurn("assistant", text, calls, vendorRaw, null, null, null);
    }

    private ExternalAiChatTurn parseAnthropicToolChatResponse(JsonNode root) {
        JsonNode contentArray = root.path("content");
        StringBuilder text = new StringBuilder();
        List<ExternalAiToolCall> calls = new ArrayList<>();
        for (JsonNode block : contentArray) {
            String type = block.path("type").asText();
            if ("text".equals(type)) {
                text.append(block.path("text").asText(""));
            } else if ("tool_use".equals(type)) {
                Map<String, Object> args = objectMapper.convertValue(
                        block.path("input"), new TypeReference<Map<String, Object>>() { });
                calls.add(new ExternalAiToolCall(
                        block.path("id").asText(null), block.path("name").asText(null),
                        args == null ? Map.of() : args));
            }
        }
        String vendorRaw = contentArray.isMissingNode() ? null : contentArray.toString();
        return new ExternalAiChatTurn(
                "assistant", text.length() == 0 ? null : text.toString(), calls, vendorRaw, null, null, null);
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
        applyUriAndAuth(builder, profile, apiKey, model);
        String body;
        try {
            body = switch (profile.requestFormat()) {
                case OPENAI_CHAT -> objectMapper.writeValueAsString(Map.of(
                        "model", model,
                        "messages", List.of(Map.of("role", "user", "content", prompt))
                ));
                case GEMINI_GENERATE_CONTENT -> objectMapper.writeValueAsString(Map.of(
                        "contents", List.of(Map.of("parts", List.of(Map.of("text", prompt))))
                ));
                case ANTHROPIC_MESSAGES -> {
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
                // R8d (RUN-4): the deadline this call cannot escape -- applies per-attempt, regardless
                // of which HttpClient sends it (see the class javadoc).
                .timeout(requestTimeout)
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
