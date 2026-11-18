package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.dsl.v1.expr.ComputedExpression.ExpressionException;
import com.npdev.dsl.v1.expr.ComputedExpression.UserFunction;
import com.npdev.dsl.v1.expr.ExprType;
import com.npdev.dsl.v1.expr.ExpressionTypeChecker;
import com.npdev.dsl.v1.expr.StdlibRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * FORM P4-T2 (extension ladder, rung 2): resolves {@code functions[]} by INLINING.
 *
 * <p>A function is a named pure expression ({@code body}) over typed {@code params}. After pack composition (so a pack's
 * functions arrive as {@code pack::name}) this pass validates every function and then rewrites each expression string
 * in the rest of the model, replacing every call by the function's body with the arguments substituted. The result is
 * plain expression text, so the Java evaluator, the CEL matcher, the client evaluator and SQL pushdown need no knowledge
 * of functions at all. The {@code functions} array is consumed here and does not appear in the resolved model.
 *
 * <p>A function may call other functions of its own pack (or of the root, for a root function) -- never itself, directly
 * or through others (the call graph must be acyclic). Its body may read only its parameters and {@code $} references
 * such as {@code $prop.cap}: reading a row field would make the function depend on where it is called from.
 */
final class FunctionInlining {

    /** A function or a call that cannot be resolved; {@code path} is a JSON pointer into the resolved model. */
    static final class Problem extends RuntimeException {
        private final String path;

        Problem(String path, String message) {
            super(message);
            this.path = path;
        }

        String path() {
            return path;
        }
    }

    private static final Set<String> TYPES = Set.of("integer", "decimal", "string", "boolean", "date", "datetime", "array");
    private static final Pattern LOCAL_NAME = Pattern.compile("^[a-z][A-Za-z0-9_]*$");
    private static final Pattern PARAM_NAME = Pattern.compile("^[a-z][A-Za-z0-9_]*$");
    /** Free text, never an expression, even when it happens to parse as one. */
    private static final Set<String> PROSE_KEYS = Set.of(
            "name", "description", "title", "label", "summary", "message", "hint", "help", "notes", "doc",
            "documentation", "purpose", "reason", "example");
    private static final Set<String> SKIPPED_ROOT_KEYS = Set.of("functions", "metadata", "packs", "provides", "modules");

    private FunctionInlining() {
    }

    static void apply(ObjectNode resolved) {
        JsonNode declared = resolved.get("functions");
        if (declared == null) {
            return;
        }
        if (!declared.isArray()) {
            throw new Problem("/functions", "functions must be an array of {name, params, returns, body}");
        }
        Map<String, UserFunction> table = new LinkedHashMap<>();
        Map<String, List<String>> paramTypes = new HashMap<>();
        Map<String, String> returnTypes = new HashMap<>();
        Map<String, String> paths = new HashMap<>();
        for (int i = 0; i < declared.size(); i++) {
            readFunction(declared.get(i), "/functions/" + i, table, paramTypes, returnTypes, paths);
        }
        Map<String, UserFunction> expanded = new LinkedHashMap<>();
        for (UserFunction function : table.values()) {
            String path = paths.get(function.name());
            String body;
            try {
                body = ComputedExpression.inlineFunctionBody(function, lookup(ownerOf(function.name()), table));
            } catch (ExpressionException e) {
                throw new Problem(path + "/body", e.getMessage());
            }
            checkBody(function, body, paramTypes.get(function.name()), returnTypes.get(function.name()), path);
            expanded.put(function.name(), new UserFunction(function.name(), function.params(), body));
        }
        for (Map.Entry<String, JsonNode> entry : fieldsOf(resolved)) {
            if (SKIPPED_ROOT_KEYS.contains(entry.getKey())) {
                continue;
            }
            JsonNode value = entry.getValue();
            if (value.isArray()) {
                for (int i = 0; i < value.size(); i++) {
                    JsonNode member = value.get(i);
                    String owner = member.isObject() && member.path("name").isTextual() ? ownerOf(member.get("name").asText()) : "";
                    walk(member, owner, expanded, "/" + entry.getKey() + "/" + i, value, i, null);
                }
            }
        }
        resolved.remove("functions");
    }

    private static List<Map.Entry<String, JsonNode>> fieldsOf(ObjectNode node) {
        List<Map.Entry<String, JsonNode>> out = new ArrayList<>();
        node.fields().forEachRemaining(out::add);
        return out;
    }

