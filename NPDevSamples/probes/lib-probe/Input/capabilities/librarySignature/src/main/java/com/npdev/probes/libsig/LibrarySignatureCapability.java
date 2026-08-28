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
 * <p><b>2026-08-28 (SEC-3 Model A, B30): the former {@code compileOnlyProbe} is GONE, and that is
 * a consequence of the security model, not a weakening of E8.</b> The old second half touched a
 * {@code compileOnly} class by REFLECTION ({@code Class.forName}) to assert it was absent at
 * runtime. Reflection-based class loading is exactly the capability-escape the SEC-3 bytecode gate
 * refuses at plugin admission (B30:plugin_bytecode_violation) -- a denylist cannot distinguish a
 * benign measurement from a hostile load, so the probe's reflective half cannot mount a plugin
 * anymore. E8's own point -- "a user capability calls an external library at runtime" -- survives
 * in full below via {@link #sign} (Guava on the runtime classpath, real SHA-256 returned); only the
 * compileOnly runtime-absence measurement is lost, and a non-plugin mechanism (a boot-time
 * classpath scan by platform code, not plugin code) would be needed to restore it.
 */
public final class LibrarySignatureCapability {

    /**
     * SHA-256 of the payload, computed by Guava rather than by {@code java.security.MessageDigest}.
     *
     * <p><b>The returned map contains EXACTLY the concept's own fields.</b> The first CI run of
     * this probe (31272063422) returned {@code library} and {@code libraryLocation} alongside them
     * as diagnostics; the flow persists this map, and the persistence adapter correctly refused:
     *
     * <pre>
     *   Unknown persistence field(s) for table lib_probe_records: [library, libraryLocation].
     *   Allowed runtime fields: [digest, id, payload, version, rowVersion, tenantId]
     * </pre>
     *
     * <p>That refusal is the platform working. Diagnostics travel with the LOG instead, via
     * {@code System.out.println} -- the console stream is exempted from the SEC-3 {@code java/io/}
     * ban (B30; {@code java/io/PrintStream} is console output, not filesystem IO), so the boot log
     * the CI job uploads carries the digest evidence.
     */
    public Map<String, Object> sign(Map<String, Object> input) {
        String payload = input == null || input.get("payload") == null
                ? "" : String.valueOf(input.get("payload"));

        // Deliberately Guava's Hashing and not MessageDigest: the JDK's own digest would produce the
        // same answer while proving nothing about the dependency, which is precisely the shape of
        // "a fix that silently does nothing" this whole plan warns about.
        String digest = Hashing.sha256().hashString(payload, StandardCharsets.UTF_8).toString();
        System.out.println("[lib-probe] Guava sha256('" + payload + "') = " + digest);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("payload", payload);
        result.put("digest", digest);
        return result;
    }
}