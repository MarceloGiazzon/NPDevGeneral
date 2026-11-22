package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P8 (owner D-4, 2026-11-21): the compact form of a simple flow step. A step that is just a verb and its operands is
 * written as one string instead of an object:
 * <pre>
 *   "return $final"                          {"type":"return","value":"$final"}
 *   "set riderId = $me.id"                   {"type":"set","value":"$me.id","output":"riderId"}
 *   "read Ride $input.id as ride"            {"type":"readConcept","scope":"Ride","id":"$input.id","output":"ride"}
 *   "create Ride from $rideIn as created"    {"type":"createConcept","scope":"Ride","input":"$rideIn","output":"created"}
 *   "call MeasureTrip with $created as m"    {"type":"callFlow","flow":"MeasureTrip","input":"$created","output":"m"}
 *   "emit RideDone from $saved"              {"type":"emitEvent","event":"RideDone","from":"$saved"}
 *   "cap geo.closest($a, $b) as best"        {"type":"capabilityCall","capability":"geo","operation":"closest","args":["$a","$b"],"output":"best"}
 * </pre>
 * It is pure sugar, lowered here to the object a person could have written, so the AST, the compiled model, the
 * canonical JSON and the kernel never see it (like the {@code notify} step). It adds no keyword to the schema: a step
 * may be a string. Anything with more keys (policy, errorTarget, match, set maps...) stays an object, and a string
 * that fits no form is refused with the list of forms.
 */
public final class CompactSteps {

    private static final String FORMS = "return <value> | set <out> = <value> | read <Scope> <id> [as <out>] | "
            + "create <Scope> from <input> [as <out>] | call <Flow> [with <input>] [as <out>] | "
            + "emit <Event> [from <ref>] | cap <capability>.<operation>(<args>) [as <out>]";

    private static final Pattern RETURN = Pattern.compile("^return\\s+(.+)$", Pattern.DOTALL);
    private static final Pattern SET = Pattern.compile("^set\\s+(\\S+)\\s*=\\s*(.+)$", Pattern.DOTALL);
    private static final Pattern READ = Pattern.compile("^read\\s+(\\S+)\\s+(\\S+)(?:\\s+as\\s+(\\S+))?$");
    private static final Pattern CREATE = Pattern.compile("^create\\s+(\\S+)\\s+from\\s+(\\S+)(?:\\s+as\\s+(\\S+))?$");
    private static final Pattern CALL = Pattern.compile("^call\\s+(\\S+)(?:\\s+with\\s+(\\S+))?(?:\\s+as\\s+(\\S+))?$");
    private static final Pattern EMIT = Pattern.compile("^emit\\s+(\\S+)(?:\\s+from\\s+(\\S+))?$");
    private static final Pattern CAP = Pattern.compile("^cap\\s+([\\w-]+)\\.([\\w-]+)\\((.*)\\)(?:\\s+as\\s+(\\S+))?$", Pattern.DOTALL);

    private CompactSteps() {
    }

    /** The step as an object: a string is lowered, an object is returned as it is. */
    public static JsonNode expandStep(JsonNode step, String where) throws IOException {
        if (step == null || !step.isTextual()) {
            return step;
        }
        return lower(step.asText().trim(), where);
    }

    /** A copy of a steps array with every compact string lowered (the original is untouched). */
    public static JsonNode expandList(JsonNode steps, String where) throws IOException {
        if (steps == null || !steps.isArray()) {
            return steps;
        }
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        int index = 0;
        for (JsonNode step : steps) {
            index++;
            out.add(expandStep(step, where + " step #" + index));
        }
        return out;
    }

    static ObjectNode lower(String text, String where) throws IOException {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        Matcher m;
        if ((m = RETURN.matcher(text)).matches()) {
            node.put("type", "return");
            node.put("value", m.group(1).trim());
        } else if ((m = SET.matcher(text)).matches()) {
            node.put("type", "set");
            node.put("value", m.group(2).trim());
            node.put("output", m.group(1));
        } else if ((m = READ.matcher(text)).matches()) {
            node.put("type", "readConcept");
            node.put("scope", m.group(1));
            node.put("id", m.group(2));
            putIf(node, "output", m.group(3));
        } else if ((m = CREATE.matcher(text)).matches()) {
            node.put("type", "createConcept");
            node.put("scope", m.group(1));
            node.put("input", m.group(2));
            putIf(node, "output", m.group(3));
        } else if ((m = CALL.matcher(text)).matches()) {
            node.put("type", "callFlow");
            node.put("flow", m.group(1));
            putIf(node, "input", m.group(2));
            putIf(node, "output", m.group(3));
        } else if ((m = EMIT.matcher(text)).matches()) {
            node.put("type", "emitEvent");
            node.put("event", m.group(1));
            putIf(node, "from", m.group(2));
        } else if ((m = CAP.matcher(text)).matches()) {
            node.put("type", "capabilityCall");
            node.put("capability", m.group(1));
            node.put("operation", m.group(2));
            ArrayNode args = node.putArray("args");
            for (String arg : splitArgs(m.group(3), where, text)) {
                args.add(arg);
            }
            putIf(node, "output", m.group(4));
        } else {
            throw new IOException(where + ": cannot read the compact step \"" + text + "\". The compact forms are: " + FORMS
                    + ". Any other step is written as an object.");
        }
        return node;
    }

    private static void putIf(ObjectNode node, String key, String value) {
        if (value != null && !value.isBlank()) {
            node.put(key, value);
        }
    }

    /** Splits {@code a, b, "c, d"} at top-level commas (not inside quotes, parentheses or brackets). */
    private static List<String> splitArgs(String raw, String where, String text) throws IOException {
        List<String> out = new ArrayList<>();
        if (raw.isBlank()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                out.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quote != 0 || depth != 0) {
            throw new IOException(where + ": the arguments of the compact step \"" + text + "\" have an unbalanced quote or bracket");
        }
        out.add(current.toString().trim());
        for (String arg : out) {
            if (arg.isEmpty()) {
                throw new IOException(where + ": the compact step \"" + text + "\" has an empty argument");
            }
        }
        return out;
    }
}
