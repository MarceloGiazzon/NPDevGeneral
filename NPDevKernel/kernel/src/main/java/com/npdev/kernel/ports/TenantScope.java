package com.npdev.kernel.ports;

/**
 * REG-46: the executing tenant, as a distinct type, supplied by the runtime to a tenant-scoped
 * capability adapter.
 *
 * <h2>Why this is a type and not just a {@code String}</h2>
 *
 * <p>The first attempt used {@code String tenantId}, and it could not work. Adapters already expose
 * {@code save(Object concept, Object entity)} / {@code findById(Object concept, Object id)}, and
 * callers pass the concept as a <b>String</b> — {@code save("User", record)}. Adding
 * {@code save(String tenantId, Object entity)} to the same class makes that call bind to the
 * tenant-scoped method instead, because {@code String} is more specific than {@code Object}. The
 * result was silent: records were filed under the wrong concept, and one overload recursed into
 * itself until the stack ran out. The adapter's own long-standing tests caught it.</p>
 *
 * <p>A dedicated type removes the ambiguity by construction. {@code save("User", record)} cannot bind
 * to {@code save(TenantScope, Object)}, so the legacy port keeps working untouched while the scoped
 * port sits beside it.</p>
 *
 * <p>It also documents intent at the call site: a {@code TenantScope} parameter is visibly something
 * the <em>runtime</em> hands you, not a value a model author typed into a flow step. That distinction
 * is the whole security point of REG-46 — an author-supplied tenant would be worse than no scoping at
 * all, because it would look enforced.</p>
 */
public record TenantScope(String tenantId) {

    /** The platform's no-tenant sentinel, which authorization policies already deny under. */
    public static final String DEFAULT_TENANT_ID = "default";

    public TenantScope {
        tenantId = (tenantId == null || tenantId.isBlank()) ? DEFAULT_TENANT_ID : tenantId.trim();
    }

    public static TenantScope of(String tenantId) {
        return new TenantScope(tenantId);
    }

    /** True when {@code record}'s tenant marker is absent (unscoped data) or matches this scope. */
    public boolean covers(String recordTenantId) {
        return recordTenantId == null || recordTenantId.isBlank() || recordTenantId.equals(tenantId);
    }
}
