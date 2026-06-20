package com.npdev.kernel.concepts;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConfiguredConceptGatewaySemanticPolicy implements ConceptGatewaySemanticPolicy {
    private final Map<String, ConceptDefinition> conceptsByName;

    public ConfiguredConceptGatewaySemanticPolicy(List<ConceptDefinition> concepts) {
        Map<String, ConceptDefinition> byName = new LinkedHashMap<>();
        for (ConceptDefinition concept : concepts == null ? List.<ConceptDefinition>of() : concepts) {
            byName.put(normalizeKey(concept.name()), concept);
        }
        this.conceptsByName = Map.copyOf(byName);
    }

    public static ConfiguredConceptGatewaySemanticPolicy empty() {
        return new ConfiguredConceptGatewaySemanticPolicy(List.of());
    }

    @Override
    public ConceptSemanticDecision normalizeAndValidate(ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null) {
            return ConceptSemanticDecision.allow(request.data());
        }

        Map<String, Object> data = new LinkedHashMap<>(request.data());
        for (FieldDefinition field : concept.fields().values()) {
            Object value = data.get(field.name());
            if (field.required() && isBlankValue(value) && !canApplyDefault(concept, field)) {
                return ConceptSemanticDecision.deny(
                        "CONCEPT_FIELD_REQUIRED",
                        "Required concept field is missing: " + request.conceptName() + "." + field.name(),
                        Map.of("concept", request.conceptName(), "field", field.name())
                );
            }
            if (!field.enumValues().isEmpty() && !isBlankValue(value)
                    && field.enumValues().stream().noneMatch(item -> item.equals(String.valueOf(value)))) {
                return ConceptSemanticDecision.deny(
                        "CONCEPT_ENUM_INVALID",
                        "Concept field value is outside the declared enum: " + request.conceptName() + "." + field.name(),
                        Map.of("concept", request.conceptName(), "field", field.name(), "value", String.valueOf(value))
                );
            }
        }
        return ConceptSemanticDecision.allow(data);
    }

    @Override
    public ConceptSemanticDecision applyDefaultsAndDerivedValues(ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null) {
            return ConceptSemanticDecision.allow(request.data());
        }

        Map<String, Object> data = new LinkedHashMap<>(request.data());
        List<String> defaults = new ArrayList<>();
        List<String> derived = new ArrayList<>();
        for (FieldDefinition field : concept.fields().values()) {
            if (isBlankValue(data.get(field.name()))) {
                Object defaultValue = field.defaultValue();
                if (defaultValue == null && hasText(field.defaultExpression())) {
                    defaultValue = evaluateValueExpression(field.defaultExpression(), data);
                }
                if (defaultValue != null) {
                    data.put(field.name(), defaultValue);
                    defaults.add(field.name());
                }
            }
            if (hasText(field.derivedExpression())) {
                Object derivedValue = evaluateValueExpression(field.derivedExpression(), data);
                if (derivedValue != null) {
                    data.put(field.name(), derivedValue);
                    derived.add(field.name());
                }
            }
        }
        return new ConceptSemanticDecision(
                true,
                "allowed",
                "allowed",
                data,
                List.of(),
                defaults,
                derived,
                null,
                Map.of()
        );
    }

    @Override
    public ConceptSemanticDecision validateLifecycleTransition(ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null || concept.lifecycle() == null || !hasText(concept.lifecycle().statusField())) {
            return ConceptSemanticDecision.allow(request.data());
        }

        LifecycleDefinition lifecycle = concept.lifecycle();
        Map<String, Object> data = new LinkedHashMap<>(request.data());
        String statusField = lifecycle.statusField();
        String previous = request.previousRecord()
                .map(record -> stringValue(record.data().get(statusField)))
                .filter(ConfiguredConceptGatewaySemanticPolicy::hasText)
                .orElse(null);
        String next = stringValue(data.get(statusField));

        List<String> defaults = new ArrayList<>();
        if (!hasText(next) && previous == null && hasText(lifecycle.initialState())) {
            next = lifecycle.initialState();
            data.put(statusField, next);
            defaults.add(statusField);
        }

        if (!hasText(next) || Objects.equals(previous, next)) {
            return new ConceptSemanticDecision(
                    true,
                    "allowed",
                    "allowed",
                    data,
                    List.of(),
                    defaults,
                    List.of(),
                    previous == null && hasText(next) ? "null->" + next : null,
                    Map.of()
            );
        }

        if (!lifecycle.states().isEmpty() && !lifecycle.states().contains(next)) {
            return ConceptSemanticDecision.deny(
                    "CONCEPT_LIFECYCLE_STATE_INVALID",
                    "Concept lifecycle target state is not declared: " + next,
                    Map.of("concept", request.conceptName(), "statusField", statusField, "state", next)
            );
        }

        if (previous != null && !lifecycle.transitions().contains(new StateTransition(previous, next))) {
            return ConceptSemanticDecision.deny(
                    "CONCEPT_LIFECYCLE_TRANSITION_INVALID",
                    "Concept lifecycle transition is not allowed: " + previous + " -> " + next,
                    Map.of("concept", request.conceptName(), "statusField", statusField, "from", previous, "to", next)
            );
        }

        return new ConceptSemanticDecision(
                true,
                "allowed",
                "allowed",
                data,
                List.of(),
                defaults,
                List.of(),
                (previous == null ? "null" : previous) + "->" + next,
                Map.of()
        );
    }

    @Override
    public ConceptSemanticDecision evaluateRuleProfiles(
            ConceptGatewayRequestContext request,
            List<ConceptRuleProfile> ruleProfiles
    ) {
        ConceptDefinition concept = concept(request);
        if (concept == null || concept.invariants().isEmpty()) {
            return allowWithSemanticDetails(request, ruleProfiles, List.of(), "noRulesConfigured");
        }
        if (request.operation() == ConceptGatewayOperation.READ || request.operation() == ConceptGatewayOperation.LIST) {
            List<Map<String, Object>> rules = new ArrayList<>();
            for (InvariantDefinition invariant : concept.invariants()) {
                rules.add(ruleDetail(invariant, "notAppliedToQueryOperation", true));
            }
            return allowWithSemanticDetails(request, ruleProfiles, rules, "queryRestrictionsExplicit");
        }

        Map<String, Object> facts = new LinkedHashMap<>(request.data());
        facts.put("operation", request.operation().name().toLowerCase(Locale.ROOT));
        facts.put("concept", request.conceptName());
        facts.put("tenantId", request.tenantId());
        List<Map<String, Object>> rulesEvaluated = new ArrayList<>();
        for (InvariantDefinition invariant : concept.invariants()) {
            String expression = invariant.expression();
            if (!isSupportedBooleanExpression(expression)) {
                // This policy only understands a small comparison/uniqueBy grammar; richer
                // invariants (e.g. conflict-detection functions like overlapsProvider(...))
                // are already fully validated by the kernel's CEL invariant engine before this
                // gateway-side check runs (see GeneratedCrudRuntimeSupport.enforceWithKernel),
                // so we skip rather than deny instead of double-rejecting on syntax we can't parse.
                rulesEvaluated.add(ruleDetail(invariant, "skippedUnsupportedExpression", true));
                continue;
            }
            boolean passed = evaluateBooleanExpression(expression, facts);
            rulesEvaluated.add(ruleDetail(invariant, passed ? "passed" : "failed", passed));
            if (!passed) {
                return ConceptSemanticDecision.deny(
                        "CONCEPT_INVARIANT_REJECTED",
                        "Concept invariant rejected operation: " + invariant.name(),
                        Map.of(
                                "concept", request.conceptName(),
                                "invariant", invariant.name(),
                                "expression", expression,
                                "operation", request.operation().name(),
                                "rulesEvaluated", rulesEvaluated
                        )
                ).withRuleProfiles(ruleProfiles);
            }
        }
        return allowWithSemanticDetails(request, ruleProfiles, rulesEvaluated, "allowed");
    }

    @Override
    public ConceptRecord filterVisibleFields(ConceptRecord record, ConceptGatewayRequestContext request) {
        ConceptDefinition concept = concept(request);
        if (concept == null || concept.hiddenFields().isEmpty()) {
            return record;
        }
        Map<String, Object> visible = new LinkedHashMap<>(record.data());
        for (String hiddenField : concept.hiddenFields()) {
            visible.remove(hiddenField);
        }
        return new ConceptRecord(record.conceptName(), record.id(), record.tenantId(), visible);
    }

    private static boolean canApplyDefault(ConceptDefinition concept, FieldDefinition field) {
        if (field.defaultValue() != null || hasText(field.defaultExpression())) {
            return true;
        }
        return concept.lifecycle() != null
                && field.name().equals(concept.lifecycle().statusField())
                && hasText(concept.lifecycle().initialState());
    }

    private ConceptDefinition concept(ConceptGatewayRequestContext request) {
        return conceptsByName.get(normalizeKey(request.conceptName()));
    }

    private static Object evaluateValueExpression(String expression, Map<String, Object> data) {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            return null;
        }
        if ("now()".equalsIgnoreCase(text)) {
            return Instant.EPOCH.toString();
        }
        if ("uuid()".equalsIgnoreCase(text)) {
            return UUID.nameUUIDFromBytes("npdev-deterministic-concept-default".getBytes()).toString();
        }
        if (text.startsWith("$")) {
            return data.get(text.substring(1));
        }
        if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1);
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        try {
            if (text.contains(".")) {
                return Double.parseDouble(text);
            }
            return Long.parseLong(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    private static final Pattern UNIQUE_BY_PATTERN =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\.uniqueBy\\(([A-Za-z_][A-Za-z0-9_]*)\\)$");

    private static boolean evaluateBooleanExpression(String expression, Map<String, Object> facts) {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            return true;
        }
        Matcher uniqueByMatcher = UNIQUE_BY_PATTERN.matcher(text);
        if (uniqueByMatcher.matches()) {
            return evaluateUniqueBy(uniqueByMatcher.group(1), uniqueByMatcher.group(2), facts);
        }
        for (String disjunct : text.split("\\s+\\|\\|\\s+")) {
            boolean all = true;
            for (String conjunct : disjunct.split("\\s+&&\\s+")) {
                all = all && evaluateComparison(conjunct.trim(), facts);
            }
            if (all) {
                return true;
            }
        }
        return false;
    }

    private static boolean evaluateUniqueBy(String fieldName, String subfield, Map<String, Object> facts) {
        Object value = facts.get(fieldName);
        if (!(value instanceof List<?> list)) {
            return true;
        }
        Set<Object> seen = new LinkedHashSet<>();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> elementMap)) {
                continue;
            }
            Object subValue = elementMap.get(subfield);
            if (subValue == null) {
                continue;
            }
            if (!seen.add(normalizeComparable(subValue))) {
                return false;
            }
        }
        return true;
    }

    private static boolean evaluateComparison(String expression, Map<String, Object> facts) {
        Matcher matcher = Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$").matcher(expression);
        if (!matcher.matches()) {
            return false;
        }
        Object left = facts.get(matcher.group(1));
        String operator = matcher.group(2);
        Object right = literal(matcher.group(3), facts);
        int comparison = compare(left, right);
        return switch (operator) {
            case "==" -> Objects.equals(normalizeComparable(left), normalizeComparable(right));
            case "!=" -> !Objects.equals(normalizeComparable(left), normalizeComparable(right));
            case ">=" -> comparison >= 0;
            case "<=" -> comparison <= 0;
            case ">" -> comparison > 0;
            case "<" -> comparison < 0;
            default -> false;
        };
    }

    private static boolean isSupportedBooleanExpression(String expression) {
        String text = expression == null ? "" : expression.trim();
        if (text.isEmpty()) {
            return true;
        }
        if (UNIQUE_BY_PATTERN.matcher(text).matches()) {
            return true;
        }
        for (String disjunct : text.split("\\s+\\|\\|\\s+")) {
            for (String conjunct : disjunct.split("\\s+&&\\s+")) {
                String item = conjunct.trim();
                if (item.isEmpty()) {
                    continue;
                }
                if (!Pattern.compile("^([A-Za-z_][A-Za-z0-9_.-]*)\\s*(==|!=|>=|<=|>|<)\\s*(.+)$").matcher(item).matches()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ConceptSemanticDecision allowWithSemanticDetails(
            ConceptGatewayRequestContext request,
            List<ConceptRuleProfile> ruleProfiles,
            List<Map<String, Object>> rulesEvaluated,
            String decisionOutcome
    ) {
        return new ConceptSemanticDecision(
                true,
                "allowed",
                "allowed",
                request.data(),
                List.of(),
                List.of(),
                List.of(),
                null,
                Map.of(
                        "operation", request.operation().name(),
                        "concept", request.conceptName(),
                        "decisionOutcome", decisionOutcome,
                        "rulesEvaluated", rulesEvaluated
                )
        ).withRuleProfiles(ruleProfiles);
    }

    private static Map<String, Object> ruleDetail(InvariantDefinition invariant, String outcome, boolean allowed) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("rule", invariant.name());
        detail.put("expression", invariant.expression());
        detail.put("outcome", outcome);
        detail.put("allowed", allowed);
        return Map.copyOf(detail);
    }

    private static Object literal(String raw, Map<String, Object> facts) {
        String text = raw == null ? "" : raw.trim();
        if ("null".equalsIgnoreCase(text)) {
            return null;
        }
        if ((text.startsWith("'") && text.endsWith("'")) || (text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1);
        }
        if (facts.containsKey(text)) {
            return facts.get(text);
        }
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    private static int compare(Object left, Object right) {
        Object normalizedLeft = normalizeComparable(left);
        Object normalizedRight = normalizeComparable(right);
        if (normalizedLeft instanceof Number leftNumber && normalizedRight instanceof Number rightNumber) {
            return Double.compare(leftNumber.doubleValue(), rightNumber.doubleValue());
        }
        return String.valueOf(normalizedLeft).compareTo(String.valueOf(normalizedRight));
    }

    private static Object normalizeComparable(Object value) {
        if (value instanceof Number) {
            return value;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            return text;
        }
    }

    private static boolean isBlankValue(Object value) {
        return value == null || (value instanceof CharSequence text && text.toString().trim().isEmpty());
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public record ConceptDefinition(
            String name,
            Map<String, FieldDefinition> fields,
            List<InvariantDefinition> invariants,
            LifecycleDefinition lifecycle,
            Set<String> hiddenFields
    ) {
        public ConceptDefinition {
            name = Objects.requireNonNull(name, "name");
            fields = fields == null ? Map.of() : Map.copyOf(fields);
            invariants = invariants == null ? List.of() : List.copyOf(invariants);
            hiddenFields = hiddenFields == null ? Set.of() : Set.copyOf(hiddenFields);
        }

        public static ConceptDefinition of(
                String name,
                List<FieldDefinition> fields,
                List<InvariantDefinition> invariants,
                LifecycleDefinition lifecycle
        ) {
            Map<String, FieldDefinition> byName = new LinkedHashMap<>();
            for (FieldDefinition field : fields == null ? List.<FieldDefinition>of() : fields) {
                byName.put(field.name(), field);
            }
            Set<String> hiddenFields = new LinkedHashSet<>();
            for (FieldDefinition field : fields == null ? List.<FieldDefinition>of() : fields) {
                if (field.hidden()) {
                    hiddenFields.add(field.name());
                }
            }
            return new ConceptDefinition(name, byName, invariants, lifecycle, hiddenFields);
        }
    }

    public record FieldDefinition(
            String name,
            boolean required,
            List<String> enumValues,
            Object defaultValue,
            String defaultExpression,
            String derivedExpression,
            boolean hidden
    ) {
        public FieldDefinition(
                String name,
                boolean required,
                List<String> enumValues,
                Object defaultValue,
                String defaultExpression,
                String derivedExpression
        ) {
            this(name, required, enumValues, defaultValue, defaultExpression, derivedExpression, false);
        }

        public FieldDefinition {
            name = Objects.requireNonNull(name, "name");
            enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        }
    }

    public record InvariantDefinition(String name, String expression) {
        public InvariantDefinition {
            name = hasText(name) ? name.trim() : "anonymousInvariant";
            expression = expression == null ? "" : expression.trim();
        }
    }

    public record LifecycleDefinition(
            String statusField,
            String initialState,
            Set<String> states,
            Set<StateTransition> transitions
    ) {
        public LifecycleDefinition {
            states = states == null ? Set.of() : Set.copyOf(states);
            transitions = transitions == null ? Set.of() : Set.copyOf(transitions);
        }

        public static LifecycleDefinition of(
                String statusField,
                String initialState,
                List<String> states,
                List<StateTransition> transitions
        ) {
            return new LifecycleDefinition(
                    statusField,
                    initialState,
                    states == null ? Set.of() : new LinkedHashSet<>(states),
                    transitions == null ? Set.of() : new LinkedHashSet<>(transitions)
            );
        }
    }

    public record StateTransition(String from, String to) {
        public StateTransition {
            from = from == null ? "" : from.trim();
            to = to == null ? "" : to.trim();
        }
    }
}
