package com.finalexec.pluginipc;

import com.finalexec.npdev.service.pluginipc.ManifestDrivenJavaSourcePluginHandler;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-218 + REG-223: {@link ManifestDrivenJavaSourcePluginHandler#invoke} had two independent
 * bugs on the isolated {@code plugin:java-source} dispatch path, both fixed by making it match its
 * sibling {@code ArtifactLocalJavaSourceCapabilityHandler}'s already-correct in-process behavior:
 *
 * <p>REG-218: the manifest lookup was {@code entry.methodByOperation().get(call.operation())}, a
 * raw case-exact {@code Map.get()}, but the generator always writes {@code methodByOperation}'s
 * keys lowercased (RuntimeApiEmitter.emitJavaSourceRuntimeRefManifestIfNeeded) -- so a camelCase
 * {@code call.operation()} (every real operation name in this codebase) could never match, and the
 * call always failed with JAVA_SOURCE_OPERATION_NOT_BOUND.
 *
 * <p>REG-223 (found live-verifying REG-218's fix against FiscalImportCapability.importarRomaneio's
 * real 3-arg overload): once the operation-name lookup was fixed, dispatch used a hardcoded
 * {@code pluginClass.getMethod(methodName, Map.class)} + {@code method.invoke(target,
 * call.input())} -- a single-Map-arg method lookup passing only the FIRST call argument. Any
 * capability method with a same-named multi-arg overload (like importarRomaneio's real
 * 1-arg/3-arg pair) silently resolved the WRONG overload and silently dropped every arg past the
 * first, with no error at all -- worse than a hard failure, since it returns a plausible-looking
 * but wrong result.
 *
 * <p>Builds a fully self-contained fixture manifest (no dependency on whatever sample app happens
 * to be assembled) pointing at {@link com.npdev.pluginfixture.CaseSensitivityFixturePlugin}, a real
 * compiled test-classpath class, and swaps it in via the thread context classloader that {@code
 * JavaSourceRuntimeRefManifestLoader}'s no-arg constructor reads from -- isolated from the real
 * classpath via a platform-classloader parent so it cannot shadow or be shadowed by an actually
 * assembled app's own {@code java-source-runtime-refs.json}.</p>
 */
class ManifestDrivenJavaSourcePluginHandlerTest {

    private static final String CAPABILITY = "regTestCapability";
    private static final String MAIN_CLASS = "com.npdev.pluginfixture.CaseSensitivityFixturePlugin";

    @TempDir
    Path tempRoot;

    private ClassLoader originalContextClassLoader;

    @BeforeEach
    void captureOriginalClassLoader() {
        originalContextClassLoader = Thread.currentThread().getContextClassLoader();
    }

    @AfterEach
    void restoreOriginalClassLoader() {
        Thread.currentThread().setContextClassLoader(originalContextClassLoader);
    }

    private void writeManifest(String operationKeyAsWritten, String methodName) throws Exception {
        Path manifestDir = tempRoot.resolve("npdev/plugin-runtime");
        Files.createDirectories(manifestDir);
        String json = """
                {
                  "javaSourcePlugins": [
                    {
                      "capability": "%s",
                      "capabilityType": "RegTestCapability",
                      "adapterId": "plugin:java-source",
                      "pluginId": "reg-218-fixture",
                      "runtimeRef": "reg218FixtureRuntimeRef",
                      "mainClass": "%s",
                      "methodByOperation": { "%s": "%s" }
                    }
                  ]
                }
                """.formatted(CAPABILITY, MAIN_CLASS, operationKeyAsWritten, methodName);
        Files.writeString(manifestDir.resolve("java-source-runtime-refs.json"), json, StandardCharsets.UTF_8);
    }

    private void activateFixtureClassLoader() throws Exception {
        URLClassLoader fixtureLoader = new URLClassLoader(
                new URL[] {tempRoot.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(fixtureLoader);
    }

    @Test
    void bindsACamelCaseOperationAgainstTheGeneratorsLowercasedManifestKey() throws Exception {
        // Real generator output: the JSON key is ALWAYS lowercased, but call.operation() below is
        // the real camelCase operation name every model in this codebase actually uses.
        writeManifest("dothing", "doThing");
        activateFixtureClassLoader();

        ManifestDrivenJavaSourcePluginHandler handler = new ManifestDrivenJavaSourcePluginHandler(null);
        CapabilityCall call = new CapabilityCall(
                CAPABILITY, "RegTestCapability", "plugin:java-source", "doThing",
                List.of(Map.of("probe", "value"))
        );

        CapabilityResult result = handler.invoke(call, Map.of());

        assertTrue(result.ok(), () -> "expected the camelCase operation to bind, got: " + result);
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        assertEquals("CaseSensitivityFixturePlugin", value.get("handledBy"));
    }

    @Test
    void resolvesTheMatchingOverloadByArgCountAndPassesAllArguments() throws Exception {
        // "dothingtwoarg" -> "doThing" in the manifest -- the SAME method name the one-arg test
        // above also resolves, mirroring importarRomaneio's real same-named 1-arg/3-arg overload
        // pair. A hardcoded single-Map-arg lookup would have resolved the ONE-arg overload here too
        // and silently dropped "second".
        writeManifest("dothingtwoarg", "doThing");
        activateFixtureClassLoader();

        ManifestDrivenJavaSourcePluginHandler handler = new ManifestDrivenJavaSourcePluginHandler(null);
        CapabilityCall call = new CapabilityCall(
                CAPABILITY, "RegTestCapability", "plugin:java-source", "doThingTwoArg",
                List.of(Map.of("who", "first"), Map.of("who", "second"))
        );

        CapabilityResult result = handler.invoke(call, Map.of());

        assertTrue(result.ok(), () -> "expected the two-arg overload to bind and invoke, got: " + result);
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) result.value();
        assertEquals("two-arg", value.get("overload"));
        assertEquals(Map.of("who", "first"), value.get("first"));
        assertEquals(Map.of("who", "second"), value.get("second"));
    }

    @Test
    void stillRefusesAnOperationThatIsGenuinelyNotInTheManifest() throws Exception {
        writeManifest("dothing", "doThing");
        activateFixtureClassLoader();

        ManifestDrivenJavaSourcePluginHandler handler = new ManifestDrivenJavaSourcePluginHandler(null);
        CapabilityCall call = new CapabilityCall(
                CAPABILITY, "RegTestCapability", "plugin:java-source", "notARealOperation",
                List.of(Map.of())
        );

        CapabilityResult result = handler.invoke(call, Map.of());

        assertFalse(result.ok());
        assertEquals("JAVA_SOURCE_OPERATION_NOT_BOUND", result.error().code());
    }
}
