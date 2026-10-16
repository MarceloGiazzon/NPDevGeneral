package com.finalexec.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.config.DefaultTenant;
import com.npdev.dsl.v1.compiled.CompiledCapability;
import com.npdev.dsl.v1.compiled.CompiledCapabilityOperation;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.CapabilityAdapter;
import com.npdev.kernel.properties.PropertyResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * INT-2: the ONE platform handler behind every model capability bound to adapter
 * {@code http-connector}. Registered once per such capability by
 * {@code NpdevCapabilityBindingConfig.populateCapabilityRegistry}; {@link CapabilityCall#capability()}
 * says which connector is being called, and its {@code http} blocks are read LIVE from the model
 * (a /model-reload is observed on the next call).
 *
 * <p>Posture copied from webhook-http / external-ai-http (R4 rules): per-request deadline, adapter-local
 * bounded retry for idempotent methods only, no redirects, egress limited to the declared base-URL
 * host, secrets resolved by NAME at call time and never echoed into logs or results. Never throws out
 * of {@link #invoke}: every failure is a {@link CapabilityResult#failure} with a stable code.
 */
public final class HttpConnectorCapabilityHandler implements CapabilityAdapter {

    public static final String ADAPTER_ID = "http-connector";
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    static final long DEFAULT_TIMEOUT_MS = 10_000;
    static final long MAX_TIMEOUT_MS = 120_000;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z_][A-Za-z0-9_]*)\\}");
    private static final Pattern WHOLE_PLACEHOLDER = Pattern.compile("^\\{([A-Za-z_][A-Za-z0-9_]*)\\}$");
    private static final Set<String> IDEMPOTENT = Set.of("GET", "PUT", "DELETE");
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "http-connector-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private static final Logger LOG = Logger.getLogger(HttpConnectorCapabilityHandler.class.getName());

    private final Supplier<CompiledModel> model;
    private final Supplier<PropertyResolver> properties;
    private final Function<String, String> secretLookup;
    private final HttpClient httpClient;
    private final ConnectorEgressPolicy egress;
    private final ObjectMapper mapper = new ObjectMapper();

    public HttpConnectorCapabilityHandler(
            Supplier<CompiledModel> model,
            Supplier<PropertyResolver> properties,
            Function<String, String> secretLookup,
            HttpClient httpClient,
            ConnectorEgressPolicy egress
    ) {
        this.model = model;
        this.properties = properties == null ? () -> null : properties;
        this.secretLookup = secretLookup;
        this.httpClient = httpClient;
        this.egress = egress;
    }

    /** Production client: connect deadline + redirects explicitly OFF (a redirect is how an allowed host hands the call to an unlisted one). */
    public static HttpClient defaultClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override public String adapterId() { return ADAPTER_ID; }
    @Override public String capability() { return "httpConnector"; }
    @Override public String capabilityType() { return "HttpConnector"; }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        try {
            return doInvoke(call, contextState);
        } catch (RuntimeException unexpected) {
            // never let a bug escape as an exception into a flow; the class name is safe, the message may echo a URL
            return fail("CONNECTOR_INTERNAL_ERROR", "Connector call failed unexpectedly: "
                    + unexpected.getClass().getSimpleName(), CapabilityErrorKind.PERMANENT, call);
        }
    }

    private CapabilityResult doInvoke(CapabilityCall call, Map<String, Object> contextState) {
        CompiledModel compiled = model.get();
        CompiledCapability capability = compiled == null ? null : compiled.getCapabilities().stream()
                .filter(c -> c.getName().equalsIgnoreCase(call.capability())).findFirst().orElse(null);
        if (capability == null || capability.getHttp().isEmpty()) {
            return fail("CONNECTOR_UNKNOWN", "No http connector named '" + call.capability() + "' in the model",
                    CapabilityErrorKind.CONTRACT, call);
        }
        CompiledCapabilityOperation operation = capability.getOperations().stream()
                .filter(o -> o.getName().equalsIgnoreCase(call.operation())).findFirst().orElse(null);
        if (operation == null || operation.getHttp().isEmpty()) {
            return fail("CONNECTOR_OPERATION_UNKNOWN", "Connector '" + capability.getName()
                    + "' has no http operation '" + call.operation() + "'", CapabilityErrorKind.CONTRACT, call);
        }
        HttpConnectorSpec spec = HttpConnectorSpec.from(capability.getHttp());
        HttpConnectorSpec.Operation op = HttpConnectorSpec.Operation.from(operation.getHttp());

        String tenantId = DefaultTenant.orDefault(text(contextState == null ? null : contextState.get("tenantId")));
        String actorId = text(contextState == null ? null : contextState.get("actorId"));
        ExecutionContext context = ExecutionContext.of(tenantId, actorId.isBlank() ? "system" : actorId);

        Map<String, Object> named = HttpConnectorSpec.bindArgs(op.params(), call.args());

        // 1. Base URL: tenant-scoped property wins over the literal (sandbox vs production per tenant).
        String baseUrl = spec.baseUrl();
        if (!spec.baseUrlProperty().isBlank()) {
            PropertyResolver resolver = properties.get();
            Object resolved = null;
            try {
                resolved = resolver == null ? null : resolver.resolve(spec.baseUrlProperty(), context);
            } catch (RuntimeException unresolvable) {
                // falls back to the literal baseUrl below; if there is none the call fails closed
                resolved = null;
            }
            if (resolved != null && !String.valueOf(resolved).isBlank()) {
                baseUrl = String.valueOf(resolved).trim();
            }
        }
        if (baseUrl.isBlank()) {
            return fail("CONNECTOR_BASE_URL_UNRESOLVED", "Connector '" + capability.getName()
                    + "' has no base URL (property '" + spec.baseUrlProperty() + "' unresolved and no literal baseUrl)",
                    CapabilityErrorKind.CONTRACT, call);
        }
        // 2. Build the URI (path params percent-encoded as ONE segment each; '.'/'..' refused).
        URI uri;
        try {
            uri = buildUri(baseUrl, op, named, spec);
        } catch (IllegalArgumentException invalid) {
            return fail("CONNECTOR_REQUEST_INVALID", invalid.getMessage(), CapabilityErrorKind.CONTRACT, call);
        }
        // 3. Egress: final host == declared base host, https unless loopback/allowed, operator allow-list.
        String denial = egress.check(baseUrl, uri);
        if (denial != null) {
            return fail("CONNECTOR_EGRESS_DENIED", denial, CapabilityErrorKind.CONTRACT, call);
        }
        // 4. Auth + headers. A missing secret is fail-closed: nothing is sent.
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(Math.min(MAX_TIMEOUT_MS, spec.timeoutMs() > 0 ? spec.timeoutMs() : DEFAULT_TIMEOUT_MS)))
                .header("User-Agent", "NPDev-Connector");
        spec.headers().forEach((k, v) -> request.header(k, interpolate(v, named)));
        op.headers().forEach((k, v) -> request.header(k, interpolate(v, named)));
        String authDenial = applyAuth(spec.auth(), request);
        if (authDenial != null) {
            return fail("CONNECTOR_SECRET_NOT_CONFIGURED", authDenial, CapabilityErrorKind.CONTRACT, call);
        }
        // 5. Body.
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
        if (op.body() != null && !"GET".equals(op.method()) && !"DELETE".equals(op.method())) {
            try {
                Object rendered = renderBody(op.body(), named);
                if ("form".equals(op.bodyEncoding())) {
                    request.header("Content-Type", "application/x-www-form-urlencoded");
                    body = HttpRequest.BodyPublishers.ofString(formEncode(rendered), StandardCharsets.UTF_8);
                } else {
                    request.header("Content-Type", "application/json");
                    body = HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(rendered), StandardCharsets.UTF_8);
                }
            } catch (IOException | IllegalArgumentException invalid) {
                return fail("CONNECTOR_REQUEST_INVALID", "Could not build the request body: " + invalid.getMessage(),
                        CapabilityErrorKind.CONTRACT, call);
            }
        }
        request.method(op.method(), body);
        // 6. Send with adapter-local bounded retry (idempotent methods only; 429/5xx/IO only).
        int attempts = 1 + (IDEMPOTENT.contains(op.method()) ? Math.max(0, Math.min(5, spec.maxRetries())) : 0);
        long deadlineMs = Math.min(MAX_TIMEOUT_MS, spec.timeoutMs() > 0 ? spec.timeoutMs() : DEFAULT_TIMEOUT_MS);
        long started = System.nanoTime();
        for (int attempt = 1; ; attempt++) {
            long attemptStarted = System.nanoTime();
            try {
                HttpResponse<InputStream> response = httpClient.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
                byte[] bytes;
                // request.timeout() only bounds the wait for the response headers; a remote that sends
                // headers and then stalls would hang the thread, so the body read gets a watchdog too.
                long remainingMs = Math.max(1, deadlineMs - (System.nanoTime() - attemptStarted) / 1_000_000);
                try (InputStream in = response.body()) {
                    ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> closeQuietly(in), remainingMs, TimeUnit.MILLISECONDS);
                    try {
                        bytes = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                    } finally {
                        watchdog.cancel(false);
                    }
                }
                int status = response.statusCode();
                if ((status == 429 || status >= 500) && attempt < attempts) {
                    backoff(attempt);
                    continue;
                }
                LOG.info(() -> "connector " + capability.getName() + "." + operation.getName() + " " + op.method()
                        + " " + op.path() + " -> " + status + " in " + (System.nanoTime() - started) / 1_000_000 + "ms");
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    return fail("CONNECTOR_RESPONSE_TOO_LARGE", "Response exceeds " + MAX_RESPONSE_BYTES + " bytes",
                            CapabilityErrorKind.CONTRACT, call);
                }
                if (status / 100 != 2) {
                    return httpFailure(call, op, status, bytes);
                }
                return success(op, status, bytes, call);
            } catch (HttpTimeoutException timeout) {
                if (attempt < attempts) { backoff(attempt); continue; }
                return fail("CONNECTOR_TIMEOUT", "No response within the deadline", CapabilityErrorKind.TIMEOUT, call);
            } catch (IOException io) {
                if ((System.nanoTime() - attemptStarted) / 1_000_000 >= deadlineMs) {
                    // the watchdog closed a stalled body stream
                    if (attempt < attempts) { backoff(attempt); continue; }
                    return fail("CONNECTOR_TIMEOUT", "No complete response within the deadline", CapabilityErrorKind.TIMEOUT, call);
                }
                if (attempt < attempts) { backoff(attempt); continue; }
                return fail("CONNECTOR_UNREACHABLE", "HTTP call failed: " + io.getClass().getSimpleName(),
                        CapabilityErrorKind.TRANSIENT, call);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return fail("CONNECTOR_INTERRUPTED", "Interrupted", CapabilityErrorKind.TRANSIENT, call);
            }
        }
    }

    // ---- helpers (implementer fills in; each is small and unit-testable) --------------------

    URI buildUri(String baseUrl, HttpConnectorSpec.Operation op, Map<String, Object> named, HttpConnectorSpec spec) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        Matcher m = PLACEHOLDER.matcher(op.path());
        StringBuilder path = new StringBuilder();
        while (m.find()) {
            Object value = named.get(m.group(1));
            String raw = value == null ? "" : String.valueOf(value);
            if (raw.isBlank() || raw.equals(".") || raw.equals("..")) {
                throw new IllegalArgumentException("Path parameter '" + m.group(1) + "' is missing or invalid");
            }
            m.appendReplacement(path, Matcher.quoteReplacement(encode(raw)));
        }
        m.appendTail(path);
        List<String> query = new ArrayList<>();
        op.query().forEach((k, v) -> {
            String rendered = interpolate(v, named);
            if (!rendered.isBlank()) {   // an absent optional param drops its query key
                query.add(encode(k) + "=" + encode(rendered));
            }
        });
        // apiKeyQuery is appended here (resolved secret) -- never logged: log op.path(), not the URI.
        HttpConnectorSpec.Auth auth = spec.auth();
        if ("apiKeyQuery".equals(auth.type())) {
            String secret = secretLookup.apply(auth.secretEnvVar());
            if (secret != null && !secret.isBlank()) {
                query.add(encode(auth.name()) + "=" + encode(secret));
            }
        }
        return URI.create(base + path + (query.isEmpty() ? "" : "?" + String.join("&", query)));
    }

    /** Returns a denial message, or null when the auth header (if any) was applied. */
    String applyAuth(HttpConnectorSpec.Auth auth, HttpRequest.Builder request) {
        switch (auth.type()) {
            case "none": return null;
            case "bearer": {
                String token = secretLookup.apply(auth.secretEnvVar());
                if (token == null || token.isBlank()) return "No secret configured (env var " + auth.secretEnvVar() + ")";
                request.header("Authorization", "Bearer " + token.trim());
                return null;
            }
            case "basic": {
                String user = secretLookup.apply(auth.usernameEnvVar());
                if (user == null || user.isBlank()) return "No username configured (env var " + auth.usernameEnvVar() + ")";
                String password = auth.secretEnvVar().isBlank() ? "" : String.valueOf(secretLookup.apply(auth.secretEnvVar()));
                request.header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((user.trim() + ":" + password).getBytes(StandardCharsets.UTF_8)));
                return null;
            }
            case "apiKeyHeader": {
                String key = secretLookup.apply(auth.secretEnvVar());
                if (key == null || key.isBlank()) return "No secret configured (env var " + auth.secretEnvVar() + ")";
                request.header(auth.name(), key.trim());
                return null;
            }
            case "apiKeyQuery": {
                String key = secretLookup.apply(auth.secretEnvVar());
                return key == null || key.isBlank() ? "No secret configured (env var " + auth.secretEnvVar() + ")" : null;
            }
            default: return "Unknown auth type '" + auth.type() + "'";
        }
    }

    /** "{p}" alone -> the typed value; "text {p}" -> string interpolation; maps/lists recurse. */
    Object renderBody(Object template, Map<String, Object> named) {
        if (template instanceof String text) {
            Matcher whole = WHOLE_PLACEHOLDER.matcher(text.trim());
            return whole.matches() ? named.get(whole.group(1)) : interpolate(text, named);
        }
        if (template instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                Object rendered = renderBody(v, named);
                if (rendered != null) out.put(String.valueOf(k), rendered);   // absent optional -> key omitted
            });
            return out;
        }
        if (template instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(v -> out.add(renderBody(v, named)));
            return out;
        }
        return template;
    }

    private CapabilityResult success(HttpConnectorSpec.Operation op, int status, byte[] bytes, CapabilityCall call) {
        if (bytes.length == 0) {
            return CapabilityResult.success(Map.of("httpStatus", status));
        }
        Object value;
        try {
            JsonNode node = mapper.readTree(bytes);
            if (!op.responsePointer().isBlank()) {
                JsonNode picked = node.at(op.responsePointer());
                if (picked.isMissingNode()) {
                    return fail("CONNECTOR_RESPONSE_POINTER_MISSING", "Response has nothing at " + op.responsePointer(),
                            CapabilityErrorKind.CONTRACT, call);
                }
                node = picked;
            }
            value = mapper.convertValue(node, Object.class);
        } catch (IOException notJson) {
            value = new String(bytes, StandardCharsets.UTF_8);   // text/plain, xml: returned raw
        }
        return CapabilityResult.success(value);
    }

    private CapabilityResult httpFailure(CapabilityCall call, HttpConnectorSpec.Operation op, int status, byte[] bytes) {
        CapabilityErrorKind kind = status == 401 || status == 403 ? CapabilityErrorKind.AUTH
                : status == 404 ? CapabilityErrorKind.NOT_FOUND
                : status == 408 ? CapabilityErrorKind.TIMEOUT
                : status == 429 ? CapabilityErrorKind.RATE_LIMIT
                : status >= 500 ? CapabilityErrorKind.TRANSIENT
                : CapabilityErrorKind.CONTRACT;
        String excerpt = new String(bytes, 0, Math.min(bytes.length, 500), StandardCharsets.UTF_8);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("capability", call.capability());
        details.put("operation", call.operation());
        details.put("httpStatus", status);
        details.put("method", op.method());
        details.put("path", op.path());          // the TEMPLATE, never the resolved URL (apiKeyQuery would leak)
        details.put("bodyExcerpt", excerpt);
        return CapabilityResult.failure("CONNECTOR_HTTP_" + status,
                call.capability() + "." + call.operation() + " returned HTTP " + status, kind, details);
    }

    private static CapabilityResult fail(String code, String message, CapabilityErrorKind kind, CapabilityCall call) {
        return CapabilityResult.failure(code, message, kind,
                Map.of("capability", String.valueOf(call.capability()), "operation", String.valueOf(call.operation())));
    }

    static String interpolate(String template, Map<String, Object> named) {
        Matcher m = PLACEHOLDER.matcher(template == null ? "" : template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = named.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String formEncode(Object rendered) {
        if (!(rendered instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("bodyEncoding 'form' needs an object body");
        }
        List<String> pairs = new ArrayList<>();
        map.forEach((k, v) -> {
            if (v instanceof Map<?, ?> || v instanceof List<?>) {
                throw new IllegalArgumentException("bodyEncoding 'form' supports flat values only (key '" + k + "')");
            }
            pairs.add(encode(String.valueOf(k)) + "=" + encode(v == null ? "" : String.valueOf(v)));
        });
        return String.join("&", pairs);
    }

    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // closing only unblocks the stalled reader; nothing to report
        }
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(300L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    static String normalizeHost(String host) {
        return host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
    }
}
