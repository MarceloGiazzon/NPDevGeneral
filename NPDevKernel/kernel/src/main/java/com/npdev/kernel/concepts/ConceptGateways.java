package com.npdev.kernel.concepts;

import com.npdev.kernel.inproc.InMemoryConceptStore;
import com.npdev.kernel.ports.AuditLogStore;
import com.npdev.kernel.ports.PermissionEvaluator;
import com.npdev.kernel.ports.TenantIsolationPolicy;

public final class ConceptGateways {

    private ConceptGateways() {
    }

    public static ConceptGateway inMemory() {
        return inMemory(ConceptGatewaySemanticPolicy.noop());
    }

    public static ConceptGateway inMemory(ConceptGatewaySemanticPolicy semanticPolicy) {
        return new DefaultConceptGateway(
                new InMemoryConceptStore(),
                PermissionEvaluator.allowAll(),
                TenantIsolationPolicy.STRICT_EQUALS,
                AuditLogStore.noop(),
                semanticPolicy == null ? ConceptGatewaySemanticPolicy.noop() : semanticPolicy,
                new InMemoryConceptGatewayTraceSink()
        );
    }
}