    private static void readFunction(JsonNode node, String path, Map<String, UserFunction> table,
                                     Map<String, List<String>> paramTypes, Map<String, String> returnTypes,
                                     Map<String, String> paths) {
        if (!node.isObject()) {
            throw new Problem(path, "a function must be an object {name, params, returns, body}");
        }
        String name = text(node, "name");
        if (name == null) {
            throw new Problem(path + "/name", "a function needs a name");
        }
        String local = name.contains("::") ? name.substring(name.indexOf("::") + 2) : name;
        if (!LOCAL_NAME.matcher(local).matches()) {
            throw new Problem(path + "/name", "function name '" + local + "' must start with a lower-case letter and use letters, digits and _ only");
        }
        if (StdlibRegistry.find(local).isPresent()) {
            throw new Problem(path + "/name", "function '" + local + "' would shadow the built-in function of the same name; pick another name");
        }
        if (table.containsKey(name)) {
            throw new Problem(path + "/name", "function '" + name + "' is declared twice (first at " + paths.get(name) + ")");
        }
        List<String> params = new ArrayList<>();
        List<String> types = new ArrayList<>();
        JsonNode paramNodes = node.get("params");
        if (paramNodes != null) {
            if (!paramNodes.isArray()) {
                throw new Problem(path + "/params", "params must be an array of {name, type}");
            }
            for (int p = 0; p < paramNodes.size(); p++) {
                JsonNode param = paramNodes.get(p);
                String paramName = text(param, "name");
                String paramType = text(param, "type");
                if (paramName == null || !PARAM_NAME.matcher(paramName).matches()) {
                    throw new Problem(path + "/params/" + p + "/name", "a parameter needs a name that starts with a lower-case letter");
                }
                if (params.contains(paramName)) {
                    throw new Problem(path + "/params/" + p + "/name", "parameter '" + paramName + "' is declared twice");
                }
                if (paramType == null || !TYPES.contains(paramType)) {
                    throw new Problem(path + "/params/" + p + "/type", "parameter type must be one of " + new java.util.TreeSet<>(TYPES));
                }
                params.add(paramName);
                types.add(paramType);
            }
        }
        String returns = text(node, "returns");
        if (returns == null || !TYPES.contains(returns)) {
            throw new Problem(path + "/returns", "returns must be one of " + new java.util.TreeSet<>(TYPES));
        }
        String body = text(node, "body");
        if (body == null) {
            throw new Problem(path + "/body", "a function needs a body expression");
        }
        try {
            ComputedExpression.parse(body);
        } catch (ExpressionException e) {
            throw new Problem(path + "/body", "body does not parse: " + e.getMessage());
        }
        table.put(name, new UserFunction(name, List.copyOf(params), body));
        paramTypes.put(name, types);
        returnTypes.put(name, returns);
        paths.put(name, path);
    }

    private static void checkBody(UserFunction function, String body, List<String> types, String returns, String path) {
        for (String reference : ComputedExpression.referencedFields(body)) {
            if (reference.startsWith("$")) {
                continue;
            }
            String root = reference.contains(".") ? reference.substring(0, reference.indexOf('.')) : reference;
            if (!function.params().contains(root)) {
                throw new Problem(path + "/body", "function '" + function.name() + "' reads '" + reference
                        + "', which is not one of its parameters (" + String.join(", ", function.params())
                        + "); pass the value in as a parameter so the function does not depend on where it is called");
            }
        }
        Function<String, ExprType> scope = reference -> {
            if (reference.contains(".")) {
                return ExprType.ANY;
            }
            int index = function.params().indexOf(reference);
            return index < 0 ? null : ExprType.fromDslType(types.get(index));
        };
        ExpressionTypeChecker.Expected expected = switch (returns) {
            case "integer", "decimal" -> ExpressionTypeChecker.Expected.NUMBER;
            case "boolean" -> ExpressionTypeChecker.Expected.BOOLEAN;
            default -> ExpressionTypeChecker.Expected.ANY;
        };
        List<String> problems = ExpressionTypeChecker.check(body, scope, expected, false);
        if (!problems.isEmpty()) {
            throw new Problem(path + "/body", "function '" + function.name() + "' returns " + returns + " but " + problems.get(0));
        }
    }

    private static void walk(JsonNode node, String owner, Map<String, UserFunction> table, String path,
                             JsonNode parent, int index, String field) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : fieldsOf((ObjectNode) node)) {
                if (PROSE_KEYS.contains(entry.getKey())) {
                    continue;
                }
                walk(entry.getValue(), owner, table, path + "/" + entry.getKey(), node, -1, entry.getKey());
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                walk(node.get(i), owner, table, path + "/" + i, node, i, null);
            }
        } else if (node.isTextual()) {
            String rewritten = rewrite(node.asText(), owner, table, path);
            if (rewritten != null) {
                if (index >= 0) {
                    ((ArrayNode) parent).set(index, TextNode.valueOf(rewritten));
                } else {
                    ((ObjectNode) parent).put(field, rewritten);
                }
            }
        }
    }

    /** The rewritten text, or null when the string calls no user function (or is not an expression at all). */
    private static String rewrite(String text, String owner, Map<String, UserFunction> table, String path) {
        if (text.indexOf('(') < 0 || !mentionsAFunction(text, table)) {
            return null;
        }
        try {
            ComputedExpression.parse(text);
        } catch (ExpressionException notAnExpression) {
            return null;
        }
        try {
            String inlined = ComputedExpression.inlineFunctions(text, lookup(owner, table));
            return inlined.equals(text) ? null : inlined;
        } catch (ExpressionException e) {
            throw new Problem(path, e.getMessage());
        }
    }

    private static boolean mentionsAFunction(String text, Map<String, UserFunction> table) {
        for (String name : table.keySet()) {
            String local = name.contains("::") ? name.substring(name.indexOf("::") + 2) : name;
            if (text.contains(name + "(") || text.contains(local + "(")) {
                return true;
            }
        }
        return false;
    }

    /** What {@code owner}'s expressions mean by a name: its own function first, then a root or fully qualified one. */
    private static Function<String, UserFunction> lookup(String owner, Map<String, UserFunction> table) {
        return name -> {
            if (!name.contains("::") && !owner.isEmpty()) {
                UserFunction own = table.get(owner + "::" + name);
                if (own != null) {
                    return own;
                }
            }
            return table.get(name);
        };
    }

    private static String ownerOf(String qualifiedName) {
        int separator = qualifiedName.indexOf("::");
        return separator <= 0 ? "" : qualifiedName.substring(0, separator);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }
}
