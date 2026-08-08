package com.npdev.probes.libsig;

import com.google.common.hash.Hashing;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * E8's proof: a user-supplied capability that IMPORTS AND CALLS an external library at runtime.
 *
 * <h2>What was actually missing</h2>
 *
 * <p>{@code build.dependencies[]} is real -- the schema has it, {@code AppDependenciesEmitter} emits
 * it, the {@code npdev-dependencies.gradle} sidecar applies it, and {@code simple-contact-intake}
 * declares Guava, so CI genuinely resolves a third-party dependency. But every test asserted the
 * EMITTED GRADLE TEXT:
 *
 * <pre>
 *   assertTrue(contents.contains("implementation 'com.google.guava:guava:33.0.0-jre'"))
 * </pre>
 *
 * <p>No procedure anywhere imported an external class. The chain <b>declare → import → compile →
 * call at runtime</b> had never run.
 *
 * <h2>Why the assertion is the VALUE, not the compile</h2>
 *
 * <p>A known input has a known SHA-256. Returning the right one proves the library was on the
 * <b>runtime</b> classpath and executed. Compilation alone would pass even if the runtime classpath
 * were wrong -- which is exactly what a {@code compileOnly}-instead-of-{@code implementation}
 * mistake produces: a clean build and a {@code NoClassDefFoundError} the first time the code path
 * runs, in production, months later.
 *
 * <p>{@link #compileOnlyProbe} is the other half, and the more interesting one. It touches a class
 * from a dependency declared {@code compileOnly}, catches the failure, and REPORTS IT AS ABSENT.
 * A green run there means Gradle scoping is behaving: present at compile time, gone at runtime.
 * Asserting an absence is the only way to catch a build that silently promotes every dependency to
 * the runtime classpath.
 */
public final class LibrarySignatureCapability {

    /**
     * SHA-256 of the payload, computed by Guava rather than by {@code java.security.MessageDigest}.
     *
     * <p><b>The returned map contains EXACTLY the concept's own fields, and that is not a style
     * choice.</b> The first CI run of this probe (31272063422) returned {@code library} and
     * {@code libraryLocation} alongside them as diagnostics; the flow persists this map, and the
     * persistence adapter correctly refused:
     *
     * <pre>
     *   Unknown persistence field(s) for table lib_probe_records: [library, libraryLocation].
     *   Allowed runtime fields: [digest, id, payload, version, rowVersion, tenantId]
     * </pre>
     *
     * <p>That refusal is the platform working. What it cost was a red run whose headline said the
     * external library had not been called, when the boot log showed the capability finishing
     * SUCCESS in 5ms with Guava's answer in hand -- the failure was two steps later. Diagnostics that
     * travel with a persisted record are diagnostics that can break persistence, so they go to the
     * log instead, where the CI job already uploads them.
     */
    public Map<String, Object> sign(Map<String, Object> input) {
        String payload = input == null || input.get("payload") == null
                ? "" : String.valueOf(input.get("payload"));

        // Deliberately Guava's Hashing and not MessageDigest: the JDK's own digest would produce the
        // same answer while proving nothing about the dependency, which is precisely the shape of
        // "a fix that silently does nothing" this whole plan warns about.
        String digest = Hashing.sha256().hashString(payload, StandardCharsets.UTF_8).toString();
        System.out.println("[lib-probe] com.google.common.hash.Hashing loaded from "
                + locationOf(Hashing.class));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payload", payload);
        result.put("digest", digest);
        return result;
    }

    /**
     * Touch a class from a {@code compileOnly} dependency and report whether it is there.
     *
     * <p>Reflection, not a direct import, for a reason: a direct reference would be inlined and the
     * class resolved at CLASS LOAD time, so a missing class would fail this whole capability rather
     * than this one operation -- and the failure would look like a broken capability instead of the
     * measurement it is.
     */
    public Map<String, Object> compileOnlyProbe(Map<String, Object> input) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("className", "org.apache.commons.lang3.StringUtils");
        try {
            Class<?> stringUtils = Class.forName("org.apache.commons.lang3.StringUtils");
            result.put("presentAtRuntime", true);
            result.put("location", locationOf(stringUtils));
        } catch (ClassNotFoundException | NoClassDefFoundError expected) {
            // THE EXPECTED OUTCOME. commons-lang3 is declared compileOnly, so it must be absent
            // here; if it is present, Gradle scoping is not doing what the config says.
            result.put("presentAtRuntime", false);
            result.put("location", null);
        }
        return result;
    }

    private static String locationOf(Class<?> type) {
        try {
            return type.getProtectionDomain().getCodeSource().getLocation().toString();
        } catch (RuntimeException unavailable) {
            return "(unknown)";
        }
    }
}
