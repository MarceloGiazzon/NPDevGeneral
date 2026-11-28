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
              "dslVersion": "3.0.0", "namespace": "shop", "version": "1.0",
              "concepts": [
                { "name": "Order", "composition": { "collections": [
                    { "name": "lines", "concept": "OrderLine", "childField": "orderId", "ownership": "owned" } ] }, "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "total", "type": "decimal", "required": true },
                    { "name": "status", "type": "enum", "enumValues": ["OPEN", "PAID"] },
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
                "/api/v1/runtime/aggregate/Order", "/api/v1/runtime/aggregate/Order/{rootId}",
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
                  "dslVersion": "3.0.0", "namespace": "desk", "version": "1.0",
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
        JsonNode schema = doc().get("components").get("schemas").get("OrderAggregate");
        JsonNode lines = schema.get("properties").get("lines");
        assertEquals("array", lines.get("type").asText());
        assertEquals("#/components/schemas/OrderLine", lines.get("items").get("allOf").get(0).get("$ref").asText());
    }

    /** REG-313 (b): a query parameter with a defaultExpression is optional -- the document must not say otherwise. */
    @Test
    void aDefaultedQueryParameterIsAdvertisedAsOptionalAndNamesItsDefault() throws Exception {
        String json = """
                {
                  "dslVersion": "3.0.0", "namespace": "trial", "version": "1.0",
                  "concepts": [
                    { "name": "Visit", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "dueDate", "type": "date" },
                        { "name": "total", "type": "decimal" } ] }
                  ],
                  "queries": [
                    { "name": "Overdue", "concept": "Visit", "where": "dueDate < :asOf and total >= :minTotal",
                      "parameters": [
                        { "name": "asOf", "type": "date", "defaultExpression": "addDays(today(), -7)",
                          "description": "Visits due before this date" },
                        { "name": "minTotal", "type": "decimal", "required": true } ] }
                  ]
                }
                """;
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(json)));
        JsonNode parameters = MAPPER.valueToTree(OpenApiDocumentBuilder.build(compiled, "Trial"))
                .get("paths").get("/api/queries/Overdue").get("get").get("parameters");
        JsonNode asOf = null;
        JsonNode minTotal = null;
        for (JsonNode parameter : parameters) {
            if ("asOf".equals(parameter.get("name").asText())) {
                asOf = parameter;
            } else if ("minTotal".equals(parameter.get("name").asText())) {
                minTotal = parameter;
            }
        }
        assertFalse(asOf.get("required").asBoolean(), "a defaulted parameter may be omitted");
        assertTrue(asOf.get("description").asText().contains("Visits due before this date"));
        assertTrue(asOf.get("description").asText().contains("addDays(today(), -7)"),
                "the document names the default so a caller knows what omitting it means");
        assertTrue(minTotal.get("required").asBoolean(), "a parameter without a default is still required");
    }

    /**
     * Mockuper plan X1: the prose "id of sales::Customer" is not a name a client generator can resolve (the schema is
     * {@code sales__Customer}), and a name match fails behind the pack prefix, so a record creator inserted quotes
     * before customers (I0, 2026-11-28, real quotes 1.0.5 document). The reference field names its target component.
     */
    @Test
    void aReferenceFieldNamesTheComponentOfItsTarget() throws Exception {
        String json = """
                {
                  "dslVersion": "3.0.0", "namespace": "crm", "version": "1.0",
                  "concepts": [
                    { "name": "Customer", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "name", "type": "string" } ] },
                    { "name": "Secret", "system": true, "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true } ] },
                    { "name": "Quote", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "customerId", "type": "reference", "reference": { "target": "Customer" } },
                        { "name": "secretId", "type": "reference", "reference": { "target": "Secret" } },
                        { "name": "number", "type": "string" } ] }
                  ]
                }
                """;
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(json)));
        JsonNode doc = MAPPER.valueToTree(OpenApiDocumentBuilder.build(compiled, "Crm"));
        JsonNode schemas = doc.get("components").get("schemas");
        JsonNode customer = schemas.get("Quote").get("properties").get("customerId");
        assertEquals("Customer", customer.get("x-npdev-ref").asText());
        assertEquals("id of Customer", customer.get("description").asText(), "the prose stays for human readers");
        assertTrue(schemas.has(customer.get("x-npdev-ref").asText()), "the extension names a schema that exists");
        JsonNode secret = schemas.get("Quote").get("properties").get("secretId");
        assertFalse(secret.has("x-npdev-ref"), "a platform-owned concept has no component to point at");
        assertFalse(schemas.get("Quote").get("properties").get("number").has("x-npdev-ref"));
        assertFalse(schemas.get("Quote").get("properties").get("id").has("x-npdev-ref"));
    }

    private static final String LIFECYCLE_MODEL = """
            {
              "dslVersion": "3.0.0", "namespace": "sales", "version": "1.0",
              "concepts": [
                { "name": "Quote",
                  "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "status", "type": "enum", "enumValues": ["DRAFT", "SENT", "APPROVED", "REJECTED", "ARCHIVED"] } ],
                  "lifecycle": {
                    "statusField": "status",
                    "states": [ { "value": "SENT" }, { "value": "DRAFT", "initial": true }, { "value": "APPROVED" },
                                { "value": "REJECTED" }, { "value": "ARCHIVED" } ],
                    "transitions": [
                      { "from": "DRAFT", "to": "SENT", "event": "QuoteSent" },
                      { "from": "SENT", "to": "APPROVED", "event": "QuoteApproved",
                        "guard": "$user.roles.contains('ADMIN') || $user.roles.contains('SALESMANAGER')" },
                      { "from": "SENT", "to": "REJECTED", "event": "QuoteRejected",
                        "guard": "$user.roles.contains('ADMIN') || $user.roles.contains('ADMIN') || $user.roles.contains(\\"BOSS\\")" },
                      { "from": "REJECTED", "to": "ARCHIVED", "event": "QuoteArchived",
                        "guard": "$user.roles.contains('ADMIN') && status == 'REJECTED'" },
                      { "from": "DRAFT", "to": "ARCHIVED", "event": "QuoteDropped",
                        "guard": "$user.roles.contains('ADMIN') || ownerId == $user.id" } ] } },
                { "name": "Note", "fields": [
                    { "name": "id", "type": "uuid", "id": true, "required": true },
                    { "name": "state", "type": "string" } ],
                  "lifecycle": {
                    "statusField": "state",
                    "transitions": [ { "from": "A", "to": "B", "event": "Moved" } ] } }
              ]
            }
            """;

    private static JsonNode lifecycleDoc() throws Exception {
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(LIFECYCLE_MODEL)));
        return MAPPER.valueToTree(OpenApiDocumentBuilder.build(compiled, "Sales")).get("components").get("schemas");
    }

    /**
     * Mockuper plan X3. A client that creates records cannot see which states a row can reach, nor who may move it:
     * the status field is a plain enum. The kernel accepts any declared state on create and checks transitions and
     * user guards only on update, so the document publishes the machine, and the role list only for a guard that is
     * nothing but a disjunction of role tests (the form the compiler leaves for {@code role('x')}).
     */
    @Test
    void aConceptWithALifecyclePublishesItsStatesAndWhoMayMoveBetweenThem() throws Exception {
        JsonNode lifecycle = lifecycleDoc().get("Quote").get("x-npdev-lifecycle");
        assertEquals("status", lifecycle.get("field").asText());
        assertEquals("DRAFT", lifecycle.get("initial").asText(), "the state flagged initial, not the first declared");
        JsonNode steps = lifecycle.get("transitions");
        assertEquals(5, steps.size());
        // the compiler orders the transitions (not by declaration), so each one is looked up by its ends
        JsonNode send = step(steps, "DRAFT", "SENT");
        assertFalse(send.has("roles") || send.has("conditional"), "no guard: any writer may take it");
        assertEquals(List.of("ADMIN", "SALESMANAGER"), strings(step(steps, "SENT", "APPROVED").get("roles")));
        assertEquals(List.of("ADMIN", "BOSS"), strings(step(steps, "SENT", "REJECTED").get("roles")),
                "duplicates fold, both quote styles read");
        JsonNode archive = step(steps, "REJECTED", "ARCHIVED");
        assertTrue(archive.get("conditional").asBoolean(), "a guard that also tests a field is not decided by roles");
        assertFalse(archive.has("roles"));
        JsonNode drop = step(steps, "DRAFT", "ARCHIVED");
        assertTrue(drop.get("conditional").asBoolean(), "a guard on the row's owner is not decided by roles");
        assertFalse(drop.has("roles"));
        assertFalse(steps.toString().contains("ownerId"), "the guard text is not published");
        for (JsonNode step : steps) {
            assertTrue(step.has("from") && step.has("to"));
        }
    }

    /**
     * Mockuper plan X2. The kernel generates the id, overwrites a derived field with the computed value, and stamps
     * the caller into a {@code $user} default when the field is left blank. A client that creates records must be told,
     * or it invents a preparedBy and the row disappears from the identity it acts as (I0, quotes 1.0.5).
     */
    @Test
    void fieldsTheServerFillsAreMarkedSoAClientLeavesThemOut() throws Exception {
        String json = """
                {
                  "dslVersion": "3.0.0", "namespace": "sales", "version": "1.0",
                  "concepts": [
                    { "name": "Line", "fields": [
                        { "name": "id", "type": "uuid", "id": true, "required": true },
                        { "name": "qty", "type": "decimal" },
                        { "name": "price", "type": "decimal" },
                        { "name": "total", "type": "decimal", "derivedExpression": "qty * price" },
                        { "name": "preparedBy", "type": "string", "defaultExpression": "$user.id" },
                        { "name": "issuedOn", "type": "date", "defaultExpression": "today()" },
                        { "name": "status", "type": "string", "default": "NEW" },
                        { "name": "note", "type": "string" } ] }
                  ]
                }
                """;
        CompiledModel compiled = new ModelCompiler().compile(new JsonModelParser().parse(MAPPER.readTree(json)));
        JsonNode props = MAPPER.valueToTree(OpenApiDocumentBuilder.build(compiled, "Sales"))
                .get("components").get("schemas").get("Line").get("properties");
        assertTrue(props.get("id").get("readOnly").asBoolean(), "the server generates the id");
        assertTrue(props.get("total").get("readOnly").asBoolean(), "the server computes a derived field");
        assertFalse(props.get("total").has("x-npdev-stamp"));
        assertEquals("caller", props.get("preparedBy").get("x-npdev-stamp").asText());
        assertFalse(props.get("preparedBy").has("readOnly"), "a value is accepted, so it is not read-only");
        for (String plain : List.of("qty", "price", "issuedOn", "status", "note")) {
            assertFalse(props.get(plain).has("readOnly") || props.get(plain).has("x-npdev-stamp"),
                    plain + " is an ordinary field: a default is a convenience, not a constraint");
        }
        assertEquals("x-npdev-stamp", OpenApiDocumentBuilder.STAMP_EXTENSION);
    }

    @Test
    void aLifecycleWithoutDeclaredStatesPublishesNothingRatherThanAGuessedInitialState() throws Exception {
        JsonNode schemas = lifecycleDoc();
        assertFalse(schemas.get("Note").has("x-npdev-lifecycle"));
    }

    @Test
    void roleGuardParsingOnlyAcceptsAPureDisjunctionOfRoleTests() {
        assertEquals(List.of("A"), OpenApiDocumentBuilder.rolesOfRoleOnlyGuard("$user.roles.contains('A')"));
        assertEquals(List.of("A", "B"),
                OpenApiDocumentBuilder.rolesOfRoleOnlyGuard(" $user.roles.contains( 'A' )||$user.roles.contains('B') "));
        for (String notPure : List.of("", "status == 'X'", "$user.roles.contains('A') && x > 1",
                "($user.roles.contains('A') || $user.roles.contains('B'))", "!$user.roles.contains('A')",
                "$user.roles.contains(role('a'))", "$user.roles.contains('A') || ")) {
            assertTrue(OpenApiDocumentBuilder.rolesOfRoleOnlyGuard(notPure).isEmpty(), "not a pure role guard: " + notPure);
        }
        assertTrue(OpenApiDocumentBuilder.rolesOfRoleOnlyGuard(null).isEmpty());
    }

    @Test
    void aConceptWithoutALifecycleCarriesNoLifecycleExtension() throws Exception {
        assertFalse(doc().get("components").get("schemas").get("Order").has("x-npdev-lifecycle"));
        assertEquals("x-npdev-lifecycle", OpenApiDocumentBuilder.LIFECYCLE_EXTENSION);
    }

    @Test
    void theExtensionIsTheComponentNameWhenThePackNamespaceIsFolded() {
        // "sales::Customer" is the model name of a pack concept; its schema (and so the extension) is "sales__Customer"
        assertEquals("sales__Customer", OpenApiDocumentBuilder.componentName("sales::Customer"));
        assertEquals("x-npdev-ref", OpenApiDocumentBuilder.REF_EXTENSION);
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

    private static JsonNode step(JsonNode transitions, String from, String to) {
        for (JsonNode transition : transitions) {
            if (from.equals(transition.get("from").asText()) && to.equals(transition.get("to").asText())) {
                return transition;
            }
        }
        throw new AssertionError("no transition " + from + " -> " + to + " in " + transitions);
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
