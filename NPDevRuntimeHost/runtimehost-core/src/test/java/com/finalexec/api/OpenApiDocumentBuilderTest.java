package com.finalexec.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.db.LiveConceptCrudSupport;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** INT-7: the model-derived OpenAPI document. */
class OpenApiDocumentBuilderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL = """
            {
              "dslVersion": "1.0.0", "namespace": "shop", "version": "1.0",
              "concepts": [
                { "name": "Order", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "total", "type": "decimal", "required": true },
                    { "name": "status", "type": "enum", "values": ["OPEN", "PAID"] },
                    { "name": "placedAt", "type": "datetime" },
                    { "name": "seats", "type": "long" } ] },
                { "name": "OrderLine", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "orderId", "type": "uuid" },
                    { "name": "qty", "type": "integer" } ] },
                { "name": "Gallery", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "label", "type": "string" },
                    { "name": "secretNote", "type": "string" } ],
                  "access": { "public": { "where": "label != ''", "fields": ["label"] } } }
              ],
              "queries": [
                { "name": "OpenOrders", "concept": "Order", "where": "status == 'OPEN'", "orderBy": ["total desc"] }
              ],
              "aggregates": [
                { "name": "OrderTree", "root": "Order", "collections": [
                    { "name": "lines", "concept": "OrderLine", "childField": "orderId", "ownership": "owned" } ] }
              ],
              "flows": [
                { "name": "PlaceOrder", "startEndpoint": true,
                  "input": { "concept": "Order", "mode": "create" },
                  "inputSchema": { "type": "object", "required": ["total"],
                    "properties": { "total": { "type": "decimal" }, "note": { "type": "string", "maxLength": 80 } } },
                  "steps": [ { "name": "r", "type": "return", "value": "$input" } ] },
                { "name": "Internal", "input": { "concept": "Order", "mode": "query" },
                  "steps": [ { "name": "r", "type": "return", "value": "$input" } ] }
              ]
            }
            """;

    private static CompiledModel model() throws Exception {
        return new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(MODEL)));
    }

    private static JsonNode doc() throws Exception {
        return MAPPER.valueToTree(OpenApiDocumentBuilder.build(model(), "Shop"));
    }

    @Test
    void listsEveryBusinessRouteFamily() throws Exception {
        JsonNode paths = doc().get("paths");
        for (String path : List.of(
                "/api/concepts/orders", "/api/concepts/orders/{id}", "/api/concepts/orders/batch",
                "/api/concepts/order_lines", "/api/queries/OpenOrders",
                "/api/v1/flows/PlaceOrder/execute", "/api/v1/flows/Internal/execute",
                "/api/v1/runtime/aggregate/OrderTree", "/api/v1/runtime/aggregate/OrderTree/{rootId}",
                "/api/public/concepts", "/api/public/concepts/Gallery", "/api/public/concepts/Gallery/{id}")) {
            assertTrue(paths.has(path), "missing path " + path + " in " + names(paths));
        }
        assertFalse(paths.has("/api/public/concepts/Order"), "private concepts have no public route");
        assertTrue(paths.get("/api/concepts/orders").has("get"));
        assertTrue(paths.get("/api/concepts/orders").has("post"));
        assertTrue(paths.get("/api/concepts/orders/{id}").has("put"));
        assertTrue(paths.get("/api/concepts/orders/{id}").has("delete"));
        assertTrue(paths.get("/api/v1/flows/PlaceOrder/execute").get("post").get("x-npdev-start-endpoint").asBoolean());
        assertFalse(paths.get("/api/v1/flows/Internal/execute").get("post").has("x-npdev-start-endpoint"));
    }

    @Test
    void conceptSchemaFollowsFieldTypes() throws Exception {
        JsonNode order = doc().get("components").get("schemas").get("Order");
        JsonNode props = order.get("properties");
        assertEquals("string", props.get("id").get("type").asText());
        assertEquals("uuid", props.get("id").get("format").asText());
        assertEquals("number", props.get("total").get("type").asText());
        assertEquals(List.of("OPEN", "PAID"), strings(props.get("status").get("enum")));
        assertEquals("date-time", props.get("placedAt").get("format").asText());
        assertEquals("int64", props.get("seats").get("format").asText());
        assertEquals(List.of("total"), strings(order.get("required")));
    }

    @Test
    void listParametersAndEnvelopeMatchTheCrudController() throws Exception {
        JsonNode get = doc().get("paths").get("/api/concepts/orders").get("get");
        List<String> params = new ArrayList<>();
        get.get("parameters").forEach(p -> params.add(p.get("name").asText()));
        assertEquals(List.of("page", "size", "sort", "direction", "filter", "where", "asOf"), params);
        JsonNode page = doc().get("components").get("schemas").get("OrderPage").get("properties");
        assertTrue(page.has("content") && page.has("totalElements") && page.has("page") && page.has("size"));
        assertEquals("#/components/schemas/Order", page.get("content").get("items").get("$ref").asText());
    }

    /**
     * REG-312: the document advertised {@code /api/concepts/Order} while the controller answers only a
     * concept's REST collection name, so a client following the app's own spec got
     * {@code 400 unknown_concept} on its first call. Every concept collection path in the document must
     * be a route the controller can resolve, checked against the matcher it falls back to for a live
     * concept (it derives the route exactly as the baked binding map does); the model name must not be.
     */
    @Test
    void everyAdvertisedConceptPathIsARouteTheControllerResolves() throws Exception {
        CompiledModel model = model();
        JsonNode paths = MAPPER.valueToTree(OpenApiDocumentBuilder.build(model, "Shop")).get("paths");
        String prefix = "/api/concepts/";
        Set<String> routes = new HashSet<>();
        paths.fieldNames().forEachRemaining(path -> {
            if (path.startsWith(prefix) && path.indexOf('/', prefix.length()) < 0) {
                routes.add(path.substring(prefix.length()));
            }
        });
        assertEquals(Set.of("orders", "order_lines", "galleries"), routes);
        for (String route : routes) {
            assertTrue(LiveConceptCrudSupport.resolve(model, route, null, null).isPresent(),
                    "the controller cannot resolve the advertised route " + prefix + route);
        }
        for (String modelName : List.of("Order", "OrderLine", "Gallery")) {
            assertTrue(LiveConceptCrudSupport.resolve(model, modelName, null, null).isEmpty(),
                    "the model name " + modelName + " is not a route; the document must not advertise it");
        }
    }

    @Test
    void irregularPluralsAreAdvertisedUnderTheirRestCollectionName() throws Exception {
        String json = """
                {
                  "dslVersion": "1.0.0", "namespace": "desk", "version": "1.0",
                  "concepts": [
                    { "name": "TicketCategory", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "Box", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ]
                }
                """;
        CompiledModel desk = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(json)));
        JsonNode paths = MAPPER.valueToTree(OpenApiDocumentBuilder.build(desk, "Desk")).get("paths");
        assertTrue(paths.has("/api/concepts/ticket_categories"), names(paths).toString());
        assertTrue(paths.has("/api/concepts/boxes"), names(paths).toString());
        assertFalse(paths.has("/api/concepts/TicketCategory"));
        assertFalse(paths.has("/api/concepts/Box"));
    }

    @Test
    void flowRequestBodyIsTheFlowInputSchema() throws Exception {
        JsonNode body = doc().get("paths").get("/api/v1/flows/PlaceOrder/execute").get("post")
                .get("requestBody").get("content").get("application/json").get("schema");
        assertEquals("number", body.get("properties").get("total").get("type").asText());
        assertEquals(80, body.get("properties").get("note").get("maxLength").asInt());
        assertEquals(List.of("total"), strings(body.get("required")));
    }

    @Test
    void publicSchemaOnlyCarriesAllowListedFields() throws Exception {
        JsonNode props = doc().get("components").get("schemas").get("GalleryPublic").get("properties");
        assertTrue(props.has("id") && props.has("label"));
        assertFalse(props.has("secretNote"));
        assertEquals(0, doc().get("paths").get("/api/public/concepts/Gallery").get("get").get("security").size());
    }

    @Test
    void aggregateSchemaNestsChildCollections() throws Exception {
        JsonNode schema = doc().get("components").get("schemas").get("OrderTreeAggregate");
        JsonNode lines = schema.get("properties").get("lines");
        assertEquals("array", lines.get("type").asText());
        assertEquals("#/components/schemas/OrderLine", lines.get("items").get("allOf").get(0).get("$ref").asText());
    }

    @Test
    void queryHasItsRowsEnvelope() throws Exception {
        JsonNode result = doc().get("components").get("schemas").get("OpenOrdersQueryResult").get("properties");
        assertEquals("#/components/schemas/Order", result.get("rows").get("items").get("$ref").asText());
        assertTrue(result.has("total") && result.has("offset") && result.has("limit"));
    }

    @Test
    void legacyRuntimePathsAndSecuritySchemesSurvive() throws Exception {
        JsonNode doc = doc();
        for (String path : List.of("/api/v1/flows", "/api/v1/flows/{flowName}/execute",
                "/api/v1/executions/{executionId}/resume", "/api/v1/executions/summaries",
                "/api/v1/traces/summaries", "/api/v1/traces/{executionId}", "/api/v1/events/publish",
                "/api/v1/correlations/{correlationId}", "/api/v1/audit", "/api/v1/admin/failures/recent",
                "/api/v1/admin/stuck/recent", "/api/v1/admin/circuits", "/api/v1/admin/idempotency")) {
            assertTrue(doc.get("paths").has(path), "legacy runtime path dropped: " + path);
        }
        JsonNode schemes = doc.get("components").get("securitySchemes");
        assertEquals("X-Api-Key", schemes.get("ApiKeyAuth").get("name").asText());
        assertEquals("bearer", schemes.get("BearerAuth").get("scheme").asText());
        assertEquals("3.0.3", doc.get("openapi").asText());
        assertEquals("Shop", doc.get("info").get("title").asText());
    }

    @Test
    void everyRefResolvesAndOperationIdsAreUnique() throws Exception {
        JsonNode doc = doc();
        List<String> refs = new ArrayList<>();
        collectRefs(doc, refs);
        assertFalse(refs.isEmpty());
        for (String ref : refs) {
            String name = ref.substring("#/components/schemas/".length());
            assertTrue(doc.get("components").get("schemas").has(name), "dangling $ref " + ref);
        }
        List<String> ids = new ArrayList<>();
        doc.get("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(op -> {
            if (op.getValue().has("operationId")) {
                ids.add(op.getValue().get("operationId").asText());
            }
        }));
        assertEquals(ids.size(), ids.stream().distinct().count(), "duplicate operationId in " + ids);
    }

    @Test
    void twoBuildsAreByteIdentical() throws Exception {
        String first = MAPPER.writeValueAsString(OpenApiDocumentBuilder.build(model(), "Shop"));
        String second = MAPPER.writeValueAsString(OpenApiDocumentBuilder.build(model(), "Shop"));
        assertEquals(first, second);
    }

    @Test
    void namespacedNamesFoldIntoValidComponentNames() {
        assertEquals("pack__Thing", OpenApiDocumentBuilder.componentName("pack::Thing").replace("::", "__")
                .replace("_", "_"));
        assertTrue(OpenApiDocumentBuilder.componentName("pack::Thing").matches("[A-Za-z0-9._-]+"));
    }

    private static void collectRefs(JsonNode node, List<String> out) {
        if (node.isObject()) {
            if (node.has("$ref")) {
                out.add(node.get("$ref").asText());
            }
            node.elements().forEachRemaining(child -> collectRefs(child, out));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(child -> collectRefs(child, out));
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> names(JsonNode object) {
        List<String> out = new ArrayList<>();
        Iterator<String> it = object.fieldNames();
        it.forEachRemaining(out::add);
        return out;
    }
}
