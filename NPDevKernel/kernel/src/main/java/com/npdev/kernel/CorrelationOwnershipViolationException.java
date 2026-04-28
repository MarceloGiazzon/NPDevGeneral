package com.npdev.kernel;

public final class CorrelationOwnershipViolationException extends RuntimeException {
    private final String correlationId;
    private final String ownerTenantId;
    private final String requesterTenantId;

    public CorrelationOwnershipViolationException(
            String correlationId,
            String ownerTenantId,
            String requesterTenantId
    ) {
        super("Correlation '" + correlationId + "' is owned by tenant '" + ownerTenantId
                + "' and cannot be used by tenant '" + requesterTenantId + "'");
        this.correlationId = correlationId;
        this.ownerTenantId = ownerTenantId;
        this.requesterTenantId = requesterTenantId;
    }

    public String correlationId() {
        return correlationId;
    }

    public String ownerTenantId() {
        return ownerTenantId;
    }

    public String requesterTenantId() {
        return requesterTenantId;
    }
}
