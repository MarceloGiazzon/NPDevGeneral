package com.finalexec.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.finalexec.filestore.TenantFileReader;
import com.finalexec.publicread.PublicReadService;
import java.lang.reflect.Field;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Pigmentampas friction #29: one property decides the tenant every tenant-less entry point uses. */
class DefaultTenantTest {

    @AfterEach
    void reset() {
        new DefaultTenant(DefaultTenant.FALLBACK);
    }

    @Test
    void withoutSpringTheHomeTenantIsDev() {
        assertEquals("dev", DefaultTenant.id());
        assertEquals("dev", DefaultTenant.orDefault(null));
        assertEquals("dev", DefaultTenant.orDefault("  "));
        assertEquals("acme", DefaultTenant.orDefault(" acme "));
    }

    @Test
    void theConfiguredValueMovesEveryBlankFallback() {
        new DefaultTenant("mosaic");
        assertEquals("mosaic", DefaultTenant.id());
        assertEquals("mosaic", DefaultTenant.orDefault(null));
        assertEquals("acme", DefaultTenant.orDefault("acme"));
    }

    @Test
    void aBlankPropertyFallsBackToDev() {
        new DefaultTenant(" ");
        assertEquals("dev", DefaultTenant.id());
    }

    @Test
    void theReservedSentinelIsRefused() {
        assertThrows(IllegalStateException.class, () -> new DefaultTenant("default"));
        assertThrows(IllegalStateException.class, () -> new DefaultTenant(" DEFAULT "));
    }

    /** The public-read service used to fall back to "default" -- the empty-gallery bug. */
    @Test
    void publicReadWithNoTenantReadsTheHomeTenant() throws Exception {
        new DefaultTenant("mosaic");
        PublicReadService service = new PublicReadService(() -> null, () -> null,
                new TenantFileReader(() -> null), null, "/api/public", tenant -> true);
        Field tenantId = PublicReadService.class.getDeclaredField("tenantId");
        tenantId.setAccessible(true);
        assertEquals("mosaic", tenantId.get(service));
    }
}
