package com.npdev.runtime.support.crud.reflection;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * LIVE-1: duck-typed access to the "extras bag" a generated entity carries (see
 * {@code entity.mustache}) so a field that did not exist when the app was generated -- one a live
 * {@code /model-reload} added to an existing concept -- still has somewhere to live on the typed POJO
 * the generated CRUD services round-trip every record through.
 *
 * <p>The generated entity exposes two public methods and marks them for Jackson:
 * {@code @JsonAnySetter void putExtraField(String, Object)} and
 * {@code @JsonAnyGetter Map<String,Object> extraFields()}, so the same conversions that already turn
 * a store record into the entity ({@code entityFromRecord}) and the entity back into the store map
 * ({@code mapFromEntity}) carry the extra values both ways. Without them a value for a field with no
 * setter was silently DROPPED by {@code ValueCoercionSupport#writeObjectValue}, and a column unknown
 * to the entity made {@code OBJECT_MAPPER.convertValue} throw.
 *
 * <p><b>Why duck typing and not an interface:</b> the entity is also compiled into sealed pack jars
 * ({@code SealedPackJarBuilder}) whose classpath has no runtime-support, so the generated class may
 * not depend on a runtime-support type. The two method names ARE the contract.
 */
public final class ExtraFieldAccess {

    private ExtraFieldAccess() {
    }

    /** True when {@code type} carries the extras bag (both contract methods are public). */
    public static boolean supports(Class<?> type) {
        return type != null && method(type, "putExtraField", String.class, Object.class) != null
                && method(type, "extraFields") != null;
    }

    /** Stores (or, with null, clears) one live-added field's value; false when {@code target} has no bag. */
    public static boolean put(Object target, String name, Object value) {
        if (target == null) {
            return false;
        }
        Method put = method(target.getClass(), "putExtraField", String.class, Object.class);
        if (put == null) {
            return false;
        }
        try {
            put.invoke(target, name, value);
            return true;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("could not write extra field '" + name + "' on "
                    + target.getClass().getName(), failure);
        }
    }

    /** The bag itself, or null when {@code source} has none. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> all(Object source) {
        if (source == null) {
            return null;
        }
        Method get = method(source.getClass(), "extraFields");
        if (get == null) {
            return null;
        }
        try {
            return (Map<String, Object>) get.invoke(source);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("could not read the extra fields of "
                    + source.getClass().getName(), failure);
        }
    }

    /** Public-method lookup by scan (not getMethod), so "absent" is an ordinary answer, not an exception. */
    private static Method method(Class<?> type, String name, Class<?>... parameters) {
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals(name) && java.util.Arrays.equals(candidate.getParameterTypes(), parameters)) {
                return candidate;
            }
        }
        return null;
    }
}
