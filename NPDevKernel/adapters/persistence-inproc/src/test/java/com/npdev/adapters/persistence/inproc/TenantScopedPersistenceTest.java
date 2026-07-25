package com.npdev.adapters.persistence.inproc;

import com.npdev.kernel.ports.TenantScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REG-46 — the persistence capability is tenant-scoped.
 *
 * <p>Before this, {@code PersistenceCapabilityContract} had no tenant parameter at all, so a flow step
 * as ordinary as {@code persistence.findById(concept: Order, id: $input.orderId)} returned any
 * tenant's order — while generated CRUD, going through {@code ConceptGateway}, was tenant- and
 * row-scoped. Two persistence routes, two different guarantees.</p>
 *
 * <p>These tests drive the <b>in-memory</b> adapter because it had the identical hole: this was a gap
 * in the port, not a difference between backends. Dev and production disagreeing about who can read
 * what is how an isolation bug stays invisible until deployment.</p>
 */
class TenantScopedPersistenceTest {

    /**
     * Seeds through the CONCEPT-aware legacy save so each row lands under "Order", with its tenant
     * marker in the payload. The port's own {@code save(TenantScope, entity)} files under the implicit
     * "default" concept (mirroring the legacy one-arg {@code save(entity)}), which is exercised
     * separately in {@link #savingUnderATenantStampsOwnershipRatherThanTrustingThePayload()} -- keeping
     * the two concerns apart, so a failure here means tenant FILTERING broke and nothing else.
     */
    private static InMemoryPersistenceCapabilityAdapter seeded() {
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter();
        adapter.save("Order", Map.of("id", "order-1", "tenantId", "tenant-a", "sku", "SKU-A", "total", 100));
        adapter.save("Order", Map.of("id", "order-2", "tenantId", "tenant-b", "sku", "SKU-B", "total", 200));
        return adapter;
    }

    @Test
    void findByIdDoesNotReachAcrossTenants() {
        InMemoryPersistenceCapabilityAdapter adapter = seeded();

        assertNotNull(adapter.findById(TenantScope.of("tenant-a"), "Order", "order-1"), "own row is readable");
        assertNull(adapter.findById(TenantScope.of("tenant-b"), "Order", "order-1"),
                "tenant B must not read tenant A's order even knowing its id");
    }

    @Test
    void queryReturnsOnlyTheCallersRows() {
        InMemoryPersistenceCapabilityAdapter adapter = seeded();

        Object rowsForA = adapter.query(TenantScope.of("tenant-a"), "Order", Map.of());
        assertTrue(rowsForA instanceof List<?>);
        assertEquals(1, ((List<?>) rowsForA).size(), "an unfiltered query must still be tenant-filtered");
    }

    @Test
    void deleteCannotRemoveAnotherTenantsRowNorRevealThatItExists() {
        InMemoryPersistenceCapabilityAdapter adapter = seeded();

        // Reports "nothing deleted" rather than deleting -- and the same answer it would give for an
        // id that does not exist at all, so it is not an existence oracle either.
        assertEquals(false, adapter.delete(TenantScope.of("tenant-b"), "Order", "order-1"));
        assertEquals(false, adapter.delete(TenantScope.of("tenant-b"), "Order", "no-such-id"));
        assertNotNull(adapter.findById(TenantScope.of("tenant-a"), "Order", "order-1"), "the row must survive");
    }

    @Test
    void uniquenessIsAskedOfTheCallersOwnRows() {
        // Both directions matter. Asking globally would leak that another tenant holds the value AND
        // refuse a value this tenant is entitled to use.
        InMemoryPersistenceCapabilityAdapter adapter = seeded();

        assertEquals(true, adapter.exists(TenantScope.of("tenant-a"), "Order", "sku", "SKU-A"));
        assertEquals(false, adapter.exists(TenantScope.of("tenant-b"), "Order", "sku", "SKU-A"));
        assertEquals(true, adapter.unique(TenantScope.of("tenant-b"), "Order", "sku", "SKU-A"),
                "tenant B may use a SKU that only tenant A has taken");
    }

    @Test
    void savingUnderATenantStampsOwnershipRatherThanTrustingThePayload() {
        // The tenant comes from the runtime, so a payload claiming another tenant must not win --
        // otherwise the scoping would be advisory.
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter();
        adapter.save(TenantScope.of("tenant-a"), Map.of("id", "order-9", "tenantId", "tenant-b", "sku", "SKU-X"));

        // The port's save files under the implicit "default" concept, like the legacy one-arg save.
        assertNotNull(adapter.findById(TenantScope.of("tenant-a"), "default", "order-9"),
                "stored under the EXECUTING tenant, not the one the payload claimed");
        assertNull(adapter.findById(TenantScope.of("tenant-b"), "default", "order-9"),
                "the payload's claimed tenant must not decide ownership");
    }

    @Test
    void recordsWithNoTenantMarkerStayVisibleToEveryone() {
        // Matches the Postgres adapter's rule of only scoping tables that actually carry a tenant
        // column: untagged data predates scoping and must not vanish when the port is upgraded.
        InMemoryPersistenceCapabilityAdapter adapter = new InMemoryPersistenceCapabilityAdapter();
        adapter.save("Order", Map.of("id", "legacy-1", "sku", "SKU-LEGACY"));  // no tenant marker

        assertNotNull(adapter.findById(TenantScope.of("tenant-a"), "Order", "legacy-1"));
        assertNotNull(adapter.findById(TenantScope.of("tenant-b"), "Order", "legacy-1"));
    }

    @Test
    void theUnscopedPortStillWorksForAdaptersThatHaveNotMigrated() {
        // PersistenceCapabilityContract is kept, not replaced: the arities differ, so reflective
        // resolution stays unambiguous and an un-migrated adapter keeps functioning.
        InMemoryPersistenceCapabilityAdapter adapter = seeded();

        assertNotNull(adapter.findById("Order", "order-1"));
        assertFalse(((List<?>) adapter.query("Order", Map.of())).isEmpty());
    }
}
