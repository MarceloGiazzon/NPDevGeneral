package com.finalexec.api;

import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollection;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledContext;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledLifecycle;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledQueryParameter;
import com.npdev.dsl.v1.compiled.CompiledPublicRead;
import com.npdev.dsl.v1.compiled.CompiledQuery;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.CompiledStateMachineState;
import com.npdev.dsl.v1.compiled.CompiledStateTransition;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;
import com.npdev.kernel.concepts.UserScopedExpressions;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * INT-7: builds the OpenAPI 3.0.3 document of a running app from its live {@link CompiledModel}, so
 * the spec a third-party developer imports into Postman or a client generator always matches the
 * routes and payload shapes the app actually serves (and follows a {@code /model-reload}).
 *
 * <p>Pure and deterministic: concepts, queries, flows and aggregates are emitted sorted by name into
 * {@link LinkedHashMap}s, so two calls over the same model produce identical JSON. Schemas only --
 * no example data, tenant ids or keys, because the document is served without authentication.
 *
 * <p>Route families (each mirrors a real controller; the code wins over any doc):
 * <ul>
 *   <li>{@code /api/concepts/{route}} list/create, {@code /{id}} get/update/delete,
 *       {@code /batch} (generated {@code ConceptCrudController}). {@code {route}} is the concept's
 *       REST collection name ({@link SqlIdentifierSupport#restCollectionName}, e.g. {@code Order} is
 *       {@code orders}, {@code TicketCategory} is {@code ticket_categories}), NOT its model name: the
 *       controller answers {@code 400 unknown_concept} to the model name. Summaries, tags and schema
 *       names still use the model name. The list envelope is
 *       {@code {content,page,size,totalElements,sort,direction,filter,where,asOf}}.</li>
 *   <li>{@code /api/queries/{Query}} (rows envelope, declared parameters become query params).</li>
 *   <li>{@code /api/v1/flows/{Flow}/execute} for every flow; start endpoints are tagged
 *       {@code x-npdev-start-endpoint}.</li>
 *   <li>{@code /api/v1/runtime/aggregate/{Aggregate}} load/commit.</li>
 *   <li>{@code /api/public/concepts/{Concept}} for concepts declaring {@code access.public}
 *       (anonymous, allow-listed fields only).</li>
 *   <li>the fixed runtime/admin paths the generated {@code OpenApiController} always listed.</li>
 * </ul>
 *
 * <p>Vendor extensions for a client that CREATES records over this API (Mockuper plan): {@code x-npdev-ref} on a
 * reference field ({@link #REF_EXTENSION}), {@code x-npdev-lifecycle} on a concept schema
 * ({@link #LIFECYCLE_EXTENSION}), and {@code readOnly} / {@code x-npdev-stamp} on the fields the server fills
 * ({@link #markServerFilled}).
 */
public final class OpenApiDocumentBuilder {

    /**
     * Vendor extension on a {@code reference} field: the name of the component schema of the concept it points to
     * (the schema that concept's own POST route takes). Lets a client that creates records over this API order the
     * calls and fill the id without guessing from field names (Mockuper plan, X1).
     */
    static final String REF_EXTENSION = "x-npdev-ref";
    /** Vendor extension on a concept schema: its lifecycle (states and who may move between them); see {@link #lifecycleExtension}. */
    static final String LIFECYCLE_EXTENSION = "x-npdev-lifecycle";
    /** Vendor extension on a field the server fills with the calling user when it is left blank; see {@link #markServerFilled}. */
    static final String STAMP_EXTENSION = "x-npdev-stamp";
    private static final String ROLE_TEST = "\\$user\\.roles\\.contains\\(\\s*(?:'[^']+'|\"[^\"]+\")\\s*\\)";
    private static final Pattern ROLE_ONLY_GUARD =
            Pattern.compile("^\\s*" + ROLE_TEST + "(?:\\s*\\|\\|\\s*" + ROLE_TEST + ")*\\s*$");
    private static final Pattern ROLE_CHECK =
            Pattern.compile("\\$user\\.roles\\.contains\\(\\s*(?:'([^']+)'|\"([^\"]+)\")\\s*\\)");
    private static final String SCHEMA_REF = "#/components/schemas/";
    private static final Set<String> RUNTIME_TAG = Set.of("runtime");

    private OpenApiDocumentBuilder() {
    }

    public static Map<String, Object> build(CompiledModel model, String title) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("openapi", "3.0.3");
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("title", title == null || title.isBlank() ? "NPDev app" : title.trim());
        info.put("version", "v1");
        info.put("description", "Generated from the app's live model. Authenticate with X-Api-Key "
                + "(issued under /api/admin/credentials) or a Bearer JWT.");
        root.put("info", info);
        root.put("servers", List.of(Map.of("url", "/")));

        Map<String, Object> schemas = new LinkedHashMap<>();
        Map<String, Object> paths = new LinkedHashMap<>();
        schemas.put("Error", errorSchema());
        schemas.put("BatchRequest", batchRequestSchema());
        schemas.put("BatchResult", batchResultSchema());
        schemas.put("FlowExecutionResponse", flowResponseSchema());

        List<CompiledConcept> concepts = new ArrayList<>(model.getConcepts());
        concepts.removeIf(CompiledConcept::isSystem); // FORM P0: platform-owned concepts have no public API
        concepts.sort(Comparator.comparing(CompiledConcept::getName, String.CASE_INSENSITIVE_ORDER));
        Set<String> documented = new HashSet<>();
        for (CompiledConcept concept : concepts) {
            documented.add(componentName(concept.getName()));
        }
        for (CompiledConcept concept : concepts) {
            addConcept(concept, model.getContexts(), documented, schemas, paths);
        }
        for (CompiledQuery query : sorted(model.getQueries(), Comparator.comparing(CompiledQuery::name))) {
            addQuery(query, model, schemas, paths);
        }
        for (CompiledFlow flow : sorted(model.getFlows(), Comparator.comparing(CompiledFlow::getName))) {
            addFlow(flow, paths);
        }
        for (CompiledAggregate aggregate : sorted(model.getAggregates(), Comparator.comparing(CompiledAggregate::name))) {
            addAggregate(aggregate, schemas, paths);
        }
        addPublicRead(concepts, schemas, paths);
        addRuntimePaths(paths);

        Map<String, Object> components = new LinkedHashMap<>();
        Map<String, Object> securitySchemes = new LinkedHashMap<>();
        securitySchemes.put("ApiKeyAuth", Map.of("type", "apiKey", "in", "header", "name", "X-Api-Key"));
        securitySchemes.put("BearerAuth", Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT"));
        components.put("securitySchemes", securitySchemes);
        components.put("schemas", schemas);
        root.put("components", components);
        root.put("security", List.of(Map.of("ApiKeyAuth", List.of()), Map.of("BearerAuth", List.of())));
        root.put("paths", paths);
        return root;
    }

    private static <T> List<T> sorted(List<T> items, Comparator<T> byName) {
        List<T> copy = new ArrayList<>(items);
        copy.sort(byName);
        return copy;
    }

    // ------------------------------------------------------------------ concepts

    private static void addConcept(CompiledConcept concept, List<CompiledContext> contexts, Set<String> documented,
                                   Map<String, Object> schemas, Map<String, Object> paths) {
        String name = concept.getName();
        String component = componentName(name);
        Map<String, Object> conceptSchema = conceptSchema(concept, null, documented);
        Map<String, Object> lifecycle = lifecycleExtension(concept.getLifecycle());
        if (lifecycle != null) {
            conceptSchema.put(LIFECYCLE_EXTENSION, lifecycle);
        }
        schemas.put(component, conceptSchema);
        schemas.put(component + "Page", pageSchema(component));

        // REG-312: the controller serves a concept under its REST collection name, never its model
        // name (rest-collection-name-one-derivation); a path built from the model name 400s.
        String base = "/api/concepts/" + SqlIdentifierSupport.restCollectionName(concept, contexts);
        Map<String, Object> collection = new LinkedHashMap<>();
        collection.put("get", operation(name, "List " + name, "list" + component,
                listParameters(), jsonResponse("200", "A page of " + name, ref(component + "Page")),
                null, "400", "401", "403"));
        collection.put("post", operation(name, "Create " + name, "create" + component,
                List.of(), jsonResponse("200", "The created " + name, ref(component)),
                jsonBody(ref(component)), "400", "401", "403", "409"));
        paths.put(base, collection);

        Map<String, Object> item = new LinkedHashMap<>();
        List<Object> idParam = List.of(pathParam("id", "Record id (uuid)"));
        item.put("get", operation(name, "Get " + name, "get" + component,
                idParam, jsonResponse("200", name, ref(component)), null, "400", "401", "403", "404"));
        item.put("put", operation(name, "Update " + name, "update" + component,
                idParam, jsonResponse("200", "The updated " + name, ref(component)),
                jsonBody(ref(component)), "400", "401", "403", "404", "409"));
        Map<String, Object> delete = operation(name, "Delete " + name, "delete" + component,
                idParam, new LinkedHashMap<>(), null, "400", "401", "403", "404", "409");
        responsesOf(delete).put("204", Map.of("description", "Deleted"));
        item.put("delete", delete);
        paths.put(base + "/{id}", item);

        paths.put(base + "/batch", Map.of("post", operation(name, "Batch delete or set a field on " + name,
                "batch" + component, List.of(),
                jsonResponse("200", "Per-id outcomes", ref("BatchResult")),
                jsonBody(ref("BatchRequest")), "400", "401", "403")));
    }

    /**
     * The state machine of a concept as a record-creating client needs it (Mockuper plan, X3):
     * {@code {field, initial, transitions: [{from, to, roles?|conditional?}]}}, or {@code null} when the concept has
     * no lifecycle the kernel enforces. Mirrors {@code ConfiguredConceptGatewaySemanticPolicy.lifecycleOf}: the
     * initial state is the one flagged {@code initial} (the last flag wins) or, with none flagged, the first declared;
     * a transition with a blank end is skipped. A lifecycle that declares transitions but NO states has no initial
     * state the kernel would apply, so nothing is published for it rather than a guess.
     *
     * <p>The kernel accepts ANY declared state when a row is created and checks transitions and guards only on
     * update, so a client that wants a row in a later state walks the transitions, as the right role, with one
     * {@code PUT} (the whole row) per step.
     *
     * <p>{@code roles}: the guard is exactly a disjunction of {@code $user.roles.contains('X')} (the form the
     * compiler leaves after resolving {@code role('x')} to the app's concrete role), so a caller holding ANY of those
     * authorities may take the transition. {@code conditional: true}: the guard says more than roles (a payload field,
     * the row's owner...), so roles alone do not decide and none are listed; the guard text is not published. No guard:
     * neither key, any caller allowed to write the concept may take it.
     */
    static Map<String, Object> lifecycleExtension(CompiledLifecycle lifecycle) {
        if (lifecycle == null || lifecycle.getStatusField() == null || lifecycle.getStatusField().isBlank()) {
            return null;
        }
        String first = null;
        String flagged = null;
        for (CompiledStateMachineState state : lifecycle.getStates()) {
            if (state.getValue() == null || state.getValue().isBlank()) {
                continue;
            }
            if (first == null) {
                first = state.getValue();
            }
            if (state.isInitial()) {
                flagged = state.getValue();
            }
        }
        String initial = flagged != null ? flagged : first;
        List<Object> transitions = new ArrayList<>();
        for (CompiledStateTransition transition : lifecycle.getTransitions()) {
            if (transition.getFrom() == null || transition.getFrom().isBlank()
                    || transition.getTo() == null || transition.getTo().isBlank()) {
                continue;
            }
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("from", transition.getFrom());
            step.put("to", transition.getTo());
            String guard = transition.getGuard();
            if (guard != null && !guard.isBlank()) {
                List<String> roles = rolesOfRoleOnlyGuard(guard);
                if (roles.isEmpty()) {
                    step.put("conditional", true);
                } else {
                    step.put("roles", roles);
                }
            }
            transitions.add(step);
        }
        if (initial == null || transitions.isEmpty()) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", lifecycle.getStatusField());
        out.put("initial", initial);
        out.put("transitions", transitions);
        return out;
    }

    /** The authorities of a guard that is nothing but {@code $user.roles.contains('A') || $user.roles.contains('B')}, else empty. */
    static List<String> rolesOfRoleOnlyGuard(String guard) {
        if (guard == null || !ROLE_ONLY_GUARD.matcher(guard).matches()) {
            return List.of();
        }
        List<String> roles = new ArrayList<>();
        Matcher matcher = ROLE_CHECK.matcher(guard);
        while (matcher.find()) {
            String role = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (!roles.contains(role)) {
                roles.add(role);
            }
        }
        return List.copyOf(roles);
    }

    private static List<Object> listParameters() {
        List<Object> params = new ArrayList<>();
        params.add(queryParam("page", integerSchema(), "Zero-based page number (default 0)."));
        params.add(queryParam("size", integerSchema(), "Page size, 1 to 100 (default 20)."));
        params.add(queryParam("sort", stringSchema(), "Field to sort by."));
        params.add(queryParam("direction", enumString("asc", "desc"), "Sort direction (default asc)."));
        params.add(queryParam("filter", stringSchema(),
                "Free-text substring search across the filterable fields."));
        params.add(queryParam("where", stringSchema(),
                "Structured filter: clauses separated by ';' or ',', each field:op:value with op one of "
                        + "eq, ne, contains, gt, gte, lt, lte (or field=value for eq)."));
        params.add(queryParam("asOf", dateSchema(),
                "ISO date (yyyy-MM-dd); only for concepts declaring temporal:true."));
        return params;
    }

    /**
     * @param documented component names of the concepts this document describes; a reference field whose target
     *                   is one of them also carries {@link #REF_EXTENSION}. The public schemas pass an empty set.
     */
    private static Map<String, Object> conceptSchema(CompiledConcept concept, Set<String> allowedFields,
                                                     Set<String> documented) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (CompiledField field : concept.getFieldsInDeclaredOrder()) {
            if (allowedFields != null && !field.isId()
                    && allowedFields.stream().noneMatch(f -> f.equalsIgnoreCase(field.getName()))) {
                continue;
            }
            properties.put(field.getName(), fieldSchema(field, documented));
            if (allowedFields == null && field.isRequired() && !field.isId()) {
                required.add(field.getName());
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    static Map<String, Object> fieldSchema(CompiledField field, Set<String> documented) {
        String type = field.getDslType() == null ? "string" : field.getDslType().toLowerCase(Locale.ROOT);
        Map<String, Object> out = new LinkedHashMap<>();
        switch (type) {
            case "uuid" -> {
                out.put("type", "string");
                out.put("format", "uuid");
            }
            case "int", "integer" -> out.put("type", "integer");
            case "long" -> {
                out.put("type", "integer");
                out.put("format", "int64");
            }
            case "decimal", "number", "money" -> out.put("type", "number");
            case "boolean" -> out.put("type", "boolean");
            case "date" -> {
                out.put("type", "string");
                out.put("format", "date");
            }
            case "datetime", "timestamp" -> {
                out.put("type", "string");
                out.put("format", "date-time");
            }
            case "enum" -> {
                out.put("type", "string");
                if (!field.getEnumValues().isEmpty()) {
                    out.put("enum", List.copyOf(field.getEnumValues()));
                }
            }
            case "reference" -> {
                out.put("type", "string");
                out.put("description", "id of " + field.getReferenceTarget());
                String target = field.getReferenceTarget();
                // The description above is prose ("sales::Customer"); a client generator needs the COMPONENT name
                // ("sales__Customer"), the schema its POST route takes. Only emitted when that component is in this
                // document: a reference to a platform-owned concept has no component to point at.
                if (target != null && !target.isEmpty() && documented.contains(componentName(target))) {
                    out.put(REF_EXTENSION, componentName(target));
                }
            }
            case "file", "image" -> {
                out.put("type", "object");
                out.put("description", "file handle");
            }
            case "object", "array" -> {
                if (field.getSchema() != null) {
                    out.putAll(toJsonSchema(field.getSchema()));
                } else {
                    out.put("type", type);
                    if ("array".equals(type)) {
                        out.put("items", new LinkedHashMap<>());
                    }
                }
            }
            default -> out.put("type", "string");
        }
        markServerFilled(field, out);
        return out;
    }

    /**
     * What the server does with a field a client sends on create (Mockuper plan, X2), read from the same
     * {@code ConfiguredConceptGatewaySemanticPolicy} rules that apply it:
     * <ul>
     *   <li>{@code readOnly: true} -- the server generates the {@code id}, and COMPUTES a {@code derivedExpression}
     *       field, overwriting whatever was sent; a client leaves both out.</li>
     *   <li>{@code x-npdev-stamp: "caller"} -- a {@code defaultExpression} over {@code $user} (a {@code preparedBy}
     *       owner column): left blank the server stamps the calling user, but a value IS accepted, so it is not
     *       read-only. A client that wants the rows to belong to the identity it acts as must leave it out; sending
     *       something else makes the row invisible to that identity under an {@code ownerId == $user.id} rule.</li>
     * </ul>
     * A plain {@code defaultValue} or a non-user {@code defaultExpression} is a convenience, not a constraint: no marker.
     */
    private static void markServerFilled(CompiledField field, Map<String, Object> out) {
        CompiledSchema schema = field.getSchema();
        boolean derived = schema != null && schema.getDerivedExpression() != null
                && !schema.getDerivedExpression().isBlank();
        if (field.isId() || derived) {
            out.put("readOnly", true);
        } else if (schema != null && schema.getDefaultExpression() != null
                && UserScopedExpressions.referencesUser(schema.getDefaultExpression())) {
            out.put(STAMP_EXTENSION, "caller");
        }
    }

    /** CompiledSchema (flow input, object/array fields) to an OpenAPI schema object. */
    static Map<String, Object> toJsonSchema(CompiledSchema schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = schema.getType() == null ? "object" : schema.getType().toLowerCase(Locale.ROOT);
        switch (type) {
            case "int", "integer", "long" -> out.put("type", "integer");
            case "decimal", "number" -> out.put("type", "number");
            case "boolean", "object", "array", "string" -> out.put("type", type);
            case "uuid" -> {
                out.put("type", "string");
                out.put("format", "uuid");
            }
            case "date" -> {
                out.put("type", "string");
                out.put("format", "date");
            }
            case "datetime" -> {
                out.put("type", "string");
                out.put("format", "date-time");
            }
            default -> out.put("type", "string");
        }
        if (schema.getDescription() != null && !schema.getDescription().isBlank()) {
            out.put("description", schema.getDescription());
        }
        if (schema.getEnumValues() != null && !schema.getEnumValues().isEmpty()) {
            out.put("enum", List.copyOf(schema.getEnumValues()));
        }
        if (schema.getMinLength() != null) {
            out.put("minLength", schema.getMinLength());
        }
        if (schema.getMaxLength() != null) {
            out.put("maxLength", schema.getMaxLength());
        }
        if (schema.getMin() != null) {
            out.put("minimum", schema.getMin());
        }
        if (schema.getMax() != null) {
            out.put("maximum", schema.getMax());
        }
        if ("object".equals(out.get("type"))) {
            Map<String, Object> props = new LinkedHashMap<>();
            if (schema.getProperties() != null) {
                schema.getProperties().forEach((n, child) -> props.put(n, toJsonSchema(child)));
            }
            out.put("properties", props);
            if (schema.getRequired() != null && !schema.getRequired().isEmpty()) {
                out.put("required", List.copyOf(schema.getRequired()));
            }
        }
        if ("array".equals(out.get("type"))) {
            out.put("items", schema.getItems() != null ? toJsonSchema(schema.getItems()) : new LinkedHashMap<>());
        }
        return out;
    }

    private static Map<String, Object> pageSchema(String itemComponent) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("content", Map.of("type", "array", "items", ref(itemComponent)));
        properties.put("page", integerSchema());
        properties.put("size", integerSchema());
        properties.put("totalElements", Map.of("type", "integer", "format", "int64"));
        properties.put("sort", stringSchema());
        properties.put("direction", stringSchema());
        properties.put("filter", stringSchema());
        properties.put("where", stringSchema());
        properties.put("asOf", stringSchema());
        return objectSchema(properties);
    }

    // ------------------------------------------------------------------ queries

    private static void addQuery(CompiledQuery query, CompiledModel model, Map<String, Object> schemas,
                                 Map<String, Object> paths) {
        String component = componentName(query.name()) + "QueryResult";
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", stringSchema());
        properties.put("aggregate", Map.of("type", "boolean"));
        Object rowSchema = new LinkedHashMap<String, Object>(Map.of("type", "object"));
        if (!query.isAggregate() && query.concept() != null && conceptNamed(model, query.concept()) != null) {
            rowSchema = ref(componentName(conceptNamed(model, query.concept()).getName()));
        }
        properties.put("rows", Map.of("type", "array", "items", rowSchema));
        if (query.isAggregate()) {
            properties.put("concept", stringSchema());
            properties.put("groupBy", Map.of("type", "array", "items", Map.of("type", "object")));
        } else {
            properties.put("total", Map.of("type", "integer", "format", "int64"));
            properties.put("offset", integerSchema());
            properties.put("limit", integerSchema());
        }
        schemas.put(component, objectSchema(properties));

        List<Object> parameters = new ArrayList<>();
        for (CompiledQueryParameter parameter : query.parameters()) {
            // REG-313 (b): NamedQueryBinding takes a declared defaultExpression for an absent parameter.
            boolean defaulted = parameter.defaultExpression() != null && !parameter.defaultExpression().isBlank();
            String description = !defaulted ? parameter.description()
                    : (parameter.description() == null || parameter.description().isBlank() ? "" : parameter.description() + " ")
                            + "Optional: when omitted it defaults to " + parameter.defaultExpression() + ".";
            Map<String, Object> param = queryParam(parameter.name(),
                    parameter.schema() != null ? toJsonSchema(parameter.schema()) : stringSchema(),
                    description);
            param.put("required", !defaulted);
            parameters.add(param);
        }
        if (!query.isAggregate()) {
            parameters.add(queryParam("offset", integerSchema(), "Rows to skip (default 0)."));
            parameters.add(queryParam("limit", integerSchema(), "Maximum rows to return."));
        }
        paths.put("/api/queries/" + query.name(), Map.of("get", operation("queries", "Run query " + query.name(),
                "query" + componentName(query.name()), parameters,
                jsonResponse("200", "Query rows", ref(component)), null, "400", "401", "403", "404")));
    }

    private static CompiledConcept conceptNamed(CompiledModel model, String name) {
        for (CompiledConcept concept : model.getConcepts()) {
            if (concept.getName().equalsIgnoreCase(name)) {
                return concept;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ flows

    private static void addFlow(CompiledFlow flow, Map<String, Object> paths) {
        if (flow.getExpose() != null && !"api".equals(flow.getExpose())) {
            return; // FORM P3-T5b: expose ui / internal flows have no public route to document
        }
        Object body = flow.getInputSchema() != null
                ? toJsonSchema(flow.getInputSchema())
                : new LinkedHashMap<String, Object>(Map.of("type", "object"));
        Map<String, Object> post = operation("flows", "Execute flow " + flow.getName(),
                "executeFlow" + componentName(flow.getName()), List.of(),
                jsonResponse("200", "Flow finished (202 when it is waiting for an event, 422 when it failed)",
                        ref("FlowExecutionResponse")),
                jsonBody(body), "401", "403", "422");
        if (flow.isStartEndpoint()) {
            post.put("x-npdev-start-endpoint", true);
        }
        paths.put("/api/v1/flows/" + flow.getName() + "/execute", Map.of("post", post));
    }

    // ------------------------------------------------------------------ aggregates

    private static void addAggregate(CompiledAggregate aggregate, Map<String, Object> schemas,
                                     Map<String, Object> paths) {
        String component = componentName(aggregate.name()) + "Aggregate";
        schemas.put(component, aggregateSchema(aggregate));
        String base = "/api/v1/runtime/aggregate/" + aggregate.name();
        paths.put(base + "/{rootId}", Map.of("get", operation("aggregates", "Load aggregate " + aggregate.name(),
                "loadAggregate" + componentName(aggregate.name()), List.of(pathParam("rootId", "Root record id")),
                jsonResponse("200", "The aggregate tree", ref(component)), null, "401", "403", "404", "503")));
        paths.put(base, Map.of("post", operation("aggregates", "Commit aggregate " + aggregate.name(),
                "commitAggregate" + componentName(aggregate.name()), List.of(),
                jsonResponse("200", "The committed aggregate", ref(component)),
                jsonBody(ref(component)), "400", "401", "403", "503")));
    }

    private static Map<String, Object> aggregateSchema(CompiledAggregate aggregate) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Map<String, Object> properties = new LinkedHashMap<>();
        collectCollections(aggregate.collections(), properties);
        schema.put("description", "Aggregate rooted at " + aggregate.root()
                + ": the root record's fields plus one array per child collection.");
        schema.put("allOf", List.of(ref(componentName(aggregate.root()))));
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static void collectCollections(List<CompiledAggregateCollection> collections, Map<String, Object> out) {
        for (CompiledAggregateCollection collection : collections) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("allOf", List.of(ref(componentName(collection.concept()))));
            if (!collection.collections().isEmpty()) {
                Map<String, Object> nested = new LinkedHashMap<>();
                collectCollections(collection.collections(), nested);
                item.put("type", "object");
                item.put("properties", nested);
            }
            out.put(collection.name(), Map.of("type", "array", "items", item));
        }
    }

    // ------------------------------------------------------------------ public read

    private static void addPublicRead(List<CompiledConcept> concepts, Map<String, Object> schemas,
                                      Map<String, Object> paths) {
        boolean any = false;
        for (CompiledConcept concept : concepts) {
            CompiledPublicRead grant = concept.getAccess() == null ? null : concept.getAccess().getPublicRead();
            if (grant == null || !grant.directlyReadable()) {
                continue;
            }
            any = true;
            String component = componentName(concept.getName()) + "Public";
            schemas.put(component, conceptSchema(concept, Set.copyOf(grant.fields()), Set.of()));
            Map<String, Object> page = new LinkedHashMap<>();
            page.put("items", Map.of("type", "array", "items", ref(component)));
            page.put("total", Map.of("type", "integer", "format", "int64"));
            page.put("hasMore", Map.of("type", "boolean"));
            page.put("offset", integerSchema());
            page.put("limit", integerSchema());
            schemas.put(component + "Page", objectSchema(page));

            List<Object> params = new ArrayList<>();
            params.add(queryParam("limit", integerSchema(), "Maximum rows to return."));
            params.add(queryParam("offset", integerSchema(), "Rows to skip."));
            params.add(queryParam("sort", stringSchema(), "Field to sort by."));
            params.add(queryParam("direction", enumString("asc", "desc"), "Sort direction."));
            Map<String, Object> list = operation("public", "Public list of " + concept.getName(),
                    "publicList" + componentName(concept.getName()), params,
                    jsonResponse("200", "A page of public " + concept.getName(), ref(component + "Page")),
                    null, "400", "404", "429");
            list.put("security", List.of());
            paths.put("/api/public/concepts/" + concept.getName(), Map.of("get", list));

            Map<String, Object> get = operation("public", "Public get of " + concept.getName(),
                    "publicGet" + componentName(concept.getName()), List.of(pathParam("id", "Record id")),
                    jsonResponse("200", concept.getName(), ref(component)), null, "400", "404", "429");
            get.put("security", List.of());
            paths.put("/api/public/concepts/" + concept.getName() + "/{id}", Map.of("get", get));
        }
        if (any) {
            Map<String, Object> catalog = operation("public", "Public concept catalog", "publicCatalog",
                    List.of(), jsonResponse("200", "Public concepts and their served fields",
                            Map.of("type", "array", "items", Map.of("type", "object"))),
                    null, "429");
            catalog.put("security", List.of());
            paths.put("/api/public/concepts", Map.of("get", catalog));
        }
    }

    // ------------------------------------------------------------------ fixed runtime paths

    private static void addRuntimePaths(Map<String, Object> paths) {
        runtimePath(paths, "/api/v1/flows", "get", "List flow definitions");
        runtimePath(paths, "/api/v1/flows/{flowName}/execute", "post", "Execute flow");
        runtimePath(paths, "/api/v1/executions/{executionId}/resume", "post", "Resume execution");
        runtimePath(paths, "/api/v1/executions/summaries", "get", "List execution summaries");
        runtimePath(paths, "/api/v1/traces/summaries", "get", "List trace summaries");
        runtimePath(paths, "/api/v1/traces/{executionId}", "get", "Get trace by execution");
        runtimePath(paths, "/api/v1/events/publish", "post", "Publish external event");
        runtimePath(paths, "/api/v1/correlations/{correlationId}", "get", "Get correlation timeline");
        runtimePath(paths, "/api/v1/audit", "get", "Search audit records (admin)");
        runtimePath(paths, "/api/v1/admin/failures/recent", "get", "List recent permanent failures (operator/admin)");
        runtimePath(paths, "/api/v1/admin/stuck/recent", "get", "List recent stuck executions (operator/admin)");
        runtimePath(paths, "/api/v1/admin/circuits", "get", "List circuit breaker states (admin)");
        runtimePath(paths, "/api/v1/admin/idempotency", "get", "Lookup idempotency record (admin)");
    }

    private static void runtimePath(Map<String, Object> paths, String path, String method, String summary) {
        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put("200", Map.of("description", "OK"));
        responses.put("202", Map.of("description", "Accepted"));
        responses.put("400", Map.of("description", "Invalid request"));
        responses.put("401", Map.of("description", "Unauthorized"));
        responses.put("403", Map.of("description", "Forbidden"));
        responses.put("404", Map.of("description", "Not found"));
        responses.put("422", Map.of("description", "Validation failed"));
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("tags", List.copyOf(RUNTIME_TAG));
        op.put("summary", summary);
        op.put("operationId", "runtime_" + method + "_" + path.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_|_$", ""));
        List<Object> params = new ArrayList<>();
        for (String name : pathVariables(path)) {
            params.add(pathParam(name, name));
        }
        if (!params.isEmpty()) {
            op.put("parameters", params);
        }
        op.put("responses", responses);
        paths.put(path, Map.of(method, op));
    }

    private static List<String> pathVariables(String path) {
        List<String> names = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = path.indexOf('{', from);
            if (open < 0) {
                return names;
            }
            int close = path.indexOf('}', open);
            names.add(path.substring(open + 1, close));
            from = close + 1;
        }
    }

    // ------------------------------------------------------------------ shared pieces

    private static Map<String, Object> errorSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("code", stringSchema());
        properties.put("message", stringSchema());
        return objectSchema(properties);
    }

    private static Map<String, Object> batchRequestSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("operation", enumString("delete", "setField"));
        properties.put("ids", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("field", Map.of("type", "string", "description", "Required for operation setField."));
        properties.put("value", Map.of("description", "New value for operation setField."));
        Map<String, Object> schema = objectSchema(properties);
        schema.put("required", List.of("operation", "ids"));
        return schema;
    }

    private static Map<String, Object> batchResultSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("operation", stringSchema());
        properties.put("requested", integerSchema());
        properties.put("succeeded", integerSchema());
        properties.put("failed", integerSchema());
        properties.put("results", Map.of("type", "array", "items", Map.of("type", "object")));
        return objectSchema(properties);
    }

    private static Map<String, Object> flowResponseSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (String name : List.of("executionId", "correlationId", "traceId", "flowName", "status", "errorCode",
                "error", "awaitedEventName", "awaitedCorrelationId")) {
            properties.put(name, stringSchema());
        }
        properties.put("output", new LinkedHashMap<>());
        properties.put("inputValidationErrors", Map.of("type", "array", "items", Map.of("type", "object")));
        properties.put("invariantViolations", Map.of("type", "array", "items", Map.of("type", "object")));
        properties.put("emittedEvents", Map.of("type", "array", "items", Map.of("type", "object")));
        return objectSchema(properties);
    }

    private static Map<String, Object> operation(String tag, String summary, String operationId,
                                                 List<Object> parameters, Map<String, Object> okResponse,
                                                 Map<String, Object> requestBody, String... errorCodes) {
        Map<String, Object> op = new LinkedHashMap<>();
        op.put("tags", List.of(tag));
        op.put("summary", summary);
        op.put("operationId", operationId);
        if (!parameters.isEmpty()) {
            op.put("parameters", parameters);
        }
        if (requestBody != null) {
            op.put("requestBody", requestBody);
        }
        Map<String, Object> responses = new LinkedHashMap<>(okResponse);
        for (String code : errorCodes) {
            responses.put(code, Map.of("description", errorDescription(code), "content",
                    Map.of("application/json", Map.of("schema", ref("Error")))));
        }
        op.put("responses", responses);
        return op;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> responsesOf(Map<String, Object> operation) {
        return (Map<String, Object>) operation.get("responses");
    }

    private static String errorDescription(String code) {
        return switch (code) {
            case "400" -> "Invalid request";
            case "401" -> "Unauthorized";
            case "403" -> "Forbidden";
            case "404" -> "Not found";
            case "409" -> "Constraint violation";
            case "422" -> "Flow failed or input invalid";
            case "429" -> "Too many requests";
            case "503" -> "Unavailable";
            default -> "Error";
        };
    }

    private static Map<String, Object> jsonResponse(String code, String description, Object schema) {
        Map<String, Object> responses = new LinkedHashMap<>();
        responses.put(code, Map.of("description", description,
                "content", Map.of("application/json", Map.of("schema", schema))));
        return responses;
    }

    private static Map<String, Object> jsonBody(Object schema) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("required", true);
        body.put("content", Map.of("application/json", Map.of("schema", schema)));
        return body;
    }

    private static Map<String, Object> pathParam(String name, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("in", "path");
        p.put("required", true);
        p.put("description", description);
        p.put("schema", stringSchema());
        return p;
    }

    private static Map<String, Object> queryParam(String name, Object schema, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("in", "query");
        p.put("required", false);
        if (description != null && !description.isBlank()) {
            p.put("description", description);
        }
        p.put("schema", schema);
        return p;
    }

    private static Map<String, Object> ref(String component) {
        return new LinkedHashMap<>(Map.of("$ref", SCHEMA_REF + component));
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    private static Map<String, Object> stringSchema() {
        return new LinkedHashMap<>(Map.of("type", "string"));
    }

    private static Map<String, Object> integerSchema() {
        return new LinkedHashMap<>(Map.of("type", "integer"));
    }

    private static Map<String, Object> dateSchema() {
        Map<String, Object> s = stringSchema();
        s.put("format", "date");
        return s;
    }

    private static Map<String, Object> enumString(String... values) {
        Map<String, Object> s = stringSchema();
        s.put("enum", List.of(values));
        return s;
    }

    /** OpenAPI component names allow only [A-Za-z0-9._-]; namespaced "pack::Concept" names are folded. */
    static String componentName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "Unnamed";
        }
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
