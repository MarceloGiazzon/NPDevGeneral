package com.finalexec.agent;

import com.npdev.dsl.v1.compiled.CompiledAgentAccess;
import com.npdev.dsl.v1.compiled.CompiledAgentAccessExposure;
import com.npdev.dsl.v1.compiled.CompiledAggregate;
import com.npdev.dsl.v1.compiled.CompiledAggregateCollection;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledSchema;
import com.npdev.dsl.v1.compiled.SqlIdentifierSupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AGENT-1 (A3): turns the model's {@code agentAccess.expose[]} into a list of tools for ONE user
 * (their roles). Pure: no I/O, no Spring -- just a translation from the compiled model.
 *
 * <p>This only decides what is OFFERED. Every call still goes through the app's REST API as the
 * user ({@link AgentApiExecutor}), so permissions and concept {@code access} rules are enforced
 * there, not here.
 */
public final class AgentToolCatalog {

    public enum Kind { CONCEPT, FLOW, AGGREGATE }

    /** One tool. {@code inputSchema} is the JSON-Schema object sent to MCP clients and LLMs. For an
     *  AGGREGATE tool, {@code conceptName} is the root concept and {@code route} the aggregate name
     *  (its path segment under {@code /api/runtime/aggregate/}). */
    public record AgentTool(String name, String title, String description, Map<String, Object> inputSchema,
            Kind kind, String conceptName, String route, String operation, String flowName,
            boolean write, boolean confirmWrites, List<String> exposedFields) {
    }

    private AgentToolCatalog() {
    }

    public static List<AgentTool> toolsFor(CompiledModel model, Set<String> userRoles) {
        CompiledAgentAccess access = model.getAgentAccess();
        if (access == null) {
            return List.of();
        }
        List<AgentTool> tools = new ArrayList<>();
        for (CompiledAgentAccessExposure exposure : access.getExpose()) {
            if (!offeredTo(exposure, userRoles)) {
                continue;
            }
            if (exposure.getConcept() != null) {
                model.findConcept(exposure.getConcept())
                        .ifPresent(concept -> addConceptTools(model, concept, exposure, tools));
            } else if (exposure.getFlow() != null) {
                model.findFlow(exposure.getFlow()).ifPresent(flow -> tools.add(flowTool(flow, exposure)));
            } else if (exposure.getAggregate() != null) {
                model.getAggregates().stream()
                        .filter(aggregate -> aggregate.name().equalsIgnoreCase(exposure.getAggregate()))
                        .findFirst()
                        .ifPresent(aggregate -> addAggregateTools(model, aggregate, exposure, tools));
            }
        }
        return tools;
    }

