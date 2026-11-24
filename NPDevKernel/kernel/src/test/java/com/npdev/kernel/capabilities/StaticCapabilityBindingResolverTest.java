package com.npdev.kernel.capabilities;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaticCapabilityBindingResolverTest {
    // static binding wins when no runtime override exists

    @Test
    void aCapabilityOfABuiltInTypeUnderAnotherNameTakesTheBuiltInsBinding() {
        // REG-359: "notificationAudit" (type NotificationCapability) has no binding of its own; the manifest lists
        // the built-in "notification".
        CapabilityBindingManifest manifest = new CapabilityBindingManifest(List.of(
                new CapabilityBindingDescriptor(
                        "notification", "notificationcapability", "notification-inproc",
                        "com.example.InProcNotificationAdapter", "", "")
        ));
        StaticCapabilityBindingResolver resolver = new StaticCapabilityBindingResolver(manifest);

        assertEquals("notification-inproc",
                resolver.require("notificationAudit", "NotificationCapability", "", "dev").adapterId());
        assertEquals("notification-inproc",
                resolver.require("notification", "NotificationCapability", "", "dev").adapterId());
    }

    @Test
    void aCapabilityWhoseTypeIsNotOfTheBuiltInFormStaysUnresolved() {
        CapabilityBindingManifest manifest = new CapabilityBindingManifest(List.of(
                new CapabilityBindingDescriptor(
                        "notification", "notificationcapability", "notification-inproc",
                        "com.example.InProcNotificationAdapter", "", "")
        ));
        StaticCapabilityBindingResolver resolver = new StaticCapabilityBindingResolver(manifest);

        assertEquals(java.util.Optional.empty(), resolver.resolve("vault", "VaultCapability", "", "dev"));
        assertEquals(java.util.Optional.empty(), resolver.resolve("selfApi", "HttpConnector", "", "dev"));
    }

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
