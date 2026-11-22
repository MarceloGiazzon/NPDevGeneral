package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.validation.ValidationDiagnostic;
import com.npdev.dsl.v1.validation.ValidationLayer;
import com.npdev.dsl.v1.validation.ValidationSeverity;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Field naming conventions: a concept field that declares no {@code type} takes its type (and size, scale, ...)
 * from the first {@code fieldConventions[]} rule that matches its NAME, so {@code {"name": "birthDt"}} means a date.
 *
 * <p>A rule is {@code {"match": "name" | "*Name", "defaults": {"type": "string", "maxLength": 50}}}.
 * <ul>
 *   <li>{@code "name"} (no star) matches that exact field name only.</li>
 *   <li>{@code "*Name"} matches a field whose LAST camelCase word is {@code Name}: {@code clientName} and
 *       {@code shippingAddressName} match, {@code hasToRename} (last word {@code Rename}) and {@code name} do not.
 *       The suffix must start with an upper-case letter and the field must have at least one character before it.</li>
 *   <li>Several rules may match one name: an exact match beats a suffix match, a longer suffix beats a shorter one
 *       ({@code *DueDt} beats {@code *Dt}), and between equals the earlier rule wins. Order is the model's own rules,
 *       then the rules of imported packs in import order, then the built-in {@code conventions-basic} pack.</li>
 * </ul>
 *
 * <p>A rule may default {@code domainType} too ({@code "*Email"} -> {@code {"type": "string", "domainType": "Email"}}),
 * so one field ending brings type, size and the domain type's validation; the domain type itself is declared by the
 * model or a pack as usual, and a rule naming one that does not exist is the usual "domain type not found" error.
 *
 * <p>A convention only fills what the field leaves out: a field that declares its own {@code type} is never touched,
 * and an explicit {@code maxLength} on a convention-typed field wins over the rule's. The array is consumed here and
 * never reaches the parser, the compiler, the canonical JSON or the generator, so none of them needs to know about it.
 * A field with no type that no rule matches is an error, never a guess.
 */
final class FieldConventionExpansion {

    /** A rule or a field that cannot be resolved; {@code path} is a JSON pointer into the resolved model. */
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

    static final String KEY = "fieldConventions";
    private static final String BUILT_IN_RESOURCE = "/npdev/dsl/conventions-basic.json";
    private static final Pattern EXACT = Pattern.compile("^[a-z][A-Za-z0-9_]*$");
    private static final Pattern SUFFIX = Pattern.compile("^\\*[A-Z][A-Za-z0-9]*$");
    /** The field properties a rule may default. Identity and wiring (name, id, access, picker, ...) never default. */
    private static final Set<String> DEFAULTABLE = Set.of(
            "type", "maxLength", "minLength", "precision", "scale", "min", "max", "required", "unique", "sensitive",
            "domainType");

    private FieldConventionExpansion() {
    }

    /** {@code builtIn} marks a rule of the {@code conventions-basic} pack, so a note can say where the type came from. */
    private record Rule(String match, boolean exact, String suffix, ObjectNode defaults, boolean builtIn) {
        boolean matches(String fieldName) {
            if (exact) {
                return match.equals(fieldName);
            }
            return fieldName.length() > suffix.length() && fieldName.endsWith(suffix);
        }
    }

    /** Diagnostic code of the {@code info} note recorded for every field a convention typed. */
    static final String NOTE_CODE = "FIELD_CONVENTION_APPLIED";

    /**
     * Expands the conventions in {@code resolved}. Every field a convention typed adds one {@code info} diagnostic to
     * {@code notes} (never a warning: using a convention is not a mistake), so the validator can show the author what
     * the rule did instead of leaving it invisible.
     */
    static void apply(ObjectNode resolved, List<ValidationDiagnostic> notes) {
        JsonNode declared = resolved.get(KEY);
        List<Rule> rules = new ArrayList<>();
        if (declared != null) {
            if (!declared.isArray()) {
                throw new Problem("/" + KEY, KEY + " must be an array of {match, defaults} rules");
            }
            for (int i = 0; i < declared.size(); i++) {
                rules.add(readRule(declared.get(i), "/" + KEY + "/" + i, false));
            }
        }
        JsonNode concepts = resolved.get("concepts");
        if (concepts != null && concepts.isArray() && needsConvention(concepts)) {
            JsonNode builtIn = builtInRules();
            for (int i = 0; i < builtIn.size(); i++) {
                rules.add(readRule(builtIn.get(i), "conventions-basic/" + KEY + "/" + i, true));
            }
            for (int c = 0; c < concepts.size(); c++) {
                JsonNode fields = concepts.get(c).path("fields");
                for (int f = 0; f < fields.size(); f++) {
                    if (fields.get(f).isObject() && !fields.get(f).has("type")) {
                        expand((ObjectNode) fields.get(f), concepts.get(c).path("name").asText("?"),
                                "/concepts/" + c + "/fields/" + f, rules, notes);
                    }
                }
            }
        }
        resolved.remove(KEY);
    }

