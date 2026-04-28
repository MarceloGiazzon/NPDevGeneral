package com.finalexec;

import com.finalexec.npdev.service.RuntimePluginAdapterRegistry;
import com.finalexec.npdev.service.RuntimePluginRealizationProviderCatalog;
import com.finalexec.npdev.service.RuntimePluginRealizationProvider;
import com.finalexec.npdev.service.RuntimePluginRuntimeRefResolver;
import com.npdev.kernel.ports.CapabilityAdapter;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimePluginRuntimeRefResolverTest {

    @Test
    void resolvesRuntimeRefThroughRegisteredProviderBoundary() {
        RuntimePluginRealizationProviderCatalog providerCatalog = new RuntimePluginRealizationProviderCatalog(List.of(
                provider("notificationInProcCapabilityAdapter", new StubCapabilityAdapter())
        ));
        RuntimePluginRuntimeRefResolver resolver = new RuntimePluginRuntimeRefResolver(providerCatalog);

        Object handler = resolver.resolve(contribution("notificationInProcCapabilityAdapter", "notification-inproc"));

        assertEquals(StubCapabilityAdapter.class, handler.getClass());
        assertEquals("runtimeref-provider-catalog", resolver.providerBoundarySummary().boundaryKind());
    }


    @Test
    void resolvesGenericCustomProcedureRuntimeRefThroughRegisteredProviderBoundary() {
        RuntimePluginRuntimeRefResolver resolver = new RuntimePluginRuntimeRefResolver(List.of(
                provider("genericCustomProcedureCapabilityAdapter", new StubCapabilityAdapter())
        ));

        Object handler = resolver.resolve(contribution("genericCustomProcedureCapabilityAdapter", "plugin:custom-procedure"));

        assertEquals(StubCapabilityAdapter.class, handler.getClass());
    }

    @Test
    void rejectsUnknownRuntimeRefOutsideRegisteredProviders() {
        RuntimePluginRuntimeRefResolver resolver = new RuntimePluginRuntimeRefResolver(List.of());

        assertThrows(
                IllegalStateException.class,
                () -> resolver.resolve(contribution("missingRuntimeRef", "notification-inproc"))
        );
    }

    private static RuntimePluginRealizationProvider provider(String runtimeRef, Object handler) {
        return new RuntimePluginRealizationProvider() {
            @Override
            public String runtimeRef() {
                return runtimeRef;
            }

            @Override
            public Object realize() {
                return handler;
            }
        };
    }

    private static RuntimePluginAdapterRegistry.RegisteredAdapterContribution contribution(
            String runtimeRef,
            String adapterId
    ) {
        return new RuntimePluginAdapterRegistry.RegisteredAdapterContribution(
                "notification-inproc-plugin",
                "1.0.0",
                "notification",
                "send",
                adapterId,
                "notification.send",
                "runtimeref",
                runtimeRef
        );
    }

    static final class StubCapabilityAdapter implements CapabilityAdapter {
        @Override
        public String capability() {
            return "notification";
        }

        @Override
        public String adapterId() {
            return "notification-inproc";
        }

        @Override
        public com.npdev.kernel.CapabilityResult invoke(com.npdev.kernel.CapabilityCall call, Map<String, Object> contextState) {
            return com.npdev.kernel.CapabilityResult.success("ok");
        }
    }
}
