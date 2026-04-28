package com.npdev.kernel.capabilities;

import java.util.Optional;

public interface CapabilityBindingResolver {

    Optional<CapabilityBindingDescriptor> resolve(
            String capability,
            String capabilityType,
            String tenantId,
            String environment
    );

    default CapabilityBindingDescriptor require(
            String capability,
            String capabilityType,
            String tenantId,
            String environment
    ) {
        return resolve(capability, capabilityType, tenantId, environment)
                .orElseThrow(() -> new IllegalStateException(
                        "No capability binding found for capability='" + capability
                                + "', capabilityType='" + capabilityType
                                + "', tenantId='" + tenantId
                                + "', environment='" + environment + "'"
                ));
    }
}
