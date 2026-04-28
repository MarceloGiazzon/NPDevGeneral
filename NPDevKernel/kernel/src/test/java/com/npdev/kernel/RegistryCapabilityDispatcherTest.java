package com.npdev.kernel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryCapabilityDispatcherTest {

    @Test
    void invokesRegisteredOperationByContractAndOperationName() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("persistence", "PersistenceCapability", "inmemory", new PersistenceAdapterStub("inmemory"));

        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);
        CapabilityResult result = dispatcher.invoke(
                new CapabilityCall("persistence", "PersistenceCapability", "inmemory", "save", Map.of("email", "a@b.com")),
                Map.of("flow", "CreateUser")
        );

        assertTrue(result.ok());
        assertEquals("inmemory:saved:a@b.com", result.value());
    }

    @Test
    void invokesOperationWithMultipleArguments() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("persistence", "PersistenceCapability", "inmemory", new PersistenceAdapterStub());

        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);
        CapabilityResult result = dispatcher.invoke(
                new CapabilityCall("persistence", "PersistenceCapability", "inmemory", "unique",
                        List.of("User", "email", "a@b.com")),
                Map.of()
        );

        assertTrue(result.ok());
        assertEquals(Boolean.TRUE, result.value());
    }

    @Test
    void returnsContractErrorWhenOperationIsMissing() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("email", "EmailCapability", "smtp", new EmailAdapterStub());

        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);
        CapabilityResult result = dispatcher.invoke(
                new CapabilityCall("email", "EmailCapability", "smtp", "missingOp", Map.of()),
                Map.of()
        );

        assertFalse(result.ok());
        assertEquals(CapabilityErrorKind.CONTRACT, result.error().kind());
        assertEquals("CAPABILITY_CONTRACT_VIOLATION", result.error().code());
    }

    @Test
    void returnsContractErrorWhenOperationExistsOnAdapterButNotInDeclaredContract() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.registerContract(new CapabilityContract(
                "StrictContract",
                List.of(new CapabilityOperationContract("save", List.of("entity"), List.of("entity")))
        ));
        registry.register("strictAlias", "StrictContract", "strict-adapter", new StrictAdapterStub());

        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);
        CapabilityResult result = dispatcher.invoke(
                new CapabilityCall("strictAlias", "StrictContract", "strict-adapter", "extra", List.of()),
                Map.of()
        );

        assertFalse(result.ok());
        assertEquals(CapabilityErrorKind.CONTRACT, result.error().kind());
        assertEquals("CAPABILITY_CONTRACT_VIOLATION", result.error().code());
    }

    @Test
    void selectsAdapterByBindingIdNotByCapabilityOnly() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("persistence", "PersistenceCapability", "inmemory", new PersistenceAdapterStub("inmemory"));
        registry.register("persistence", "PersistenceCapability", "postgres", new PersistenceAdapterStub("postgres"));
        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);

        CapabilityResult first = dispatcher.invoke(
                new CapabilityCall("persistence", "PersistenceCapability", "inmemory", "save", Map.of("email", "a@b.com")),
                Map.of()
        );
        CapabilityResult second = dispatcher.invoke(
                new CapabilityCall("persistence", "PersistenceCapability", "postgres", "save", Map.of("email", "a@b.com")),
                Map.of()
        );

        assertTrue(first.ok());
        assertTrue(second.ok());
        assertEquals("inmemory:saved:a@b.com", first.value());
        assertEquals("postgres:saved:a@b.com", second.value());
    }

    @Test
    void returnsNotFoundWhenBindingAdapterIsMissing() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("persistence", "PersistenceCapability", "inmemory", new PersistenceAdapterStub("inmemory"));
        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);

        CapabilityResult result = dispatcher.invoke(
                new CapabilityCall("persistence", "PersistenceCapability", "postgres", "save",
                        Map.of("email", "a@b.com")),
                Map.of()
        );

        assertFalse(result.ok());
        assertEquals(CapabilityErrorKind.NOT_FOUND, result.error().kind());
        assertEquals("CAPABILITY_BINDING_MISSING", result.error().code());
        assertEquals("Capability binding not found for capability 'persistence' and adapter 'postgres'",
                result.error().message());
    }

    @Test
    void returnsStructuredErrorWhenAdapterThrowsRuntimeException() {
        CapabilityRegistry registry = new CapabilityRegistry();
        registry.register("persistence", "PersistenceCapability", "inmemory", new FailingAdapterStub());
        RegistryCapabilityDispatcher dispatcher = new RegistryCapabilityDispatcher(registry);

        CapabilityResult result = dispatcher.invoke(
                new CapabilityCall("persistence", "PersistenceCapability", "inmemory", "save", Map.of("email", "a@b.com")),
                Map.of()
        );

        assertFalse(result.ok());
        assertEquals("CAPABILITY_INVOCATION_FAILED", result.error().code());
        assertEquals(CapabilityErrorKind.PERMANENT, result.error().kind());
        assertEquals("boom", result.error().message());
    }

    private static final class PersistenceAdapterStub {
        private final String adapterId;

        private PersistenceAdapterStub() {
            this("default");
        }

        private PersistenceAdapterStub(String adapterId) {
            this.adapterId = adapterId;
        }

        public String save(Object payload) {
            return adapterId + ":saved:" + ((Map<?, ?>) payload).get("email");
        }

        public Boolean unique(Object concept, Object field, Object value) {
            return Boolean.TRUE;
        }
    }

    private static final class EmailAdapterStub {
        public String send(Object payload) {
            return "sent";
        }
    }

    private static final class StrictAdapterStub {
        public Object save(Object payload) {
            return payload;
        }

        public Object extra() {
            return "x";
        }
    }

    private static final class FailingAdapterStub {
        public Object save(Object payload) {
            throw new RuntimeException("boom");
        }
    }
}
