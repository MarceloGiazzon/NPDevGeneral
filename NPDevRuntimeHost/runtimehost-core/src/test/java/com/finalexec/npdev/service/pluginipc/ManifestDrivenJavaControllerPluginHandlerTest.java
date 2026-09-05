package com.finalexec.npdev.service.pluginipc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B30/SEC-9: exercises {@link ManifestDrivenJavaControllerPluginHandler}'s reflective dispatch
 * directly (no real child process/pool needed -- the handler itself has no IPC-specific logic, it
 * only needs a {@link CapabilityCall} and the manifest resource on the test classpath, at
 * {@code src/test/resources/npdev/plugin-runtime/plugin-controller-routes.json}, pointing at
 * {@link com.finalexec.npdev.service.pluginipc.fixtures.SampleControllerForHandlerTest}).
 */
class ManifestDrivenJavaControllerPluginHandlerTest {

    private final ManifestDrivenJavaControllerPluginHandler handler = new ManifestDrivenJavaControllerPluginHandler(null);

    @Test
    void invokesANoArgMethodAndWrapsThePlainReturnValueAs200() {
        CapabilityCall call = new CapabilityCall("sampleController", null, "plugin:java-controller", "ping",
                (Object) Map.of("pathVariables", Map.of(), "queryParams", Map.of()));

        CapabilityResult result = handler.invoke(call, Map.of());

        assertTrue(result.ok(), () -> result.error() == null ? "" : result.error().message());
        Map<?, ?> envelope = (Map<?, ?>) result.value();
        assertEquals(200, envelope.get("status"));
        assertEquals(Map.of("ok", true), envelope.get("body"));
    }

    @Test
    void bindsPathVariableAndOptionalRequestParamByExplicitName() {
        CapabilityCall call = new CapabilityCall("sampleController", null, "plugin:java-controller", "getUser",
                (Object) Map.of("pathVariables", Map.of("id", "42"), "queryParams", Map.of("verbose", List.of("true"))));

        CapabilityResult result = handler.invoke(call, Map.of());

        assertTrue(result.ok(), () -> result.error() == null ? "" : result.error().message());
        Map<?, ?> envelope = (Map<?, ?>) result.value();
        assertEquals("user-42-true", envelope.get("body"));
    }

    @Test
    void bindsRequestBodyAndUnwrapsAResponseEntity() {
        CapabilityCall call = new CapabilityCall("sampleController", null, "plugin:java-controller", "createUser",
                (Object) Map.of("pathVariables", Map.of(), "queryParams", Map.of(), "body", Map.of("name", "Ada")));

        CapabilityResult result = handler.invoke(call, Map.of());

        assertTrue(result.ok(), () -> result.error() == null ? "" : result.error().message());
        Map<?, ?> envelope = (Map<?, ?>) result.value();
        assertEquals(201, envelope.get("status"));
        assertEquals(Map.of("name", "Ada"), envelope.get("body"));
    }

    @Test
    void failsCleanlyWhenNoManifestEntryMatchesTheCapability() {
        CapabilityCall call = new CapabilityCall("unknownCapability", null, "plugin:java-controller", "ping", (Object) Map.of());

        CapabilityResult result = handler.invoke(call, Map.of());

        assertFalse(result.ok());
        assertEquals("PLUGIN_CONTROLLER_ROUTE_NOT_FOUND", result.error().code());
    }

    @Test
    void failsCleanlyWhenTheMatchedMethodDoesNotExist() {
        CapabilityCall call = new CapabilityCall("sampleController", null, "plugin:java-controller", "doesNotExist", (Object) Map.of());

        CapabilityResult result = handler.invoke(call, Map.of());

        assertFalse(result.ok());
        assertEquals("PLUGIN_CONTROLLER_METHOD_NOT_FOUND", result.error().code());
    }
}
