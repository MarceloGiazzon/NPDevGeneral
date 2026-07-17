package com.npdev.dsl.v1.compiled;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LNCH-1 Phase 0.2: a REFLECTIVE ratchet against the canonical-JSON field-loss bug class that has
 * bitten this codebase four separate times (see the comments in {@link CompiledModelCanonicalJson}
 * around {@code schedule}/{@code access}/{@code loopSteps}/{@code onFailureSteps} and in
 * {@link CompiledModelCanonicalJsonReader} for the same history): a new field gets added to a
 * {@code Compiled*} type but never threaded through the hand-rolled writer and/or reader, so a
 * generated app's {@code NPDevModelProvider} silently loses that feature at boot.
 *
 * <p>Unlike {@link CompiledModelCanonicalJsonReaderTest}, which hand-constructs a fixture and
 * hand-asserts specific fields (so a newly added field is invisible to it until someone remembers
 * to extend it), this test builds a "maximally populated" {@link CompiledModel} entirely via
 * reflection over each {@code Compiled*} type's widest public constructor, round-trips it through
 * {@link CompiledModelCanonicalJson#toJson(CompiledModel)} /
 * {@link CompiledModelCanonicalJsonReader#fromJson(String)}, and then compares EVERY accessor
 * (record component or JavaBean getter) of EVERY reachable {@code Compiled*} type between the
 * original and the restored model. A newly added field is therefore picked up automatically, with
 * no edits to this file required -- if the writer/reader don't thread it through, this test fails
 * by construction, naming the exact type + accessor + expected + actual value.
 */
class CanonicalJsonRoundTripCompletenessTest {

    private static final String COMPILED_PACKAGE = "com.npdev.dsl.v1.compiled";

    /** Types in the compiled package that are not part of the CompiledModel data graph (support/
     * utility classes, enum-holder classes, or the writer/reader themselves) -- excluded per the
     * task brief rather than treated as "no rule found" failures. */
    private static final Set<String> EXCLUDED_TYPE_NAMES = Set.of(
            "SqlTypeSupport",
            "JavaIdentifierSupport",
            "SqlIdentifierSupport",
            "FieldWidgetDefaults",
            "GuidePageDefaults",
            "CompiledMetadataCanonicalJson",
            "CompiledPluginRequirementGraphBuilder",
            "CompiledPluginRequirementGraph",
            "CompiledPluginRequirement",
            "CompiledModelCanonicalJson",
            "CompiledModelCanonicalJsonReader"
    );

    /** Caps recursion through self-/mutually-recursive chains (CompiledFlowStep.thenSteps/...,
     * CompiledSchema.items/properties, CompiledPanelLayout.children, CompiledAggregateCollection
     * .collections). Deep enough to prove at least two full nesting levels; bounded so the fixture
     * stays a few hundred objects, not exponential. */
    private static final int MAX_DEPTH = 4;

    @Test
    void everyReachableAccessorSurvivesTheCanonicalJsonRoundTrip() throws Exception {
        ReflectiveValueFactory factory = new ReflectiveValueFactory();
        CompiledModel original = (CompiledModel) factory.generateForClass(CompiledModel.class, 0);

        String json = CompiledModelCanonicalJson.toJson(original);
        CompiledModel restored = CompiledModelCanonicalJsonReader.fromJson(json);

        List<String> mismatches = new ArrayList<>();
        compare(CompiledModel.class, original, restored, "CompiledModel", mismatches);

        assertTrue(mismatches.isEmpty(),
                "Canonical JSON round trip dropped/altered " + mismatches.size() + " value(s) -- "
                        + "a new or existing field is not threaded through CompiledModelCanonicalJson "
                        + "and/or CompiledModelCanonicalJsonReader:\n" + String.join("\n", mismatches));
    }

    // ------------------------------------------------------------------------------------------
    // Comparator: walks the SAME accessor discovery rules the generator uses, on the DECLARED
    // (static) type of each accessor, so the comparison is driven by the type shape, not by
    // getClass() of whatever the reader happened to produce.
    // ------------------------------------------------------------------------------------------

    private static void compare(Type declaredType, Object originalValue, Object restoredValue, String path,
                                 List<String> mismatches) {
        Type resolved = unwrap(declaredType);

        if (resolved instanceof ParameterizedType parameterizedType) {
            Class<?> raw = rawClassOf(parameterizedType);
            if (Collection.class.isAssignableFrom(raw)) {
                List<?> a = originalValue == null ? List.of() : new ArrayList<>((Collection<?>) originalValue);
                List<?> b = restoredValue == null ? List.of() : new ArrayList<>((Collection<?>) restoredValue);
                if (a.size() != b.size()) {
                    mismatches.add(path + ": list size mismatch -- expected " + a.size() + " element(s) "
                            + describe(a) + " but restored " + b.size() + " element(s) " + describe(b));
                    return;
                }
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                for (int i = 0; i < a.size(); i++) {
                    compare(elementType, a.get(i), b.get(i), path + "[" + i + "]", mismatches);
                }
                return;
            }
            if (Map.class.isAssignableFrom(raw)) {
                Map<?, ?> a = originalValue == null ? Map.of() : (Map<?, ?>) originalValue;
                Map<?, ?> b = restoredValue == null ? Map.of() : (Map<?, ?>) restoredValue;
                if (!a.keySet().equals(b.keySet())) {
                    mismatches.add(path + ": map key set mismatch -- expected " + a.keySet()
                            + " but restored " + b.keySet());
                    return;
                }
                Type valueType = parameterizedType.getActualTypeArguments()[1];
                for (Object key : a.keySet()) {
                    compare(valueType, a.get(key), b.get(key), path + "[" + key + "]", mismatches);
                }
                return;
            }
            mismatches.add(path + ": comparator has no rule for parameterized type " + parameterizedType);
            return;
        }

        Class<?> declaredClass = (Class<?>) resolved;

        if (isScalar(declaredClass) || declaredClass.isEnum() || declaredClass == Object.class) {
            if (!java.util.Objects.equals(originalValue, restoredValue)) {
                mismatches.add(path + ": expected <" + describe(originalValue) + "> but restored <"
                        + describe(restoredValue) + ">");
            }
            return;
        }

        if (isDomainType(declaredClass)) {
            if (originalValue == null && restoredValue == null) {
                return;
            }
            if (originalValue == null || restoredValue == null) {
                mismatches.add(path + ": expected " + (originalValue == null ? "null" : "non-null")
                        + " but restored " + (restoredValue == null ? "null" : "non-null"));
                return;
            }
            for (Accessor accessor : accessorsOf(declaredClass)) {
                Object a;
                Object b;
                try {
                    a = accessor.get(originalValue);
                    b = accessor.get(restoredValue);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                            "Failed reading accessor " + accessor.name() + " on " + declaredClass, e);
                }
                compare(accessor.genericType(), a, b, path + "." + accessor.name(), mismatches);
            }
            return;
        }

        mismatches.add(path + ": comparator has no rule for type " + declaredClass
                + " (original=" + describe(originalValue) + ", restored=" + describe(restoredValue) + ")");
    }

    private static String describe(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    // ------------------------------------------------------------------------------------------
    // Shared accessor discovery: unifies true records (accessor = component) and plain immutable
    // classes (accessor = public no-arg JavaBean getter), including inherited in-package getters
    // (CompiledConcept extends CompiledEntity).
    // ------------------------------------------------------------------------------------------

    private interface Accessor {
        String name();

        Type genericType();

        Object get(Object instance) throws ReflectiveOperationException;
    }

    private static final class RecordAccessor implements Accessor {
        private final RecordComponent component;

        RecordAccessor(RecordComponent component) {
            this.component = component;
        }

        @Override
        public String name() {
            return component.getName();
        }

        @Override
        public Type genericType() {
            return component.getGenericType();
        }

        @Override
        public Object get(Object instance) throws ReflectiveOperationException {
            try {
                return component.getAccessor().invoke(instance);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new IllegalStateException("Accessor invocation failed for " + component, e.getCause());
            }
        }
    }

    private static final class MethodAccessor implements Accessor {
        private final Method method;
        private final String propertyName;

        MethodAccessor(Method method) {
            this.method = method;
            this.propertyName = propertyNameOf(method);
        }

        @Override
        public String name() {
            return propertyName;
        }

        @Override
        public Type genericType() {
            return method.getGenericReturnType();
        }

        @Override
        public Object get(Object instance) throws ReflectiveOperationException {
            try {
                return method.invoke(instance);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw new IllegalStateException("Accessor invocation failed for " + method, e.getCause());
            }
        }

        private static String propertyNameOf(Method method) {
            String name = method.getName();
            String stripped = name.startsWith("is") ? name.substring(2) : name.substring(3);
            if (stripped.isEmpty()) {
                return name;
            }
            return Character.toLowerCase(stripped.charAt(0)) + stripped.substring(1);
        }
    }

    private static List<Accessor> accessorsOf(Class<?> type) {
        if (type.isRecord()) {
            List<Accessor> out = new ArrayList<>();
            for (RecordComponent component : type.getRecordComponents()) {
                out.add(new RecordAccessor(component));
            }
            return out;
        }

        List<Accessor> out = new ArrayList<>();
        Set<String> seenMethodNames = new java.util.LinkedHashSet<>();
        Class<?> current = type;
        while (current != null && !current.equals(Object.class)
                && COMPILED_PACKAGE.equals(current.getPackageName())) {
            for (Method method : current.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (method.getParameterCount() != 0) {
                    continue;
                }
                if (method.getReturnType() == void.class) {
                    continue;
                }
                if (method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                String name = method.getName();
                if (name.equals("getClass")) {
                    continue;
                }
                boolean isGetter = (name.startsWith("get") && name.length() > 3)
                        || (name.startsWith("is") && name.length() > 2);
                if (!isGetter) {
                    continue;
                }
                if (method.isAnnotationPresent(Deprecated.class)) {
                    continue;
                }
                if (!seenMethodNames.add(name)) {
                    continue;
                }
                out.add(new MethodAccessor(method));
            }
            current = current.getSuperclass();
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------
    // Type-directed value generator: builds a "maximally populated" instance of any reachable
    // Compiled* type via its widest public constructor, generating a deterministic,
    // distinguishable-from-default value per parameter type.
    // ------------------------------------------------------------------------------------------

    private static final class ReflectiveValueFactory {
        private int stringCounter;
        private int intCounter;
        private long longCounter;
        private double doubleCounter;

        Object generateForClass(Class<?> requestedClass, int domainDepth) {
            Class<?> c = requestedClass == CompiledEntity.class ? CompiledConcept.class : requestedClass;

            if (c == String.class) {
                return "value-" + (++stringCounter);
            }
            if (c == boolean.class || c == Boolean.class) {
                return Boolean.TRUE;
            }
            if (c == int.class || c == Integer.class) {
                return ++intCounter;
            }
            if (c == long.class || c == Long.class) {
                return ++longCounter;
            }
            if (c == double.class || c == Double.class) {
                doubleCounter += 1.5d;
                return doubleCounter;
            }
            if (c.isEnum()) {
                Object[] constants = c.getEnumConstants();
                if (constants == null || constants.length == 0) {
                    throw new IllegalStateException("ReflectiveValueFactory: enum " + c + " has no constants");
                }
                return constants[0];
            }
            if (c == Object.class) {
                // Generic "any value" slots (CompiledSchema.defaultValue, CompiledProcedureVariable
                // .initialValue, CompiledProcedureStep.value): verified the reader round-trips a
                // JSON string scalar back to a plain String via Jackson's convertValue(..., Object
                // .class) for every one of these fields (toDefaultValue/toObjectValue), so a String
                // is faithful here.
                return "value-" + (++stringCounter);
            }
            if (isDomainType(c)) {
                if (domainDepth >= MAX_DEPTH) {
                    // Depth cap reached for a single nested same-family object field: null it out
                    // rather than recursing further (mirrors the List/Map cap below). Every
                    // constructor site that reaches here accepts null for a nested Compiled* field
                    // (verified by inspection -- e.g. CompiledField.schema, CompiledSchema.items).
                    return null;
                }
                return buildDomainInstance(c, domainDepth + 1);
            }
            throw new IllegalStateException(
                    "ReflectiveValueFactory: no generation rule for type " + c.getName()
                            + " -- extend the generator (this usually means a genuinely new kind of field "
                            + "was added and needs a type-directed rule, same as String/boolean/List/Map/etc.)");
        }

        private Object buildDomainInstance(Class<?> c, int domainDepth) {
            // CompiledOrchestration's widest constructor takes BOTH a single `action` and a
            // separate `actions` list; the real writer treats `actions` as authoritative and
            // derives the serialized "action" key from actions.get(0) (see toOrchestrationRules),
            // so an independently-generated standalone `action` would never be expected to survive
            // the round trip -- not a bug, just a legacy-alias field. Keep them consistent here,
            // the same way every real compiler-produced CompiledOrchestration does (via the
            // actions-only constructor delegating action = firstOrNull(actions)).
            if (c == CompiledOrchestration.class) {
                return buildCompiledOrchestration(domainDepth);
            }

            Constructor<?> ctor = widestPublicConstructor(c);
            Type[] paramTypes = ctor.getGenericParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                args[i] = generateValue(paramTypes[i], domainDepth);
            }
            try {
                return ctor.newInstance(args);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "ReflectiveValueFactory: failed constructing " + c.getName() + " via " + ctor, e);
            }
        }

        private Object buildCompiledOrchestration(int domainDepth) {
            String name = (String) generateValue(String.class, domainDepth);
            String condition = (String) generateValue(String.class, domainDepth);
            CompiledOrchestrationTrigger trigger =
                    (CompiledOrchestrationTrigger) generateValue(CompiledOrchestrationTrigger.class, domainDepth);
            CompiledOrchestrationAction sharedAction =
                    (CompiledOrchestrationAction) generateValue(CompiledOrchestrationAction.class, domainDepth);
            return new CompiledOrchestration(name, condition, trigger, sharedAction, List.of(sharedAction));
        }

        private Object generateValue(Type rawType, int domainDepth) {
            Type type = unwrap(rawType);

            if (type instanceof ParameterizedType parameterizedType) {
                Class<?> raw = rawClassOf(parameterizedType);
                if (Collection.class.isAssignableFrom(raw)) {
                    Type elementType = parameterizedType.getActualTypeArguments()[0];
                    if (wouldOverflow(elementType, domainDepth)) {
                        return new ArrayList<>();
                    }
                    List<Object> list = new ArrayList<>();
                    list.add(generateValue(elementType, domainDepth));
                    return list;
                }
                if (Map.class.isAssignableFrom(raw)) {
                    Type keyType = unwrap(parameterizedType.getActualTypeArguments()[0]);
                    Type valueType = parameterizedType.getActualTypeArguments()[1];
                    if (!(keyType instanceof Class<?> keyClass) || keyClass != String.class) {
                        throw new IllegalStateException(
                                "ReflectiveValueFactory: unsupported map key type " + keyType
                                        + " -- only String-keyed maps are used in this codebase");
                    }
                    if (wouldOverflow(valueType, domainDepth)) {
                        return new LinkedHashMap<>();
                    }
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("key-" + (++stringCounter), generateValue(valueType, domainDepth));
                    return map;
                }
                throw new IllegalStateException(
                        "ReflectiveValueFactory: no generation rule for parameterized type " + parameterizedType);
            }

            if (type instanceof Class<?> c) {
                return generateForClass(c, domainDepth);
            }

            throw new IllegalStateException(
                    "ReflectiveValueFactory: no generation rule for Type kind " + type.getClass() + " (" + type + ")");
        }

        private boolean wouldOverflow(Type type, int domainDepth) {
            Type resolved = unwrap(type);
            if (resolved instanceof Class<?> c) {
                Class<?> effective = c == CompiledEntity.class ? CompiledConcept.class : c;
                return isDomainType(effective) && domainDepth >= MAX_DEPTH;
            }
            return false;
        }
    }

    private static Constructor<?> widestPublicConstructor(Class<?> c) {
        Constructor<?>[] constructors = c.getConstructors();
        if (constructors.length == 0) {
            throw new IllegalStateException("ReflectiveValueFactory: " + c + " has no public constructor");
        }
        Constructor<?> widest = constructors[0];
        for (Constructor<?> candidate : constructors) {
            if (candidate.getParameterCount() > widest.getParameterCount()) {
                widest = candidate;
            }
        }
        return widest;
    }

    // ------------------------------------------------------------------------------------------
    // Shared type-shape helpers
    // ------------------------------------------------------------------------------------------

    private static Type unwrap(Type type) {
        if (type instanceof WildcardType wildcardType) {
            Type[] upperBounds = wildcardType.getUpperBounds();
            return upperBounds.length > 0 ? unwrap(upperBounds[0]) : Object.class;
        }
        return type;
    }

    private static Class<?> rawClassOf(ParameterizedType parameterizedType) {
        Type rawType = parameterizedType.getRawType();
        if (rawType instanceof Class<?> c) {
            return c;
        }
        throw new IllegalStateException("ReflectiveValueFactory: non-Class raw type " + rawType);
    }

    private static boolean isScalar(Class<?> c) {
        return c == String.class
                || c == Boolean.class || c == boolean.class
                || c == Integer.class || c == int.class
                || c == Long.class || c == long.class
                || c == Double.class || c == double.class;
    }

    private static boolean isDomainType(Class<?> c) {
        if (c == null) {
            return false;
        }
        if (!COMPILED_PACKAGE.equals(c.getPackageName())) {
            return false;
        }
        if (c.isEnum() || c.isInterface() || c.isPrimitive()) {
            return false;
        }
        if (EXCLUDED_TYPE_NAMES.contains(c.getSimpleName())) {
            return false;
        }
        return true;
    }
}
