package com.finalexec.connector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityErrorKind;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.properties.PropertyExplanation;
import com.npdev.kernel.properties.PropertyResolver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INT-2: the http-connector handler against a JDK HttpServer stub on loopback. */
class HttpConnectorCapabilityHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BEARER = "{\"type\":\"bearer\",\"secretEnvVar\":\"SHOP_TOKEN\"}";

    private record Seen(String method, String rawPath, String rawQuery, Map<String, String> headers, String body) {
    }

    private HttpServer stub;
    private String baseUrl;
    private final List<Seen> seen = new CopyOnWriteArrayList<>();
    private volatile Function<Seen, Object[]> responder = request -> new Object[] {200, "{}"};
    private volatile long stubDelayMs;
    private final Map<String, String> secrets = new HashMap<>();

    @BeforeEach
    void startStub() throws IOException {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.setExecutor(Executors.newCachedThreadPool());
        stub.createContext("/", exchange -> {
            try {
                Seen request = record(exchange);
                seen.add(request);
                if (stubDelayMs > 0) {
                    sleep(stubDelayMs);
                }
                Object[] answer = responder.apply(request);
                byte[] bytes = String.valueOf(answer[1]).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders((Integer) answer[0], bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            } catch (IOException ignored) {
                // client gave up (timeout test)
            } finally {
                exchange.close();
            }
        });
        stub.start();
        baseUrl = "http://127.0.0.1:" + stub.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    private static Seen record(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(), String.join(",", v)));
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getRawPath(),
                exchange.getRequestURI().getRawQuery(), headers, body);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static CompiledModel model(String capabilityHttpBlock) throws Exception {
        String json = """
                {
                  "dslVersion": "1.0.0", "namespace": "demo.conn", "version": "1.0",
                  "concepts": [ { "name": "Thing", "fields": [ { "name": "id", "type": "uuid", "id": true } ] } ],
                  "propertyScopes": [ { "name": "tenant" } ],
                  "properties": [ { "name": "shopBase", "type": "string", "default": "http://127.0.0.1:1", "settableAt": ["tenant"] } ],
                  "capabilities": [ {
                    "name": "shop", "type": "HttpConnector",
                    "http": %s,
                    "operations": [
                      { "name": "getCustomer", "sideEffects": "reads",
                        "http": { "method": "GET", "path": "/customers/{customerId}", "params": ["customerId", "expand"],
                                  "query": { "expand": "{expand}" }, "response": { "pointer": "/data" } } },
                      { "name": "createCharge", "sideEffects": "external",
                        "http": { "method": "POST", "path": "/charges", "params": ["amount", "currency"],
                                  "body": { "amount": "{amount}", "currency": "{currency}", "note": "for {currency}" },
                                  "response": { "pointer": "/id" } } },
                      { "name": "chargeForm", "sideEffects": "external",
                        "http": { "method": "POST", "path": "/form", "params": ["amount", "currency"],
                                  "bodyEncoding": "form", "body": { "amount": "{amount}", "currency": "{currency}" } } },
                      { "name": "listAll", "sideEffects": "reads", "http": { "method": "GET", "path": "/list" } },
                      { "name": "failPost", "sideEffects": "external", "http": { "method": "POST", "path": "/fail", "body": {} } },
                      { "name": "slow", "sideEffects": "reads", "http": { "method": "GET", "path": "/slow" } }
                    ] } ],
                  "bindings": [ { "capability": "shop", "adapter": "http-connector" } ]
                }
                """.formatted(capabilityHttpBlock);
        JsonNode node = MAPPER.readTree(json);
        return new ModelCompiler().compile(new JsonModelParser().parse(node));
    }

    private String block(String extra) {
        return "{\"baseUrl\":\"" + baseUrl + "\"" + extra + "}";
    }

    private HttpConnectorCapabilityHandler handler(CompiledModel model, ConnectorEgressPolicy egress, PropertyResolver resolver) {
        return new HttpConnectorCapabilityHandler(() -> model, () -> resolver, secrets::get,
                HttpConnectorCapabilityHandler.defaultClient(), egress);
    }

    private HttpConnectorCapabilityHandler handler(String blockExtra) throws Exception {
        return handler(model(block(blockExtra)), new ConnectorEgressPolicy(true, Set.of(), false), null);
    }

    private static CapabilityResult call(HttpConnectorCapabilityHandler handler, String operation, Object... args) {
        return handler.invoke(new CapabilityCall("shop", "HttpConnector", "http-connector", operation, new ArrayList<>(List.of(args))),
                Map.of("tenantId", "t1"));
    }

    @Test
    void getEncodesPathParamAsOneSegmentAndReturnsPointerValue() throws Exception {
        responder = request -> new Object[] {200, "{\"data\":{\"name\":\"Ann\"}}"};
        CapabilityResult result = call(handler(""), "getCustomer", "a b/c", "customer");

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertEquals(Map.of("name", "Ann"), result.value());
        assertEquals(1, seen.size());
        assertEquals("GET", seen.get(0).method());
        assertEquals("/customers/a%20b%2Fc", seen.get(0).rawPath());
        assertEquals("expand=customer", seen.get(0).rawQuery());
    }

    @Test
    void bearerSecretIsSentAndMissingSecretFailsClosedWithoutAnyRequest() throws Exception {
        String auth = ",\"auth\":" + BEARER;
        HttpConnectorCapabilityHandler handler = handler(auth);

        CapabilityResult missing = call(handler, "listAll");
        assertFalse(missing.ok());
        assertEquals("CONNECTOR_SECRET_NOT_CONFIGURED", missing.error().code());
        assertEquals(0, seen.size(), "nothing may be sent without the configured secret");

        secrets.put("SHOP_TOKEN", "s3cr3t-value");
        responder = request -> new Object[] {401, "{\"error\":\"denied\"}"};
        CapabilityResult denied = call(handler, "listAll");
        assertEquals("Bearer s3cr3t-value", seen.get(0).headers().get("authorization"));
        assertFalse(denied.ok());
        assertEquals(CapabilityErrorKind.AUTH, denied.error().kind());
        assertFalse(String.valueOf(denied.error()).contains("s3cr3t-value"), "secret leaked into the failure");
        assertFalse(String.valueOf(missing.error()).contains("s3cr3t-value"));
    }

    @Test
    void jsonBodyTemplateKeepsWholePlaceholderTypedAndInterpolatesText() throws Exception {
        responder = request -> new Object[] {200, "{\"id\":\"ch_1\"}"};
        CapabilityResult result = call(handler(""), "createCharge", 1250, "usd");

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertEquals("ch_1", result.value());
        JsonNode sent = MAPPER.readTree(seen.get(0).body());
        assertTrue(sent.get("amount").isNumber(), seen.get(0).body());
        assertEquals(1250, sent.get("amount").asInt());
        assertEquals("usd", sent.get("currency").asText());
        assertEquals("for usd", sent.get("note").asText());
        assertEquals("application/json", seen.get(0).headers().get("content-type"));
    }

    @Test
    void formEncodingSendsUrlEncodedPairs() throws Exception {
        responder = request -> new Object[] {200, "{}"};
        CapabilityResult result = call(handler(""), "chargeForm", Map.of("amount", 1250, "currency", "usd"));

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertEquals("amount=1250&currency=usd", seen.get(0).body());
        assertEquals("application/x-www-form-urlencoded", seen.get(0).headers().get("content-type"));
    }

    @Test
    void notFoundIsClassifiedAndCarriesThePathTemplateNotTheUrl() throws Exception {
        responder = request -> new Object[] {404, "{\"message\":\"no such customer\"}"};
        CapabilityResult result = call(handler(""), "getCustomer", "42", "x");

        assertFalse(result.ok());
        assertEquals("CONNECTOR_HTTP_404", result.error().code());
        assertEquals(CapabilityErrorKind.NOT_FOUND, result.error().kind());
        assertEquals("/customers/{customerId}", result.error().details().get("path"));
        assertFalse(result.error().details().toString().contains("/customers/42"));
    }

    @Test
    void getRetriesOn429ButPostDoesNotRetryOn503() throws Exception {
        List<Integer> statuses = new ArrayList<>(List.of(429, 200));
        responder = request -> new Object[] {statuses.isEmpty() ? 200 : statuses.remove(0), "{}"};
        HttpConnectorCapabilityHandler handler = handler(",\"maxRetries\":1");

        assertTrue(call(handler, "listAll").ok());
        assertEquals(2, seen.size());

        seen.clear();
        responder = request -> new Object[] {503, "{}"};
        CapabilityResult failed = call(handler, "failPost");
        assertFalse(failed.ok());
        assertEquals(CapabilityErrorKind.TRANSIENT, failed.error().kind());
        assertEquals(1, seen.size(), "POST must not be retried by the adapter");
    }

    @Test
    void slowRemoteHitsTheDeadline() throws Exception {
        stubDelayMs = 2000;
        long started = System.nanoTime();
        CapabilityResult result = call(handler(",\"timeoutMs\":300"), "slow");

        assertFalse(result.ok());
        assertEquals("CONNECTOR_TIMEOUT", result.error().code());
        assertEquals(CapabilityErrorKind.TIMEOUT, result.error().kind());
        assertTrue((System.nanoTime() - started) / 1_000_000 < 1800, "must not wait for the slow remote");
    }

    @Test
    void egressAllowListDeniesBeforeAnyRequest() throws Exception {
        CompiledModel model = model(block(""));
        HttpConnectorCapabilityHandler denied = handler(model, new ConnectorEgressPolicy(true, Set.of("api.other.example"), false), null);
        CapabilityResult result = call(denied, "listAll");

        assertFalse(result.ok());
        assertEquals("CONNECTOR_EGRESS_DENIED", result.error().code());
        assertEquals(0, seen.size());

        HttpConnectorCapabilityHandler listed = handler(model, new ConnectorEgressPolicy(true, Set.of("127.0.0.1"), false), null);
        assertTrue(call(listed, "listAll").ok(), "loopback over http is allowed and listed");

        HttpConnectorCapabilityHandler off = handler(model, new ConnectorEgressPolicy(false, Set.of(), false), null);
        assertEquals("CONNECTOR_EGRESS_DENIED", call(off, "listAll").error().code());
    }

    @Test
    void plainHttpToANonLoopbackHostIsRefusedUnlessOperatorAllows() {
        ConnectorEgressPolicy strict = new ConnectorEgressPolicy(true, Set.of(), false);
        java.net.URI uri = java.net.URI.create("http://api.example.com/v1/x");
        assertNotNull(strict.check("http://api.example.com/v1", uri));
        assertEquals(null, new ConnectorEgressPolicy(true, Set.of(), true).check("http://api.example.com/v1", uri));
        assertNotNull(strict.check("https://api.example.com/v1", java.net.URI.create("https://evil.example.org/x")),
                "a different host than the declared base must be refused");
    }

    @Test
    void baseUrlPropertyOverridesTheLiteralBaseUrl() throws Exception {
        CompiledModel model = model("{\"baseUrl\":\"http://127.0.0.1:1\",\"baseUrlProperty\":\"shopBase\"}");
        PropertyResolver resolver = new PropertyResolver() {
            @Override
            public Object resolve(String propertyKey, ExecutionContext context) {
                return "shopBase".equals(propertyKey) ? baseUrl : null;
            }

            @Override
            public PropertyExplanation explain(String propertyKey, ExecutionContext context) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void set(String scopeType, String scopeId, String propertyKey, Object propertyValue, ExecutionContext context) {
                throw new UnsupportedOperationException();
            }
        };
        CapabilityResult result = call(handler(model, new ConnectorEgressPolicy(true, Set.of(), false), resolver), "listAll");

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertEquals(1, seen.size());
    }

    @Test
    void dotDotPathParameterIsRejected() throws Exception {
        CapabilityResult result = call(handler(""), "getCustomer", "..", "x");

        assertFalse(result.ok());
        assertEquals("CONNECTOR_REQUEST_INVALID", result.error().code());
        assertEquals(0, seen.size());
    }

    @Test
    void unknownOperationAndConnectorFailWithContractErrors() throws Exception {
        HttpConnectorCapabilityHandler handler = handler("");
        assertEquals("CONNECTOR_OPERATION_UNKNOWN", call(handler, "nope").error().code());
        CapabilityResult other = handler.invoke(new CapabilityCall("ghost", "nope", "x"), Map.of());
        assertEquals("CONNECTOR_UNKNOWN", other.error().code());
    }
}
