package com.npdev.kernel.ports;

/**
 * REG-46: version 2 of the persistence capability port — the same operations, with the executing
 * tenant supplied by the runtime.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@link PersistenceCapabilityContract} has no tenant parameter anywhere, so an adapter
 * implementing it <em>cannot</em> scope by tenant even if it wants to. That left a generated app with
 * two persistence routes carrying different guarantees:</p>
 *
 * <ul>
 *   <li>generated CRUD → {@code ConceptGateway}: tenant-scoped <b>and</b> row-scoped; and</li>
 *   <li>a flow's {@code persistence} capability step → the adapter: <b>unscoped</b>.</li>
 * </ul>
 *
 * <p>A step as ordinary as {@code persistence.findById(concept: Order, id: $input.orderId)} therefore
 * returned any tenant's order. The in-memory adapter had the same hole, so this was a gap in the port
 * rather than a difference between backends.</p>
 *
 * <h2>Why the tenant is a parameter the RUNTIME supplies, not one the model declares</h2>
 *
 * <p>This is the part that matters, and it is why the port is versioned rather than edited.</p>
 *
 * <p>{@code RegistryCapabilityDispatcher} resolves an operation reflectively by <b>name and argument
 * count</b>. If the tenant were simply appended to the existing signatures, every model that declares
 * a two-argument {@code persistence.findById} would fail to resolve at runtime — and, far worse, a
 * model author would be the one choosing the tenant value, which is a weaker position than having no
 * scoping at all: an unscoped read is at least obviously unscoped, whereas an author-supplied tenant
 * <em>looks</em> enforced.</p>
 *
 * <p>So the dispatcher detects an adapter implementing this interface and <b>prepends a
 * {@link TenantScope}</b> (from the flow's {@code tenantId} state, i.e. the authenticated
 * {@code ExecutionContext}) to the declared arguments. See {@link TenantScope} for why the tenant is
 * a distinct type rather than a bare {@code String}. Models keep their existing step declarations unchanged; the tenant is
 * never author-writable.</p>
 *
 * <p>{@link PersistenceCapabilityContract} remains for adapters that have not migrated. An adapter
 * may implement both: the two arities are distinct, so reflective resolution stays unambiguous.</p>
 */
public interface TenantScopedPersistenceCapabilityContract {

    Object save(TenantScope scope, Object entity);

    Object findById(TenantScope scope, Object concept, Object id);

    Object query(TenantScope scope, Object concept, Object criteria);

    Object delete(TenantScope scope, Object concept, Object id);

    Object exists(TenantScope scope, Object concept, Object field, Object value);

    Object unique(TenantScope scope, Object concept, Object field, Object value);
}