    private static boolean needsConvention(JsonNode concepts) {
        for (JsonNode concept : concepts) {
            for (JsonNode field : concept.path("fields")) {
                if (field.isObject() && !field.has("type")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void expand(ObjectNode field, String conceptName, String path, List<Rule> rules,
                               List<ValidationDiagnostic> notes) {
        String fieldName = field.path("name").asText("");
        Rule best = null;
        for (Rule rule : rules) {
            if (!rule.matches(fieldName)) {
                continue;
            }
            if (best == null || beats(rule, best)) {
                best = rule;
            }
        }
        if (best == null) {
            throw new Problem(path, "Field '" + fieldName + "' of concept '" + conceptName + "' declares no type, and "
                    + "no fieldConventions rule matches its name. Declare its type, or add a "
                    + "fieldConventions rule such as {\"match\": \"" + (fieldName.length() > 0 ? "*" + capitalized(lastWord(fieldName)) : "name")
                    + "\", \"defaults\": {\"type\": \"string\", \"maxLength\": 50}} (built-in rules: name, *Name, *Dt, *Dtt).");
        }
        List<String> applied = new ArrayList<>();
        best.defaults().fields().forEachRemaining(entry -> {
            if ("type".equals(entry.getKey()) || !field.has(entry.getKey())) {
                field.set(entry.getKey(), entry.getValue().deepCopy());
                applied.add(entry.getKey() + " " + entry.getValue().asText());
            }
        });
        if (notes != null) {
            String origin = best.builtIn() ? "the built-in convention" : "the model's convention";
            notes.add(new ValidationDiagnostic(
                    ValidationLayer.SEMANTIC,
                    ValidationSeverity.INFO,
                    NOTE_CODE,
                    "Field '" + fieldName + "' of concept '" + conceptName + "' declares no type; " + origin + " '"
                            + best.match() + "' typed it (" + String.join(", ", applied) + "). Write \"type\" on the "
                            + "field to override it.",
                    "NPDevContract",
                    path,
                    conceptName,
                    fieldName,
                    null,
                    null,
                    null,
                    null));
        }
    }

    /** True when {@code candidate} is strictly more specific than {@code current}; equals keep the earlier rule. */
    private static boolean beats(Rule candidate, Rule current) {
        if (candidate.exact() != current.exact()) {
            return candidate.exact();
        }
        return !candidate.exact() && candidate.suffix().length() > current.suffix().length();
    }

    private static Rule readRule(JsonNode node, String path, boolean builtIn) {
        if (!node.isObject()) {
            throw new Problem(path, "A fieldConventions rule must be an object {match, defaults}");
        }
        JsonNode matchNode = node.get("match");
        if (matchNode == null || !matchNode.isTextual()) {
            throw new Problem(path + "/match", "A fieldConventions rule needs a text 'match': an exact field name like \"name\" or a "
                    + "camelCase ending like \"*Name\"");
        }
        String match = matchNode.asText();
        boolean exact = EXACT.matcher(match).matches();
        if (!exact && !SUFFIX.matcher(match).matches()) {
            throw new Problem(path + "/match", "fieldConventions match '" + match + "' is neither an exact field name (\"name\") nor a "
                    + "camelCase ending that starts with an upper-case letter (\"*Name\")");
        }
        JsonNode defaultsNode = node.get("defaults");
        if (defaultsNode == null || !defaultsNode.isObject() || !defaultsNode.has("type")) {
            throw new Problem(path + "/defaults", "A fieldConventions rule needs defaults with at least a 'type'");
        }
        ObjectNode defaults = (ObjectNode) defaultsNode;
        defaults.fieldNames().forEachRemaining(key -> {
            if (!DEFAULTABLE.contains(key)) {
                throw new Problem(path + "/defaults/" + key, "fieldConventions defaults may only set " + DEFAULTABLE
                        + "; '" + key + "' is not one of them");
            }
        });
        return new Rule(match, exact, exact ? null : match.substring(1), defaults, builtIn);
    }

    private static JsonNode builtInRules() {
        try (InputStream in = FieldConventionExpansion.class.getResourceAsStream(BUILT_IN_RESOURCE)) {
            if (in == null) {
                throw new Problem("/" + KEY, "Built-in conventions-basic rules are missing from the DSL jar: " + BUILT_IN_RESOURCE);
            }
            return new ObjectMapper().readTree(in).path(KEY);
        } catch (IOException e) {
            throw new Problem("/" + KEY, "Built-in conventions-basic rules cannot be read: " + e.getMessage());
        }
    }

    private static String lastWord(String name) {
        int i = name.length() - 1;
        while (i > 0 && !Character.isUpperCase(name.charAt(i))) {
            i--;
        }
        return name.substring(i);
    }

    private static String capitalized(String word) {
        return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
