package com.npdev.runtime.support.crud.bonds;

import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledReferenceSemantics;

/**
 * Split out of {@code GeneratedCrudRuntimeSupport} (2.B.3): the small string helpers around
 * reference/bond field resolution -- the target concept name a reference field points at, and
 * normalizing a caller's tenant id for the cross-tenant bond-target existence check.
 */
public final class BondNamingSupport {

    private BondNamingSupport() {
    }

    public static String referenceTargetName(CompiledField referenceField) {
        if (referenceField == null) {
            return "";
        }
        CompiledReferenceSemantics semantics = referenceField.getReferenceSemantics();
        if (semantics != null && semantics.getTarget() != null && !semantics.getTarget().isBlank()) {
            return semantics.getTarget();
        }
        return referenceField.getReferenceTarget();
    }

    public static String normalizeTenantForBondCheck(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? "default" : tenantId.trim();
    }
}
