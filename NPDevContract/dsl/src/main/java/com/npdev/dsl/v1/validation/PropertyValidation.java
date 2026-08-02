package com.npdev.dsl.v1.validation;

import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.ast.PropertyAst;
import com.npdev.dsl.v1.ast.PropertyScopeAst;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static com.npdev.dsl.v1.validation.SemanticValidator.hasText;
import static com.npdev.dsl.v1.validation.SemanticValidator.normalize;

/**
 * MASTER_AI_PLATFORM_PROGRAMME_v2.md Wave 6 (RC-A1, {@code MOVE11_RUNTIME_CONFIGURATION_PLAN}
 * Part A.1): compile-time checks for the optional top-level {@code propertyScopes}/{@code properties}
 * declarations -- the scoped-property cascade's declaration layer. Three checks this item's own
 * DoD names explicitly, all refusing rather than silently accepting a shape the runtime (A3's
 * resolver) could not honor:
 * <ol>
 *   <li>a property's {@code settableAt} names a scope not declared in {@code propertyScopes};</li>
 *   <li>a property's {@code default} does not match its own declared {@code type};</li>
 *   <li>a scope's {@code from} does not match the closed grammar {@code ExecutionContext} can
 *       actually supply ({@code $ctx.tenantId}, {@code $user.id}, or {@code $user.<tagName>}).</li>
 * </ol>
 */
final class PropertyValidation {

    private PropertyValidation() {
    }

    private static final Set<String> PROPERTY_TYPES = Set.of("string", "int", "boolean", "enum", "date");

    /** {@code $ctx.tenantId}, {@code $user.id}, or {@code $user.<tagName>} -- see {@link
     *  PropertyScopeAst}'s javadoc for why these three are the only forms {@code ExecutionContext}
     *  can actually supply without a per-read database lookup. */
    private static final Pattern FROM_GRAMMAR =
            Pattern.compile("^\\$(ctx\\.tenantId|user\\.(id|[A-Za-z_][A-Za-z0-9_]*))$");

    static void validatePropertyScopesAndProperties(ModelAst modelAst, List<String> errors) {
        Set<String> scopeNames = new HashSet<>();
        List<PropertyScopeAst> scopes = modelAst.getPropertyScopes();
        for (int i = 0; i < scopes.size(); i++) {
            PropertyScopeAst scope = scopes.get(i);
            if (!hasText(scope.name())) {
                errors.add("propertyScopes: name is required");
                continue;
            }
            String here = "propertyScopes[" + scope.name() + "]";
            if (!scopeNames.add(normalize(scope.name()))) {
                errors.add(here + ": duplicate scope name");
            }
            if (scope.from() != null && !FROM_GRAMMAR.matcher(scope.from()).matches()) {
                errors.add(here + ": from '" + scope.from() + "' is not a form ExecutionContext can supply "
                        + "-- must be '$ctx.tenantId', '$user.id', or '$user.<tagName>' "
                        + "(a per-read database lookup is not allowed here; see PropertyScopeAst)");
            }
            // REG-116: propertyScopes' declared ORDER is the resolver's actual cascade precedence
            // (PropertyResolver walks the array as given, most specific first) -- nothing else
            // signals which entry is "more specific" than another, so a wrongly-ordered array
            // compiles clean and silently inverts precedence at runtime, with no error anywhere. The
            // one mechanically-checkable rule: the implicit root scope (no "from", always
            // $ctx.tenantId -- see this class's own field/PropertyScopeAst javadoc) is BY DEFINITION
            // the least specific, so it must be declared last if present at all.
            if (scope.from() == null && i != scopes.size() - 1) {
                errors.add(here + ": the implicit root scope (no 'from', resolves to $ctx.tenantId) "
                        + "is the LEAST specific level and must be declared LAST in propertyScopes -- "
                        + "found at position " + (i + 1) + " of " + scopes.size()
                        + ". Declared order IS resolution order (most specific first); a scope listed "
                        + "before this one would be silently treated as MORE specific than it actually is.");
            }
        }

        Set<String> propertyNames = new HashSet<>();
        for (PropertyAst property : modelAst.getProperties()) {
            if (!hasText(property.name())) {
                errors.add("properties: name is required");
                continue;
            }
            String here = "properties[" + property.name() + "]";
            if (!propertyNames.add(normalize(property.name()))) {
                errors.add(here + ": duplicate property name");
            }

            String type = normalize(property.type());
            if (!PROPERTY_TYPES.contains(type)) {
                errors.add(here + ": type '" + property.type() + "' is not one of the declared closed set "
                        + new TreeSet<>(PROPERTY_TYPES) + " -- suggestedFix: declare one of these types");
                continue;
            }
            if (!matchesDeclaredType(type, property.defaultValue())) {
                errors.add(here + ": default " + describeValue(property.defaultValue())
                        + " does not match declared type '" + type + "'");
            }

            for (String settable : property.settableAt()) {
                if (!hasText(settable)) {
                    errors.add(here + ": settableAt entry is blank");
                    continue;
                }
                if (!scopeNames.contains(normalize(settable))) {
                    errors.add(here + ": settableAt names undeclared scope '" + settable
                            + "' -- suggestedFix: declare it in propertyScopes, or choose one of "
                            + (scopeNames.isEmpty() ? "none declared" : new TreeSet<>(scopeNames)));
                }
            }
        }
    }

    private static boolean matchesDeclaredType(String type, Object defaultValue) {
        if (defaultValue == null) {
            // A property with no default is not this move's shape (A1's example always declares one),
            // but null is not itself a type mismatch -- absent is a separate, unaddressed question.
            return true;
        }
        return switch (type) {
            case "string", "enum", "date" -> defaultValue instanceof String;
            case "int" -> defaultValue instanceof Long || defaultValue instanceof Integer;
            case "boolean" -> defaultValue instanceof Boolean;
            default -> false;
        };
    }

    private static String describeValue(Object value) {
        if (value == null) {
            return "null";
        }
        return "'" + value + "' (" + value.getClass().getSimpleName() + ")";
    }
}
