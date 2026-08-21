package com.npdev.dsl.v1.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PACK-9: rewrites {@code role('logicalName')} tokens found in a pack's own contributed JSON, at
 * pack-composition time, into the literal concrete role name the composing app bound via its root
 * {@code provides.roleBindings} map. This is the "internal rewrite" half of PACK-9 -- the presence-
 * only {@code requires.roles}/{@code provides.roles} check ({@link ModelSourceResolver#checkPackRequirements})
 * only ever proved a name was PRESENT, never let a pack's own internal role checks consume WHATEVER
 * concrete name the app actually chose (see that method's own doc, and {@code ledger/items/PACK-9.yml}).
 *
 * <p>The token is expanded HERE, before {@code JsonModelParser}/{@code ModelCompiler}/the generator/
 * the kernel ever see the string -- by the time any of those run, the value is an ordinary literal,
 * exactly as if the pack author had hand-written the app's own concrete role name. This is why the
 * mechanism is a composer rewrite, not a new runtime CEL function: nothing downstream needs to know
 * {@code role(...)} syntax ever existed, so this does not wait on any runtime-evaluator work (unlike
 * a real {@code role()} function would).
 *
 * <p>An unbound logical name is refused HERE, at composition, naming both the pack and the key --
 * never silently merged through to become a runtime authorization surprise (the PACK-9 done-when's
 * own non-negotiable: an unbound role must fail at composition, never reach a running app).
 *
 * <p>Deliberately field-name-scoped, not a blanket string scan across the whole pack: only the exact
 * JSON keys the 2026-08-15 PACK-9 scoping pass identified as real role-check sites are walked --
 * STRUCTURED (a single role name IS the whole value, or the value after the existing {@code "role:"}
 * prefix convention): {@code visibility}, {@code permissionHint}, each entry of {@code
 * permissionRequirements}; EXPRESSION-EMBEDDED (a free-form CEL-ish predicate that may reference a
 * role check anywhere inside a larger expression): {@code read}/{@code write} (a concept's {@code
 * access} object), {@code visibleWhen}, {@code enabledWhen}, {@code readonlyWhen}. A coincidental
 * {@code "role(...)"} substring inside an unrelated label or description is left untouched, because
 * this walker never looks at those keys in the first place.
 *
 * <p>Structured fields substitute the bound name UNQUOTED, preserving the existing {@code "role:X"}
 * / bare-role-name conventions those fields already use (e.g. {@code visibility: "role:role('curator')"}
 * becomes {@code "role:WidgetLabelCurator"}, exactly the shape a hand-authored literal already had).
 * Expression fields substitute a single-quoted string literal so the surrounding expression stays a
 * syntactically valid string constant (e.g. {@code $user.roles.contains(role('curator'))} becomes
 * {@code $user.roles.contains('WidgetLabelCurator')}).
 */
final class PackRoleBindingRewriter {

    private static final Pattern ROLE_TOKEN =
            Pattern.compile("role\\(\\s*['\"]([A-Za-z][A-Za-z0-9_]*)['\"]\\s*\\)");

    /** Whole-string-or-embedded, substituted UNQUOTED. */
    private static final Set<String> UNQUOTED_STRING_FIELDS = Set.of("visibility", "permissionHint");
    /** An array of plain role-name strings, each entry substituted UNQUOTED. */
    private static final String UNQUOTED_ARRAY_FIELD = "permissionRequirements";
    /** Free-form expression strings; the token is substituted as a QUOTED string literal. */
    private static final Set<String> QUOTED_STRING_FIELDS =
            Set.of("read", "write", "visibleWhen", "enabledWhen", "readonlyWhen");

    private PackRoleBindingRewriter() {
    }

    /**
     * @param path the path that reached this pack ({@code ["app", packId]} for a direct import, or
     *             longer for a transitive dependency) -- named in the refusal message exactly like
     *             {@link ModelSourceResolver.PackRequirementEntry} already does for the presence check.
     */
    static ObjectNode rewrite(
            String packId,
            List<String> path,
            ObjectNode packNode,
            JsonNode provides,
            Path packFile
    ) throws IOException {
        if (!containsRoleToken(packNode)) {
            return packNode;
        }
        ObjectNode copy = packNode.deepCopy();
        rewriteNode(copy, packId, path, provides, packFile);
        return copy;
    }

    private static boolean containsRoleToken(JsonNode node) {
        if (node.isTextual()) {
            return ROLE_TOKEN.matcher(node.asText()).find();
        }
        if (node.isArray() || node.isObject()) {
            for (JsonNode child : node) {
                if (containsRoleToken(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void rewriteNode(
            JsonNode node,
            String packId,
            List<String> path,
            JsonNode provides,
            Path packFile
    ) throws IOException {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);
            for (String field : fieldNames) {
                JsonNode value = obj.get(field);
                if (value.isTextual() && UNQUOTED_STRING_FIELDS.contains(field)) {
                    obj.put(field, substitute(value.asText(), false, packId, path, provides, packFile));
                } else if (value.isArray() && UNQUOTED_ARRAY_FIELD.equals(field)) {
                    ArrayNode array = (ArrayNode) value;
                    for (int i = 0; i < array.size(); i++) {
                        JsonNode item = array.get(i);
                        if (item.isTextual()) {
                            array.set(i, TextNode.valueOf(
                                    substitute(item.asText(), false, packId, path, provides, packFile)));
                        }
                    }
                } else if (value.isTextual() && QUOTED_STRING_FIELDS.contains(field)) {
                    obj.put(field, substitute(value.asText(), true, packId, path, provides, packFile));
                } else {
                    rewriteNode(value, packId, path, provides, packFile);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                rewriteNode(child, packId, path, provides, packFile);
            }
        }
    }

    private static String substitute(
            String value,
            boolean quoted,
            String packId,
            List<String> path,
            JsonNode provides,
            Path packFile
    ) throws IOException {
        Matcher matcher = ROLE_TOKEN.matcher(value);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            String logicalName = matcher.group(1);
            String bound = boundRoleFor(logicalName, packId, path, provides, packFile);
            out.append(value, last, matcher.start());
            out.append(quoted ? "'" + bound + "'" : bound);
            last = matcher.end();
        }
        out.append(value.substring(last));
        return out.toString();
    }

    private static String boundRoleFor(
            String logicalName,
            String packId,
            List<String> path,
            JsonNode provides,
            Path packFile
    ) throws IOException {
        JsonNode roleBindings = provides == null ? null : provides.get("roleBindings");
        JsonNode bound = roleBindings == null ? null : roleBindings.get(logicalName);
        if (bound == null || !bound.isTextual() || bound.asText().isBlank()) {
            throw ModelSourceResolver.error(packFile, "/packs", "pack '" + packId + "' (via "
                    + String.join(" -> ", path) + ") references role('" + logicalName + "'), which the app "
                    + "does not declare in provides.roleBindings.'" + logicalName + "' -- composition refuses "
                    + "(an unbound role must fail at composition, never silently reach runtime)");
        }
        return bound.asText().trim();
    }
}
