package com.npdev.kernel.capabilities;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaticCapabilityBindingResolverTest {
    // static binding wins when no runtime override exists

    @Test
    void shouldResolveMostSpecificBindingFirst() {
        CapabilityBindingManifest manifest = new CapabilityBindingManifest(List.of(
                new CapabilityBindingDescriptor(
                        "emailcapability",
                        "",
                        "smtp-default",
                        "com.example.EmailDefaultAdapter",
                        "dev",
                        ""
                ),
                new CapabilityBindingDescriptor(
                        "emailcapability",
                        "",
                        "smtp-tenant-a",
                        "com.example.EmailTenantAAdapter",
                        "dev",
                        "tenant-a"
                ),
                new CapabilityBindingDescriptor(
                        "emailcapability",
                        "notificationcapability",
                        "smtp-tenant-a-notify",
                        "com.example.EmailTenantANotifyAdapter",
                        "dev",
                        "tenant-a"
                )
        ));

        StaticCapabilityBindingResolver resolver = new StaticCapabilityBindingResolver(manifest);

        CapabilityBindingDescriptor resolved = resolver.require(
                "EmailCapability",
                "NotificationCapability",
                "tenant-a",
                "dev"
        );

        assertEquals("smtp-tenant-a-notify", resolved.adapterId());
    }

    @Test
    void shouldFallBackToCapabilityOnlyBinding() {
        CapabilityBindingManifest manifest = new CapabilityBindingManifest(List.of(
                new CapabilityBindingDescriptor(
                        "persistencecapability",
                        "",
                        "jpa-default",
                        "com.example.JpaAdapter",
                        "",
                        ""
                )
        ));

        StaticCapabilityBindingResolver resolver = new StaticCapabilityBindingResolver(manifest);

        CapabilityBindingDescriptor resolved = resolver.require(
                "PersistenceCapability",
                "CrudCapability",
                "tenant-x",
                "prod"
        );

        assertEquals("jpa-default", resolved.adapterId());
    }
}