    public static Optional<AgentTool> find(List<AgentTool> tools, String name) {
        return tools.stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    static boolean offeredTo(CompiledAgentAccessExposure exposure, Set<String> userRoles) {
        List<String> roles = exposure.getRoles();
        if (roles == null || roles.isEmpty()) {
            return true;
        }
        for (String role : roles) {
            for (String held : userRoles) {
                if (held.equalsIgnoreCase(role)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addConceptTools(CompiledModel model, CompiledConcept concept,
            CompiledAgentAccessExposure exposure, List<AgentTool> out) {
        // The REST route the generated CRUD controller registers this concept under -- ControllerEmitter
        // computes it exactly like this; using anything else gives 404s.
        String route = SqlIdentifierSupport.aliasPreservingTableName(concept, model.getContexts());
        String toolSuffix = safeName(concept.getName());
        String label = concept.getUi() != null && concept.getUi().getLabel() != null
                ? concept.getUi().getLabel() : concept.getName();
        String about = exposure.getDescription() != null ? exposure.getDescription() : label;
        List<CompiledField> fields = exposedFields(concept, exposure);
        List<String> fieldNames = fields.stream().map(CompiledField::getName).toList();
        List<String> operations = exposure.getOperations() == null || exposure.getOperations().isEmpty()
                ? List.of("list", "get") : exposure.getOperations();
        boolean confirm = exposure.getConfirmWrites();

        for (String op : operations) {
            switch (op) {
                case "list" -> out.add(new AgentTool("list_" + toolSuffix, "List " + label,
                        "List " + label + " records. " + about
                                + " Returns a page of records; use 'where' to filter by exact field values.",
                        listSchema(fields), Kind.CONCEPT, concept.getName(), route, "list", null, false, false,
                        fieldNames));
                case "get" -> out.add(new AgentTool("get_" + toolSuffix, "Get " + label,
                        "Get one " + label + " by its id. " + about,
                        objectSchema(Map.of("id", prop("string", "The record id")), List.of("id")),
                        Kind.CONCEPT, concept.getName(), route, "get", null, false, false, fieldNames));
                case "create" -> out.add(new AgentTool("create_" + toolSuffix, "Create " + label,
                        "Create a new " + label + ". " + about,
                        fieldsSchema(fields, true, false), Kind.CONCEPT, concept.getName(), route, "create", null,
                        true, confirm, fieldNames));
                case "update" -> out.add(new AgentTool("update_" + toolSuffix, "Update " + label,
                        "Change fields of an existing " + label + ". Send the id and only the fields to change. "
                                + about,
                        fieldsSchema(fields, false, true), Kind.CONCEPT, concept.getName(), route, "update", null,
                        true, confirm, fieldNames));
                case "delete" -> out.add(new AgentTool("delete_" + toolSuffix, "Delete " + label,
                        "Delete one " + label + " by id. " + about,
                        objectSchema(Map.of("id", prop("string", "The record id")), List.of("id")),
                        Kind.CONCEPT, concept.getName(), route, "delete", null, true, confirm, fieldNames));
                default -> { /* schema enum makes this unreachable */ }
            }
        }
    }

    private static AgentTool flowTool(CompiledFlow flow, CompiledAgentAccessExposure exposure) {
        Map<String, Object> schema = flow.getInputSchema() != null
                ? toJsonSchema(flow.getInputSchema()) : objectSchema(Map.of(), List.of());
        String about = exposure.getDescription() != null
                ? exposure.getDescription() : "Runs the " + flow.getName() + " process.";
        boolean confirm = exposure.getConfirmWrites();
        return new AgentTool("run_" + safeName(flow.getName()), "Run " + flow.getName(), about, schema,
                Kind.FLOW, null, null, "run", flow.getName(), true, confirm, List.of());
    }

    /** P8: {@code get_<Aggregate>} reads the whole tree, {@code save_<Aggregate>} creates or REPLACES it
     *  in one call -- e.g. a 20x33 mosaic is one tool call, not 661. */
    private static void addAggregateTools(CompiledModel model, CompiledAggregate aggregate,
            CompiledAgentAccessExposure exposure, List<AgentTool> out) {
        Optional<CompiledConcept> root = model.findConcept(aggregate.root());
        if (root.isEmpty()) {
            return;
        }
        String label = root.get().getUi() != null && root.get().getUi().getLabel() != null
                ? root.get().getUi().getLabel() : root.get().getName();
        String about = exposure.getDescription() != null ? exposure.getDescription() : label;
        List<String> collections = aggregate.collections().stream().map(CompiledAggregateCollection::name).toList();
        String withChildren = collections.isEmpty() ? "" : " with its " + String.join(", ", collections);
        List<String> operations = exposure.getOperations() == null || exposure.getOperations().isEmpty()
                ? List.of("get") : exposure.getOperations();
        String suffix = safeName(aggregate.name());
        for (String op : operations) {
            switch (op) {
                case "get" -> out.add(new AgentTool("get_" + suffix, "Get " + label + withChildren,
                        "Get one " + label + withChildren + ", whole, by the " + label + "'s id. " + about,
                        objectSchema(Map.of("id", prop("string", "The " + label + " id")), List.of("id")),
                        Kind.AGGREGATE, root.get().getName(), aggregate.name(), "get", null, false, false, List.of()));
                case "save" -> out.add(new AgentTool("save_" + suffix, "Save " + label + withChildren,
                        "Save one " + label + withChildren + ", new or existing, in ONE call. Omit id to add a new "
                                + label + "; send an existing id to replace it. Root fields you leave out keep their current"
                                + " values, but lists are replaced whole: every row you leave out of a list is"
                                + " deleted, so always send the complete lists. " + about,
                        aggregateSaveSchema(model, root.get(), aggregate.collections()),
                        Kind.AGGREGATE, root.get().getName(), aggregate.name(), "save", null, true,
                        exposure.getConfirmWrites(), List.of()));
                default -> { /* validation makes this unreachable */ }
            }
        }
    }

    private static Map<String, Object> aggregateSaveSchema(CompiledModel model, CompiledConcept root,
            List<CompiledAggregateCollection> collections) {
        Map<String, Object> schema = fieldsSchema(exposedFields(root, null), true, false);
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> withId = new LinkedHashMap<>();
        withId.put("id", prop("string", "Omit to create; the existing id to replace it"));
        withId.putAll(props);
        schema.put("properties", withId);
        addCollectionProps(model, collections, withId);
        return schema;
    }

    private static void addCollectionProps(CompiledModel model, List<CompiledAggregateCollection> collections,
            Map<String, Object> props) {
        for (CompiledAggregateCollection collection : collections) {
            Optional<CompiledConcept> child = model.findConcept(collection.concept());
            if (child.isEmpty()) {
                continue;
            }
            List<CompiledField> fields = exposedFields(child.get(), null).stream()
                    .filter(field -> !field.getName().equals(collection.childField()))
                    .toList();
            Map<String, Object> item = fieldsSchema(fields, true, false);
            @SuppressWarnings("unchecked")
            Map<String, Object> itemProps = (Map<String, Object>) item.get("properties");
            addCollectionProps(model, collection.collections(), itemProps);
            Map<String, Object> array = new LinkedHashMap<>();
            array.put("type", "array");
            array.put("description", "Every " + collection.concept() + " row of this tree (no ids needed; "
                    + collection.childField() + " is set for you)");
            array.put("items", item);
            props.put(collection.name(), array);
        }
    }

    /** Never: sensitive fields, derived fields, object/array/file fields. Allow-list from
     *  exposure.fields when given. */
    static List<CompiledField> exposedFields(CompiledConcept concept, CompiledAgentAccessExposure exposure) {
        List<String> allow = exposure == null ? null : exposure.getFields();
        List<CompiledField> out = new ArrayList<>();
        for (CompiledField field : concept.getFields()) {
            if (field.isSensitive()) {
                continue;
            }
            String type = field.getDslType() == null ? "" : field.getDslType().toLowerCase(Locale.ROOT);
            if (type.equals("object") || type.equals("array") || type.equals("file")) {
                continue;
            }
            if (allow != null && !allow.isEmpty() && !allow.contains(field.getName()) && !field.isId()) {
                continue;
            }
            out.add(field);
        }
        return out;
    }

    private static Map<String, Object> listSchema(List<CompiledField> fields) {
        Map<String, Object> where = new LinkedHashMap<>();
        where.put("type", "array");
        where.put("description", "Exact-match filters, each {field, op, value}. op is eq, ne or contains. "
                + "Fields: " + String.join(", ", fields.stream().map(CompiledField::getName).toList()));
        Map<String, Object> clause = objectSchema(Map.of(
                "field", prop("string", "Field name"),
                "op", enumProp(List.of("eq", "ne", "contains"), "Comparison"),
                "value", prop("string", "Value as text")), List.of("field", "op", "value"));
        where.put("items", clause);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("where", where);
        props.put("search", prop("string", "Free text matched against the record's text fields"));
        props.put("page", prop("integer", "Page number, starting at 0"));
        props.put("size", prop("integer", "Page size, 1-100, default 20"));
        return objectSchema(props, List.of());
    }

    private static Map<String, Object> fieldsSchema(List<CompiledField> fields, boolean forCreate, boolean withId) {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        if (withId) {
            props.put("id", prop("string", "The record id"));
            required.add("id");
        }
        for (CompiledField field : fields) {
            if (field.isId()) {
                continue;
            }
            CompiledSchema schema = field.getSchema();
            if (schema != null && schema.getDerivedExpression() != null) {
                continue;
            }
            props.put(field.getName(), fieldProp(field));
            if (forCreate && field.isRequired()) {
                required.add(field.getName());
            }
        }
        return objectSchema(props, required);
    }

    static Map<String, Object> fieldProp(CompiledField field) {
        String type = field.getDslType() == null ? "string" : field.getDslType().toLowerCase(Locale.ROOT);
        String label = field.getUi() != null && field.getUi().getLabel() != null
                ? field.getUi().getLabel() : field.getName();
        return switch (type) {
            case "int", "integer", "long" -> prop("integer", label);
            case "decimal" -> prop("number", label);
            case "boolean" -> prop("boolean", label);
            case "date" -> prop("string", label + " (date, YYYY-MM-DD)");
            case "datetime" -> prop("string", label + " (date-time, ISO-8601, e.g. 2026-10-01T14:30:00)");
            case "enum" -> enumProp(field.getEnumValues(), label);
            case "reference" -> prop("string", label + " -- the id of a " + field.getReferenceTarget()
                    + "; use list_" + safeName(String.valueOf(field.getReferenceTarget()))
                    + " to find it if that tool exists");
            default -> prop("string", label);
        };
    }

    static Map<String, Object> toJsonSchema(CompiledSchema schema) {
        Map<String, Object> out = new LinkedHashMap<>();
        String type = schema.getType() == null ? "object" : schema.getType();
        out.put("type", type);
        if (schema.getDescription() != null) {
            out.put("description", schema.getDescription());
        }
        if (schema.getEnumValues() != null && !schema.getEnumValues().isEmpty()) {
            out.put("enum", schema.getEnumValues());
        }
        if ("object".equals(type)) {
            Map<String, Object> props = new LinkedHashMap<>();
            if (schema.getProperties() != null) {
                schema.getProperties().forEach((name, child) -> props.put(name, toJsonSchema(child)));
            }
            out.put("properties", props);
            if (schema.getRequired() != null && !schema.getRequired().isEmpty()) {
                out.put("required", schema.getRequired());
            }
        }
        if ("array".equals(type) && schema.getItems() != null) {
            out.put("items", toJsonSchema(schema.getItems()));
        }
        return out;
    }

    static String safeName(String raw) {
        String cleaned = raw.replace("::", "__").replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isEmpty() || !Character.isLetter(cleaned.charAt(0))) {
            cleaned = "x" + cleaned;
        }
        return cleaned.length() > 50 ? cleaned.substring(0, 50) : cleaned;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, Collection<String> required) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", "object");
        out.put("properties", new LinkedHashMap<>(properties));
        if (!required.isEmpty()) {
            out.put("required", List.copyOf(required));
        }
        return out;
    }

    private static Map<String, Object> prop(String type, String description) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("description", description);
        return out;
    }

    private static Map<String, Object> enumProp(List<String> values, String description) {
        Map<String, Object> out = prop("string", description);
        out.put("enum", List.copyOf(values));
        return out;
    }
}
