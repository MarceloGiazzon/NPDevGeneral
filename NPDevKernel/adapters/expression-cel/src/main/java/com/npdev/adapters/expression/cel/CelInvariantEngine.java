package com.npdev.adapters.expression.cel;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledInvariant;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.expr.ComputedExpression;
import com.npdev.kernel.ports.InvariantEngine;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Invariant engine adapter with a model-driven rule set.
 *
 * Supports:
 * - required(field): field must be present and non-blank (for String)
 * - unique(field): delegated to a caller-provided checker
 * - expression: comparisons, regex, uniqueBy, all, exists
 *
 * This remains "CEL adapter" namespace-wise, but provides practical runtime behavior.
 */
public final class CelInvariantEngine implements InvariantEngine {
    private static final String PATH_TOKEN =
            "([A-Za-z_][A-Za-z0-9_]*(?:\\[\\*\\])?(?:\\.[A-Za-z_][A-Za-z0-9_]*(?:\\[\\*\\])?)*)";
    private static final Pattern PATH_EXACT_PATTERN =
            Pattern.compile("^\\s*" + PATH_TOKEN + "\\s*$");
    private static final Pattern MATCHES_PATTERN =
            Pattern.compile("^\\s*" + PATH_TOKEN + "\\s*\\.matches\\s*\\((.+)\\)\\s*$");
    private static final Pattern COMPARISON_PATTERN =
            Pattern.compile("^\\s*" + PATH_TOKEN + "\\s*(==|!=|>=|<=|>|<)\\s*(.+)\\s*$");
    private static final Pattern UNIQUE_BY_PATTERN =
            Pattern.compile("^\\s*" + PATH_TOKEN + "\\s*\\.uniqueBy\\s*\\(\\s*" + PATH_TOKEN + "\\s*\\)\\s*$");
    private static final Pattern QUANTIFIED_PATTERN =
            Pattern.compile("^\\s*" + PATH_TOKEN
                    + "\\s*\\.(all|exists)\\s*\\(\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=>\\s*(.+)\\)\\s*$");

    @FunctionalInterface
    public interface UniqueValueChecker {
        /**
         * @return true when value already exists and violates uniqueness.
         */
        boolean exists(String entityName, String fieldName, Object value, Object payload);
    }

    /** LIFT-UNIQUE-P3: existence check for a compound (multi-field) unique invariant. */
    @FunctionalInterface
    public interface CompoundUniqueValueChecker {
        /**
         * @return true when a row already matches every (field, value) pair and violates uniqueness.
         */
        boolean exists(String entityName, List<String> fields, List<Object> values, Object payload);
    }

    @FunctionalInterface
    public interface ConflictChecker {
        boolean conflicts(
                String resourceField,
                Object resourceId,
                String startsAtField,
                Object startsAt,
                String durationField,
                Object durationMinutes,
                Object excludeId,
                Object payload
        );
    }

    @FunctionalInterface
    public interface ScopeChecker {
        /**
         * @return true when the requested scope value exists.
         */
        boolean exists(
                String conceptName,
                String fieldPath,
                Object expectedValue,
                Map<String, Object> state,
                Object payload
        );
    }

    private static final UniqueValueChecker NO_UNIQUE_CHECKER = (entity, field, value, payload) -> false;
    private static final CompoundUniqueValueChecker NO_COMPOUND_UNIQUE_CHECKER =
            (entity, fields, values, payload) -> false;
    private static final ConflictChecker NO_CONFLICT_CHECKER =
            (resourceField, resourceId, startsAtField, startsAt, durationField, durationMinutes, excludeId, payload) -> false;
    private static final ScopeChecker NO_SCOPE_CHECKER =
            (conceptName, fieldPath, expectedValue, state, payload) -> false;

    private final Map<String, EntityRules> rulesByEntity;
    private final UniqueValueChecker uniqueValueChecker;
    private final CompoundUniqueValueChecker compoundUniqueValueChecker;
    private final ConflictChecker conflictChecker;
    private final ScopeChecker scopeChecker;

    public CelInvariantEngine() {
        this(Collections.emptyMap(), NO_UNIQUE_CHECKER, NO_CONFLICT_CHECKER);
    }

    public CelInvariantEngine(Map<String, EntityRules> rulesByEntity) {
        this(rulesByEntity, NO_UNIQUE_CHECKER, NO_CONFLICT_CHECKER);
    }

    public CelInvariantEngine(Map<String, EntityRules> rulesByEntity, UniqueValueChecker uniqueValueChecker) {
        this(rulesByEntity, uniqueValueChecker, NO_CONFLICT_CHECKER);
    }

    public CelInvariantEngine(
            Map<String, EntityRules> rulesByEntity,
            UniqueValueChecker uniqueValueChecker,
            ConflictChecker conflictChecker
    ) {
        this(rulesByEntity, uniqueValueChecker, conflictChecker, NO_SCOPE_CHECKER);
    }

    public CelInvariantEngine(
            Map<String, EntityRules> rulesByEntity,
            UniqueValueChecker uniqueValueChecker,
            ConflictChecker conflictChecker,
            ScopeChecker scopeChecker
    ) {
        this(rulesByEntity, uniqueValueChecker, conflictChecker, scopeChecker, NO_COMPOUND_UNIQUE_CHECKER);
    }

    public CelInvariantEngine(
            Map<String, EntityRules> rulesByEntity,
            UniqueValueChecker uniqueValueChecker,
            ConflictChecker conflictChecker,
            ScopeChecker scopeChecker,
            CompoundUniqueValueChecker compoundUniqueValueChecker
    ) {
        Objects.requireNonNull(rulesByEntity, "rulesByEntity");
        this.uniqueValueChecker = uniqueValueChecker == null ? NO_UNIQUE_CHECKER : uniqueValueChecker;
        this.conflictChecker = conflictChecker == null ? NO_CONFLICT_CHECKER : conflictChecker;
        this.scopeChecker = scopeChecker == null ? NO_SCOPE_CHECKER : scopeChecker;
        this.compoundUniqueValueChecker = compoundUniqueValueChecker == null ? NO_COMPOUND_UNIQUE_CHECKER : compoundUniqueValueChecker;

        Map<String, EntityRules> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, EntityRules> e : rulesByEntity.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            normalized.put(normalize(e.getKey()), e.getValue());
        }
        this.rulesByEntity = Collections.unmodifiableMap(normalized);
    }

    public static CelInvariantEngine fromCompiledModel(CompiledModel model) {
        return fromCompiledModel(model, NO_UNIQUE_CHECKER, NO_CONFLICT_CHECKER);
    }

    public static CelInvariantEngine fromCompiledModel(CompiledModel model, UniqueValueChecker uniqueValueChecker) {
        return fromCompiledModel(model, uniqueValueChecker, NO_CONFLICT_CHECKER);
    }

    public static CelInvariantEngine fromCompiledModel(
            CompiledModel model,
            UniqueValueChecker uniqueValueChecker,
            ConflictChecker conflictChecker
    ) {
        return fromCompiledModel(model, uniqueValueChecker, conflictChecker, NO_SCOPE_CHECKER);
    }

    public static CelInvariantEngine fromCompiledModel(
            CompiledModel model,
            UniqueValueChecker uniqueValueChecker,
            ConflictChecker conflictChecker,
            ScopeChecker scopeChecker
    ) {
        return fromCompiledModel(model, uniqueValueChecker, conflictChecker, scopeChecker, NO_COMPOUND_UNIQUE_CHECKER);
    }

    public static CelInvariantEngine fromCompiledModel(
            CompiledModel model,
            UniqueValueChecker uniqueValueChecker,
            ConflictChecker conflictChecker,
            ScopeChecker scopeChecker,
            CompoundUniqueValueChecker compoundUniqueValueChecker
    ) {
        Objects.requireNonNull(model, "model");

        Map<String, EntityRules> rules = new LinkedHashMap<>();
        for (CompiledConcept entity : model.getConcepts()) {
            rules.put(entity.getName(), EntityRules.fromCompiledConcept(entity));
        }

        return new CelInvariantEngine(rules, uniqueValueChecker, conflictChecker, scopeChecker, compoundUniqueValueChecker);
    }

    @Override
    public List<String> evaluate(String entityName, Object payload) {
        if (entityName == null || entityName.isBlank()) {
            return List.of("Invariant evaluation requires a non-blank entity name");
        }

        EntityRules rules = rulesByEntity.get(normalize(entityName));
        if (rules == null) {
            return Collections.emptyList();
        }

        if (payload == null) {
            return List.of("Entity " + entityName + ": payload is null");
        }

        List<String> violations = new ArrayList<>();

        for (String invariantRef : rules.orderedRefs()) {
            RuleEvaluationResult ruleResult = evaluateRule(entityName, payload, invariantRef, rules, Map.of());
            if (!ruleResult.ok()) {
                violations.add(ruleResult.message());
            }
        }

        return violations;
    }

    @Override
    public List<Violation> evaluate(List<String> invariants, EvaluationContext context) {
        InvariantEvaluationResult result = evaluate(new InvariantEvaluationRequest(
                context.entityName(),
                context.payload(),
                invariants,
                new EvaluationMetadata(
                        context.flowName(),
                        "<batch-step>",
                        0,
                        context.checkpoint(),
                        null
                ),
                context.state()
        ));
        return result.violations();
    }

    @Override
    public InvariantEvaluationResult evaluate(InvariantEvaluationRequest request) {
        EntityRules rules = rulesByEntity.get(normalize(request.conceptName()));
        if (rules == null) {
            List<Violation> violations = request.invariantRefs().stream()
                    .map(invariantRef -> new Violation(
                            "INVARIANT_REF_UNKNOWN",
                            "Unknown concept for invariant evaluation: " + request.conceptName(),
                            invariantRef,
                            request.conceptName(),
                            request.metadata().flowName(),
                            request.metadata().stepName(),
                            request.metadata().stepIndex(),
                            Map.of("knownConcepts", rulesByEntity.keySet())
                    ))
                    .toList();
            return new InvariantEvaluationResult(violations);
        }

        List<Violation> violations = new ArrayList<>();
        for (String invariantRef : request.invariantRefs()) {
            RuleEvaluationResult ruleResult = evaluateRule(
                    request.conceptName(),
                    request.payload(),
                    invariantRef,
                    rules,
                    request.state()
            );
            if (!ruleResult.ok()) {
                violations.add(new Violation(
                        ruleResult.code(),
                        ruleResult.message(),
                        invariantRef,
                        request.conceptName(),
                        request.metadata().flowName(),
                        request.metadata().stepName(),
                        request.metadata().stepIndex(),
                        ruleResult.details()
                ));
            }
        }
        return new InvariantEvaluationResult(violations);
    }
    public record EntityRules(
            Set<String> requiredFields,
            Set<String> uniqueFields,
            List<String> expressions,
            Map<String, InvariantRule> rulesByRef,
            List<String> orderedRefs
    ) {
        public EntityRules(Set<String> requiredFields, Set<String> uniqueFields) {
            this(requiredFields, uniqueFields, List.of());
        }

        public EntityRules(Set<String> requiredFields, Set<String> uniqueFields, List<String> expressions) {
            this(
                    requiredFields,
                    uniqueFields,
                    expressions,
                    buildRulesByRef(requiredFields, uniqueFields, expressions),
                    buildOrderedRefs(requiredFields, uniqueFields, expressions)
            );
        }

        public EntityRules {
            requiredFields = immutableCopy(requiredFields);
            uniqueFields = immutableCopy(uniqueFields);
            expressions = immutableExpressionCopy(expressions);
            rulesByRef = immutableRulesMap(rulesByRef);
            orderedRefs = orderedRefs == null ? List.copyOf(rulesByRef.keySet()) : List.copyOf(orderedRefs);
        }

        public static EntityRules fromCompiledConcept(CompiledConcept entity) {
            Map<String, InvariantRule> compiledRules = new LinkedHashMap<>();
            List<String> orderedRefs = new ArrayList<>();
            Set<String> requiredFields = new LinkedHashSet<>();
            Set<String> uniqueFields = new LinkedHashSet<>();
            List<String> expressions = new ArrayList<>();

            if (entity.getInvariants() != null && !entity.getInvariants().isEmpty()) {
                for (CompiledInvariant invariant : entity.getInvariants()) {
                    if (invariant == null || invariant.getRef() == null || invariant.getRef().isBlank()) {
                        continue;
                    }
                    String type = invariant.getType() == null ? "" : invariant.getType().trim().toLowerCase(Locale.ROOT);
                    String normalizedRef = normalize(invariant.getRef());
                    compiledRules.put(normalizedRef,
                            new InvariantRule(type, invariant.getField(), invariant.getExpression(), invariant.getFields()));
                    orderedRefs.add(invariant.getRef());

                    if ("required".equals(type) && invariant.getField() != null && !invariant.getField().isBlank()) {
                        requiredFields.add(invariant.getField());
                    } else if ("unique".equals(type)
                            && invariant.getFields() != null
                            && invariant.getFields().size() == 1) {
                        // LIFT-UNIQUE-P3: compound (2+ field) unique invariants stay out of this
                        // single-field set and are enforced via evaluateCompoundUniqueRule instead.
                        uniqueFields.add(invariant.getFields().get(0));
                    } else if ("expression".equals(type)
                            && invariant.getExpression() != null
                            && !invariant.getExpression().isBlank()) {
                        expressions.add(invariant.getExpression().trim());
                    }
                }
            }

            if (compiledRules.isEmpty()) {
                for (CompiledField field : entity.getFields()) {
                    if (field.isRequired()) requiredFields.add(field.getName());
                    if (field.isUnique()) uniqueFields.add(field.getName());
                }
                expressions.addAll(entity.getExpressionInvariants());
                return new EntityRules(requiredFields, uniqueFields, expressions);
            }
            return new EntityRules(requiredFields, uniqueFields, expressions, compiledRules, orderedRefs);
        }

        private static Set<String> immutableCopy(Collection<String> values) {
            if (values == null || values.isEmpty()) {
                return Collections.emptySet();
            }

            Set<String> copy = new LinkedHashSet<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    copy.add(value);
                }
            }
            return Collections.unmodifiableSet(copy);
        }

        private static List<String> immutableExpressionCopy(Collection<String> values) {
            if (values == null || values.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    out.add(value.trim());
                }
            }
            return Collections.unmodifiableList(out);
        }

        private static Map<String, InvariantRule> immutableRulesMap(Map<String, InvariantRule> values) {
            if (values == null || values.isEmpty()) {
                return Map.of();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        private static Map<String, InvariantRule> buildRulesByRef(
                Set<String> requiredFields,
                Set<String> uniqueFields,
                List<String> expressions
        ) {
            Map<String, InvariantRule> rules = new LinkedHashMap<>();
            for (String field : requiredFields) {
                rules.put(normalize("required(" + field + ")"), new InvariantRule("required", field, null));
            }
            for (String field : uniqueFields) {
                rules.put(normalize("unique(" + field + ")"), new InvariantRule("unique", field, null));
            }
            for (String expression : expressions) {
                rules.put(normalize(expression), new InvariantRule("expression", null, expression));
            }
            return rules;
        }

        private static List<String> buildOrderedRefs(
                Set<String> requiredFields,
                Set<String> uniqueFields,
                List<String> expressions
        ) {
            List<String> refs = new ArrayList<>();
            for (String field : requiredFields) {
                refs.add("required(" + field + ")");
            }
            for (String field : uniqueFields) {
                refs.add("unique(" + field + ")");
            }
            refs.addAll(expressions);
            return Collections.unmodifiableList(refs);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private RuleEvaluationResult evaluateRule(
            String entityName,
            Object payload,
            String invariantRef,
            EntityRules rules,
            Map<String, Object> state
    ) {
        if (payload == null) {
            return RuleEvaluationResult.fail(
                    "INVARIANT_FAIL",
                    "Entity " + entityName + ": payload is null",
                    Map.of()
            );
        }

        InvariantRule rule = rules.rulesByRef().get(normalize(invariantRef));
        if (rule == null) {
            return RuleEvaluationResult.fail(
                    "INVARIANT_UNKNOWN_REF",
                    "Entity " + entityName + ": unknown invariant ref '" + invariantRef + "'",
                    Map.of("knownInvariantRefs", rules.orderedRefs())
            );
        }

        return switch (rule.type()) {
            case "required" -> evaluateRequiredRule(entityName, payload, invariantRef, rule.field());
            case "unique" -> rule.fields().size() > 1
                    ? evaluateCompoundUniqueRule(entityName, payload, invariantRef, rule.fields())
                    : evaluateUniqueRule(entityName, payload, invariantRef, rule.field());
            case "expression" -> evaluateExpressionRule(entityName, payload, invariantRef, rule.expression(), state);
            default -> RuleEvaluationResult.fail(
                    "INVARIANT_UNKNOWN_REF",
                    "Entity " + entityName + ": unsupported invariant type '" + rule.type()
                            + "' for ref '" + invariantRef + "'",
                    Map.of()
            );
        };
    }

    private RuleEvaluationResult evaluateRequiredRule(
            String entityName,
            Object payload,
            String invariantRef,
            String field
    ) {
        Object value = readFieldValue(payload, field);
        if (!isMissing(value)) {
            return RuleEvaluationResult.success();
        }
        return RuleEvaluationResult.fail(
                "INVARIANT_FAIL",
                "Entity " + entityName + ": required field '" + field
                        + "' is missing (ref=" + invariantRef + ")",
                Map.of(
                        "field", field,
                        "fieldPath", field,
                        "invariantRef", invariantRef,
                        "violationKind", "required"
                )
        );
    }

    private RuleEvaluationResult evaluateUniqueRule(
            String entityName,
            Object payload,
            String invariantRef,
            String field
    ) {
        Object value = readFieldValue(payload, field);
        if (isMissing(value)) {
            return RuleEvaluationResult.success();
        }
        if (!uniqueValueChecker.exists(entityName, field, value, payload)) {
            return RuleEvaluationResult.success();
        }
        return RuleEvaluationResult.fail(
                "INVARIANT_FAIL",
                "Entity " + entityName + ": unique constraint violated for field '" + field
                        + "' (ref=" + invariantRef + ")",
                Map.of(
                        "field", field,
                        "fieldPath", field,
                        "value", value,
                        "invariantRef", invariantRef,
                        "violationKind", "unique"
                )
        );
    }

    /** LIFT-UNIQUE-P3: compound (2+ field) unique invariant. Mirrors evaluateUniqueRule's
     * "missing value -> success" leniency: if any field in the group is absent, the group can't
     * collide yet, so the check is skipped (consistent with the single-field behavior above). */
    private RuleEvaluationResult evaluateCompoundUniqueRule(
            String entityName,
            Object payload,
            String invariantRef,
            List<String> fields
    ) {
        List<Object> values = new ArrayList<>(fields.size());
        for (String field : fields) {
            Object value = readFieldValue(payload, field);
            if (isMissing(value)) {
                return RuleEvaluationResult.success();
            }
            values.add(value);
        }
        if (!compoundUniqueValueChecker.exists(entityName, fields, values, payload)) {
            return RuleEvaluationResult.success();
        }
        String fieldList = String.join(", ", fields);
        return RuleEvaluationResult.fail(
                "INVARIANT_FAIL",
                "Entity " + entityName + ": unique constraint violated for fields (" + fieldList
                        + ") (ref=" + invariantRef + ")",
                Map.of(
                        "fields", fields,
                        "fieldPath", fieldList,
                        "values", values,
                        "invariantRef", invariantRef,
                        "violationKind", "unique"
                )
        );
    }

    private RuleEvaluationResult evaluateExpressionRule(
            String entityName,
            Object payload,
            String invariantRef,
            String expression,
            Map<String, Object> state
    ) {
        ExpressionResult expressionResult = evaluateExpression(payload, expression, state);
        if (expressionResult.ok()) {
            return RuleEvaluationResult.success();
        }

        String details = expressionResult.details();
        String message;
        if ("Resource is already reserved during this time".equals(details)) {
            message = details;
        } else {
            message = "Entity " + entityName + ": expression invariant failed: " + expression
                    + " (ref=" + invariantRef + ")";
            if (details != null && !details.isBlank()) {
                message += " (" + details + ")";
            }
        }

        Map<String, Object> detailMap = new LinkedHashMap<>();
        detailMap.put("expression", expression);
        detailMap.put("invariantRef", invariantRef);
        detailMap.put("violationKind", "expression");
        if (expressionResult.fieldPath() != null && !expressionResult.fieldPath().isBlank()) {
            detailMap.put("fieldPath", expressionResult.fieldPath());
        }
        ScopeExistsInvocation scopeInvocation = parseScopeExistsInvocation(expression);
        if (scopeInvocation != null) {
            detailMap.put("scopeConcept", scopeInvocation.conceptName());
            detailMap.put("scopeFieldPath", scopeInvocation.fieldPath());
            if (scopeInvocation.valuePath() != null && !scopeInvocation.valuePath().isBlank()) {
                detailMap.put("scopeValuePath", scopeInvocation.valuePath());
            }
        }

        return RuleEvaluationResult.fail("INVARIANT_FAIL", message, detailMap);
    }

    private static boolean isMissing(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        return false;
    }

    private static Object readFieldValue(Object payload, String fieldPath) {
        if (payload == null || fieldPath == null || fieldPath.isBlank()) {
            return null;
        }

        List<Object> resolved = resolvePathValues(payload, fieldPath.trim());
        if (!resolved.isEmpty()) {
            return resolved.get(0);
        }
        return null;
    }

    private static List<Object> resolvePathValues(Object payload, String fieldPath) {
        if (payload == null || fieldPath == null || fieldPath.isBlank()) {
            return List.of();
        }

        List<String> segments = splitPath(fieldPath.trim());
        if (segments.isEmpty()) {
            return List.of();
        }

        List<Object> current = new ArrayList<>();
        current.add(payload);

        for (String rawSegment : segments) {
            if (current.isEmpty()) {
                return List.of();
            }

            boolean wildcard = rawSegment.endsWith("[*]");
            String segment = wildcard
                    ? rawSegment.substring(0, rawSegment.length() - 3)
                    : rawSegment;

            List<Object> next = new ArrayList<>();
            for (Object candidate : current) {
                Object resolved = readSimpleFieldValue(candidate, segment);
                if (wildcard) {
                    Iterable<?> iterable = toIterable(resolved);
                    if (iterable == null) {
                        continue;
                    }
                    for (Object item : iterable) {
                        next.add(item);
                    }
                } else if (resolved != null) {
                    next.add(resolved);
                }
            }
            current = next;
        }

        return current;
    }

    private static List<String> splitPath(String path) {
        if (path == null || path.isBlank()) {
            return List.of();
        }
        String[] tokens = path.split("\\.");
        List<String> out = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (token != null && !token.isBlank()) {
                out.add(token.trim());
            }
        }
        return out;
    }

    private static Object readSimpleFieldValue(Object payload, String fieldName) {
        if (payload == null || fieldName == null || fieldName.isBlank()) {
            return null;
        }

        if (payload instanceof Map<?, ?> map) {
            Object direct = map.get(fieldName);
            if (direct != null || map.containsKey(fieldName)) {
                return direct;
            }

            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getKey() instanceof String key && key.equalsIgnoreCase(fieldName)) {
                    return e.getValue();
                }
            }
            return null;
        }

        ValueResult getterValue = readByGetter(payload, fieldName);
        if (getterValue.found()) {
            return getterValue.value();
        }

        ValueResult fieldValue = readByField(payload, fieldName);
        if (fieldValue.found()) {
            return fieldValue.value();
        }

        return null;
    }

    private static ValueResult readByGetter(Object payload, String fieldName) {
        if (payload == null || fieldName == null || fieldName.isBlank()) {
            return ValueResult.notFound();
        }
        String suffix = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        String[] candidates = new String[] { "get" + suffix, "is" + suffix };

        for (String methodName : candidates) {
            try {
                Method m = payload.getClass().getMethod(methodName);
                return new ValueResult(true, m.invoke(payload));
            } catch (ReflectiveOperationException ignored) {
                // Try next candidate.
            }
        }
        return ValueResult.notFound();
    }

    private static ValueResult readByField(Object payload, String fieldName) {
        if (payload == null || fieldName == null || fieldName.isBlank()) {
            return ValueResult.notFound();
        }
        Class<?> type = payload.getClass();
        while (type != null && type != Object.class) {
            for (Field f : type.getDeclaredFields()) {
                if (f.getName().equalsIgnoreCase(fieldName)) {
                    try {
                        f.setAccessible(true);
                        return new ValueResult(true, f.get(payload));
                    } catch (ReflectiveOperationException ignored) {
                        return ValueResult.notFound();
                    }
                }
            }
            type = type.getSuperclass();
        }
        return ValueResult.notFound();
    }

    private record ValueResult(boolean found, Object value) {
        static ValueResult notFound() {
            return new ValueResult(false, null);
        }
    }

    /**
     * Adapts an arbitrary payload (Map or POJO) to the {@code Map<String,Object>} scope
     * {@link ComputedExpression} expects, resolving every lookup through {@link #readFieldValue}
     * so dotted/case-insensitive/reflection field resolution stays identical to the legacy
     * matcher's behavior. Only {@code get}/{@code containsKey} are exercised by ComputedExpression.
     */
    private static final class FieldPathScope extends AbstractMap<String, Object> {
        private final Object payload;

        FieldPathScope(Object payload) {
            this.payload = payload;
        }

        @Override
        public Object get(Object key) {
            return key instanceof String fieldPath ? readFieldValue(payload, fieldPath) : null;
        }

        @Override
        public boolean containsKey(Object key) {
            return true;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            return Collections.emptySet();
        }
    }

    private ExpressionResult evaluateExpression(Object payload, String expression, Map<String, Object> state) {
        if (expression == null || expression.isBlank()) {
            return ExpressionResult.failure("blank expression");
        }

        // LIFT-EXPR-P2: try the unified ComputedExpression grammar first — it's a strict
        // superset (parens, unary !, arithmetic-in-comparisons, dotted paths) of the legacy
        // atom/DNF matcher below. It throws ExpressionException on any syntax it doesn't
        // recognize (regex .matches(), .uniqueBy(), .all()/.exists() quantifiers, conflicts()/
        // overlapsProvider(), scope.exists(), [*] wildcards), so those CEL-specific forms fall
        // through unchanged to the legacy matcher, which is the only implementation for them.
        try {
            boolean ok = ComputedExpression.evaluateBoolean(expression, new FieldPathScope(payload));
            if (ok) {
                return ExpressionResult.success();
            }
            // Best-effort fieldPath so simple "field op value" failures still attribute to a
            // field for UI highlighting, matching the legacy matcher's behavior for that shape.
            // Compound (&&/||/paren/!) expressions are new capability with no prior fieldPath
            // to preserve, so they report the expression with no single fieldPath.
            Matcher simpleComparison = COMPARISON_PATTERN.matcher(expression);
            String fieldPath = simpleComparison.matches() ? simpleComparison.group(1) : null;
            return ExpressionResult.failure("expression evaluated to false", fieldPath);
        } catch (ComputedExpression.ExpressionException legacySyntax) {
            // fall through
        }

        List<String> disjuncts = splitLogicalExpression(expression, "||");
        if (disjuncts.size() > 1) {
            ExpressionResult lastFailure = null;
            for (String disjunct : disjuncts) {
                ExpressionResult result = evaluateExpression(payload, disjunct, state);
                if (result.ok()) {
                    return ExpressionResult.success();
                }
                lastFailure = result;
            }
            return lastFailure == null ? ExpressionResult.failure("expected at least one expression to pass") : lastFailure;
        }

        List<String> conjuncts = splitLogicalExpression(expression, "&&");
        if (conjuncts.size() > 1) {
            for (String conjunct : conjuncts) {
                ExpressionResult result = evaluateExpression(payload, conjunct, state);
                if (!result.ok()) {
                    return result;
                }
            }
            return ExpressionResult.success();
        }

        ConflictInvocation conflictInvocation = parseConflictInvocation(expression);
        if (conflictInvocation != null) {
            return evaluateConflictExpression(payload, conflictInvocation);
        }

        ScopeExistsInvocation scopeInvocation = parseScopeExistsInvocation(expression);
        if (scopeInvocation != null) {
            return evaluateScopeExistsExpression(payload, state, scopeInvocation);
        }

        Matcher matches = MATCHES_PATTERN.matcher(expression);
        if (matches.matches()) {
            String fieldName = matches.group(1);
            String regexToken = matches.group(2);

            Object fieldValue = readFieldValue(payload, fieldName);
            if (!(fieldValue instanceof String value)) {
                return ExpressionResult.failure("field '" + fieldName + "' is not a string", fieldName);
            }

            String regex = unquote(regexToken.trim());
            if (regex == null || regex.isBlank()) {
                return ExpressionResult.failure("regex is blank", fieldName);
            }
            boolean ok = value.matches(regex);
            return ok
                    ? ExpressionResult.success()
                    : ExpressionResult.failure("regex mismatch on field '" + fieldName + "'", fieldName);
        }

        Matcher uniqueBy = UNIQUE_BY_PATTERN.matcher(expression);
        if (uniqueBy.matches()) {
            String collectionField = uniqueBy.group(1);
            String keyField = uniqueBy.group(2);
            Object collectionValue = readFieldValue(payload, collectionField);
            if (collectionValue == null) {
                return ExpressionResult.success();
            }

            Iterable<?> iterable = toIterable(collectionValue);
            if (iterable == null) {
                return ExpressionResult.failure(
                        "field '" + collectionField + "' is not an array/collection",
                        collectionField
                );
            }

            Set<String> seen = new LinkedHashSet<>();
            for (Object item : iterable) {
                Object rawKey = readFieldValue(item, keyField);
                if (rawKey == null) {
                    continue;
                }
                String normalizedKey = String.valueOf(rawKey).trim().toLowerCase(Locale.ROOT);
                if (normalizedKey.isBlank()) {
                    continue;
                }
                if (!seen.add(normalizedKey)) {
                    return ExpressionResult.failure(
                            "duplicate '" + keyField + "' value '" + rawKey + "'",
                            collectionItemPath(collectionField, keyField)
                    );
                }
            }
            return ExpressionResult.success();
        }

        Matcher quantified = QUANTIFIED_PATTERN.matcher(expression);
        if (quantified.matches()) {
            String collectionField = quantified.group(1);
            String quantifier = quantified.group(2);
            String alias = quantified.group(3);
            String predicate = quantified.group(4);

            Object collectionValue = readFieldValue(payload, collectionField);
            if (collectionValue == null) {
                if ("all".equals(quantifier)) {
                    return ExpressionResult.success();
                }
                return ExpressionResult.failure(
                        "exists predicate requires a non-empty collection",
                        inferPredicatePath(predicate, alias, collectionField)
                );
            }

            Iterable<?> iterable = toIterable(collectionValue);
            if (iterable == null) {
                return ExpressionResult.failure(
                        "field '" + collectionField + "' is not an array/collection",
                        collectionField
                );
            }

            boolean anyMatch = false;
            int count = 0;
            for (Object item : iterable) {
                count++;
                ExpressionResult predicateResult = evaluatePredicate(
                        predicate,
                        alias,
                        item,
                        payload,
                        collectionField
                );
                if ("all".equals(quantifier)) {
                    if (!predicateResult.ok()) {
                        String path = predicateResult.fieldPath();
                        if (path == null || path.isBlank()) {
                            path = inferPredicatePath(predicate, alias, collectionField);
                        }
                        return ExpressionResult.failure(predicateResult.details(), path);
                    }
                } else if (predicateResult.ok()) {
                    anyMatch = true;
                    break;
                }
            }

            if ("all".equals(quantifier)) {
                return ExpressionResult.success();
            }
            if (anyMatch) {
                return ExpressionResult.success();
            }
            if (count == 0) {
                return ExpressionResult.failure(
                        "exists predicate requires a non-empty collection",
                        inferPredicatePath(predicate, alias, collectionField)
                );
            }
            return ExpressionResult.failure(
                    "no collection item satisfied predicate",
                    inferPredicatePath(predicate, alias, collectionField)
            );
        }

        Matcher comparison = COMPARISON_PATTERN.matcher(expression);
        if (comparison.matches()) {
            String fieldName = comparison.group(1);
            String operator = comparison.group(2);
            String rightToken = comparison.group(3).trim();

            Object leftValue = readFieldValue(payload, fieldName);
            Object rightValue = resolveRightOperand(rightToken, null, null, payload);
            ExpressionResult compared = compareValues(fieldName, leftValue, operator, rightValue);
            if (compared.ok()) {
                return compared;
            }
            return ExpressionResult.failure(compared.details(), fieldName);
        }

        return ExpressionResult.failure("unsupported expression format");
    }

    private ExpressionResult evaluateScopeExistsExpression(
            Object payload,
            Map<String, Object> state,
            ScopeExistsInvocation invocation
    ) {
        Object expectedValue = invocation.valuePath() == null
                ? invocation.literalValue()
                : readFieldValue(payload, invocation.valuePath());
        boolean exists = scopeChecker.exists(
                invocation.conceptName(),
                invocation.fieldPath(),
                expectedValue,
                state == null ? Map.of() : state,
                payload
        );
        boolean passes = invocation.negated() ? !exists : exists;
        if (passes) {
            return ExpressionResult.success();
        }

        String message = invocation.negated()
                ? "scope.exists matched an entity that should not exist"
                : "scope.exists did not find a matching entity";
        String fieldPath = invocation.valuePath();
        if (fieldPath == null || fieldPath.isBlank()) {
            fieldPath = invocation.fieldPath();
        }
        return ExpressionResult.failure(message, fieldPath);
    }

    private ExpressionResult evaluateConflictExpression(Object payload, ConflictInvocation invocation) {
        Object subjectId = readFieldValue(payload, invocation.resourceFieldPath());
        Object scheduledAt = readFieldValue(payload, invocation.startsAtPath());
        Object durationMinutes = readFieldValue(payload, invocation.durationMinutesPath());
        Object excludeId = invocation.excludeIdPath() == null
                ? null
                : readFieldValue(payload, invocation.excludeIdPath());

        boolean hasConflict = conflictChecker.conflicts(
                invocation.resourceFieldPath(),
                subjectId,
                invocation.startsAtPath(),
                scheduledAt,
                invocation.durationMinutesPath(),
                durationMinutes,
                excludeId,
                payload
        );

        boolean passes = invocation.negated() ? !hasConflict : hasConflict;
        if (passes) {
            return ExpressionResult.success();
        }

        return ExpressionResult.failure(
                "Resource is already reserved during this time",
                invocation.resourceFieldPath()
        );
    }

    private static ExpressionResult evaluatePredicate(
            String predicate,
            String alias,
            Object item,
            Object payload,
            String collectionField
    ) {
        Matcher matches = MATCHES_PATTERN.matcher(predicate);
        if (matches.matches()) {
            String token = matches.group(1);
            String regexToken = matches.group(2);
            Object value = resolvePathToken(token, alias, item, payload);
            String path = resolveFieldPathToken(token, alias, collectionField);

            if (!(value instanceof String strVal)) {
                return ExpressionResult.failure("field '" + token + "' is not a string", path);
            }
            String regex = unquote(regexToken.trim());
            if (regex == null || regex.isBlank()) {
                return ExpressionResult.failure("regex is blank", path);
            }
            return strVal.matches(regex)
                    ? ExpressionResult.success()
                    : ExpressionResult.failure("regex mismatch on field '" + token + "'", path);
        }

        Matcher comparison = COMPARISON_PATTERN.matcher(predicate);
        if (comparison.matches()) {
            String leftToken = comparison.group(1);
            String operator = comparison.group(2);
            String rightToken = comparison.group(3).trim();

            Object leftValue = resolvePathToken(leftToken, alias, item, payload);
            Object rightValue = resolveRightOperand(rightToken, alias, item, payload);
            String path = resolveFieldPathToken(leftToken, alias, collectionField);

            ExpressionResult compared = compareValues(path == null ? leftToken : path, leftValue, operator, rightValue);
            if (compared.ok()) {
                return ExpressionResult.success();
            }
            return ExpressionResult.failure(compared.details(), path);
        }

        return ExpressionResult.failure("unsupported predicate format", inferPredicatePath(predicate, alias, collectionField));
    }

    private static ConflictInvocation parseConflictInvocation(String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        boolean negated = false;
        if (trimmed.startsWith("!")) {
            negated = true;
            trimmed = trimmed.substring(1).trim();
        }

        String functionPrefix = "conflicts(";
        if (trimmed.startsWith("overlapsProvider(")) {
            functionPrefix = "overlapsProvider(";
        }
        if (!trimmed.startsWith(functionPrefix) || !trimmed.endsWith(")")) {
            return null;
        }
        String argsSection = trimmed.substring(functionPrefix.length(), trimmed.length() - 1).trim();
        if (argsSection.isBlank()) {
            return null;
        }

        String[] rawArgs = argsSection.split(",");
        if (rawArgs.length != 3 && rawArgs.length != 4) {
            return null;
        }

        List<String> args = new ArrayList<>(rawArgs.length);
        for (String rawArg : rawArgs) {
            String arg = rawArg == null ? "" : rawArg.trim();
            if (!isPathToken(arg)) {
                return null;
            }
            args.add(arg);
        }

        String exclude = args.size() == 4 ? args.get(3) : null;
        return new ConflictInvocation(negated, args.get(0), args.get(1), args.get(2), exclude);
    }

    private static List<String> splitLogicalExpression(String expression, String operator) {
        if (expression == null || expression.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i <= expression.length() - operator.length(); i++) {
            char ch = expression.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')' && depth > 0) {
                depth--;
                continue;
            }
            if (depth == 0 && expression.startsWith(operator, i)) {
                String part = expression.substring(start, i).trim();
                if (!part.isEmpty()) {
                    parts.add(part);
                }
                i += operator.length() - 1;
                start = i + 1;
            }
        }
        if (parts.isEmpty()) {
            return List.of(expression.trim());
        }
        String tail = expression.substring(start).trim();
        if (!tail.isEmpty()) {
            parts.add(tail);
        }
        return parts;
    }

    private static ScopeExistsInvocation parseScopeExistsInvocation(String expression) {
        if (expression == null) {
            return null;
        }
        String trimmed = expression.trim();
        boolean negated = false;
        if (trimmed.startsWith("!")) {
            negated = true;
            trimmed = trimmed.substring(1).trim();
        }

        String prefix = "scope.exists(";
        if (!trimmed.startsWith(prefix) || !trimmed.endsWith(")")) {
            return null;
        }
        String argsSection = trimmed.substring(prefix.length(), trimmed.length() - 1).trim();
        List<String> args = splitFunctionArguments(argsSection);
        if (args.size() != 3) {
            return null;
        }

        String conceptToken = args.get(0);
        String fieldToken = args.get(1);
        String valueToken = args.get(2);
        if (!isQuoted(conceptToken) || !isQuoted(fieldToken)) {
            return null;
        }

        String conceptName = unquote(conceptToken);
        String fieldPath = unquote(fieldToken);
        if (conceptName == null || conceptName.isBlank() || fieldPath == null || fieldPath.isBlank()) {
            return null;
        }

        String valuePath = null;
        Object literalValue = null;
        if (isScopeValuePathToken(valueToken)) {
            valuePath = valueToken.trim();
        } else {
            literalValue = parseLiteral(valueToken);
        }
        return new ScopeExistsInvocation(negated, conceptName.trim(), fieldPath.trim(), valuePath, literalValue);
    }

    private static List<String> splitFunctionArguments(String argsSection) {
        if (argsSection == null || argsSection.isBlank()) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int idx = 0; idx < argsSection.length(); idx++) {
            char ch = argsSection.charAt(idx);
            if (quote == 0 && (ch == '"' || ch == '\'')) {
                quote = ch;
                current.append(ch);
                continue;
            }
            if (quote != 0 && ch == quote) {
                quote = 0;
                current.append(ch);
                continue;
            }
            if (quote == 0 && ch == ',') {
                String value = current.toString().trim();
                if (!value.isEmpty()) {
                    args.add(value);
                }
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }

        String trailing = current.toString().trim();
        if (!trailing.isEmpty()) {
            args.add(trailing);
        }
        return args;
    }

    private static Object resolveRightOperand(String token, String alias, Object item, Object payload) {
        if (token == null) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (isPathToken(trimmed)
                && !isQuoted(trimmed)
                && !"true".equalsIgnoreCase(trimmed)
                && !"false".equalsIgnoreCase(trimmed)
                && !"null".equalsIgnoreCase(trimmed)) {
            return resolvePathToken(trimmed, alias, item, payload);
        }
        return parseLiteral(trimmed);
    }

    private static boolean isScopeValuePathToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String trimmed = value.trim();
        if (!isPathToken(trimmed) || isQuoted(trimmed)) {
            return false;
        }
        return !"true".equalsIgnoreCase(trimmed)
                && !"false".equalsIgnoreCase(trimmed)
                && !"null".equalsIgnoreCase(trimmed);
    }

    private static boolean isPathToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return PATH_EXACT_PATTERN.matcher(value).matches();
    }

    private static Object resolvePathToken(String token, String alias, Object item, Object payload) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        if (alias != null && !alias.isBlank()) {
            if (trimmed.equals(alias)) {
                return item;
            }
            if (trimmed.startsWith(alias + ".")) {
                return readFieldValue(item, trimmed.substring(alias.length() + 1));
            }
        }
        return readFieldValue(payload, trimmed);
    }

    private static String resolveFieldPathToken(String token, String alias, String collectionField) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String trimmed = token.trim();
        if (alias != null && !alias.isBlank()) {
            if (trimmed.equals(alias)) {
                return collectionItemPath(collectionField, null);
            }
            if (trimmed.startsWith(alias + ".")) {
                return collectionItemPath(collectionField, trimmed.substring(alias.length() + 1));
            }
        }
        return trimmed;
    }

    private static String inferPredicatePath(String predicate, String alias, String collectionField) {
        if (predicate != null) {
            Matcher comparison = COMPARISON_PATTERN.matcher(predicate);
            if (comparison.matches()) {
                return resolveFieldPathToken(comparison.group(1), alias, collectionField);
            }
            Matcher matches = MATCHES_PATTERN.matcher(predicate);
            if (matches.matches()) {
                return resolveFieldPathToken(matches.group(1), alias, collectionField);
            }
        }
        return collectionItemPath(collectionField, null);
    }

    private static String collectionItemPath(String collectionField, String itemField) {
        String base = collectionField == null ? "" : collectionField.trim();
        if (base.isBlank()) {
            return itemField == null ? null : itemField.trim();
        }
        if (!base.endsWith("[*]")) {
            base = base + "[*]";
        }
        if (itemField == null || itemField.isBlank()) {
            return base;
        }
        String suffix = itemField.trim();
        if (suffix.startsWith(".")) {
            suffix = suffix.substring(1);
        }
        return base + "." + suffix;
    }

    private static Iterable<?> toIterable(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Iterable<?> iterable) {
            return iterable;
        }
        if (value.getClass().isArray()) {
            List<Object> out = new ArrayList<>();
            int length = java.lang.reflect.Array.getLength(value);
            for (int idx = 0; idx < length; idx++) {
                out.add(java.lang.reflect.Array.get(value, idx));
            }
            return out;
        }
        return null;
    }

    private static ExpressionResult compareValues(String fieldName, Object leftValue, String operator, Object rightValue) {
        if ("==".equals(operator) || "!=".equals(operator)) {
            Object normalizedRight = coerceRightToLeftType(leftValue, rightValue);
            boolean equals = Objects.equals(leftValue, normalizedRight);
            if ("==".equals(operator)) {
                return equals ? ExpressionResult.success() : ExpressionResult.failure("expected equality");
            }
            return !equals ? ExpressionResult.success() : ExpressionResult.failure("expected inequality");
        }

        if (leftValue == null || rightValue == null) {
            return ExpressionResult.failure(
                    "operator " + operator + " requires non-null numeric operands for field '" + fieldName + "'"
            );
        }
        if (leftValue instanceof Number && rightValue instanceof Number) {
            BigDecimal left = toBigDecimal((Number) leftValue);
            BigDecimal right = toBigDecimal((Number) rightValue);
            int cmp = left.compareTo(right);
            return switch (operator) {
                case ">" -> cmp > 0 ? ExpressionResult.success() : ExpressionResult.failure("expected >");
                case "<" -> cmp < 0 ? ExpressionResult.success() : ExpressionResult.failure("expected <");
                case ">=" -> cmp >= 0 ? ExpressionResult.success() : ExpressionResult.failure("expected >=");
                case "<=" -> cmp <= 0 ? ExpressionResult.success() : ExpressionResult.failure("expected <=");
                default -> ExpressionResult.failure("unsupported operator");
            };
        }

        return ExpressionResult.failure(
                "operator " + operator + " requires numeric operands for field '" + fieldName + "'"
        );
    }

    private static Object coerceRightToLeftType(Object leftValue, Object rightValue) {
        if (leftValue instanceof Boolean) {
            if (rightValue instanceof Boolean b) return b;
            if (rightValue instanceof String s) return Boolean.parseBoolean(s);
        }
        if (leftValue instanceof String) {
            return String.valueOf(rightValue);
        }
        return rightValue;
    }

    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal bd) return bd;
        return new BigDecimal(n.toString());
    }

    private static Object parseLiteral(String literal) {
        if (literal == null) return null;
        String trimmed = literal.trim();
        if (trimmed.isEmpty()) return "";
        if ("null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if (isQuoted(trimmed)) {
            return unquote(trimmed);
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.parseBoolean(trimmed);
        }
        try {
            return new BigDecimal(trimmed);
        } catch (NumberFormatException ignored) {
            return trimmed;
        }
    }

    private static boolean isQuoted(String value) {
        return (value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"));
    }

    private static String unquote(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && isQuoted(trimmed)) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private record ExpressionResult(boolean ok, String details, String fieldPath) {
        static ExpressionResult success() {
            return new ExpressionResult(true, null, null);
        }

        static ExpressionResult failure(String details) {
            return new ExpressionResult(false, details, null);
        }

        static ExpressionResult failure(String details, String fieldPath) {
            return new ExpressionResult(false, details, fieldPath);
        }
    }

    private record RuleEvaluationResult(boolean ok, String code, String message, Map<String, Object> details) {
        private static RuleEvaluationResult success() {
            return new RuleEvaluationResult(true, null, null, Map.of());
        }

        private static RuleEvaluationResult fail(String code, String message, Map<String, Object> details) {
            return new RuleEvaluationResult(
                    false,
                    code == null || code.isBlank() ? "INVARIANT_FAIL" : code,
                    message,
                    details == null ? Map.of() : Map.copyOf(details)
            );
        }
    }

    private record ConflictInvocation(
            boolean negated,
            String resourceFieldPath,
            String startsAtPath,
            String durationMinutesPath,
            String excludeIdPath
    ) {
    }

    private record ScopeExistsInvocation(
            boolean negated,
            String conceptName,
            String fieldPath,
            String valuePath,
            Object literalValue
    ) {
    }

    private record InvariantRule(String type, String field, String expression, List<String> fields) {
        private InvariantRule {
            fields = (fields == null || fields.isEmpty())
                    ? (field == null ? List.of() : List.of(field))
                    : List.copyOf(fields);
        }

        InvariantRule(String type, String field, String expression) {
            this(type, field, expression, null);
        }
    }
}
