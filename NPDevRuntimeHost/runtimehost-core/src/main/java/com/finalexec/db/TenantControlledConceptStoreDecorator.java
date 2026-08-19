package com.finalexec.db;

import com.npdev.kernel.concepts.ConceptListSlice;
import com.npdev.kernel.concepts.ConceptPage;
import com.npdev.kernel.concepts.ConceptQuery;
import com.npdev.kernel.concepts.ConceptRecord;
import com.npdev.kernel.ports.ConceptStore;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * The genuinely LIVE per-request adapter switch: a concept whose generation-time
 * {@code persistence.adapter} resolves to {@code "tenant"} delegates through
 * {@link AuditingConceptStoreDecorator} only when the REQUEST's own tenant has
 * {@code npdev_tenant.persistence_mode = 'audited'} -- checked fresh on every call, not baked in at
 * generation time. Unlike {@code AuditingConceptStoreDecorator} selected directly via
 * {@code persistence.adapter="audited"} (a fixed, permanent wrap for every request regardless of
 * tenant), an admin toggles a SINGLE tenant into/out of audited mode via
 * {@code PUT /api/admin/tenants/{tenantId}/persistence-mode} and every subsequent request for that
 * tenant (only) is affected immediately -- no regenerate, no reboot.
 *
 * <p>Fails safe (not-audited) on any DB error or missing DataSource, matching every other
 * fail-open/fail-safe runtime lookup in this codebase (e.g. {@code TenantRegistryService.isActive}).
 * The delegate's data/correctness are identical either way -- this only changes whether accesses are
 * additionally logged.</p>
 */
public final class TenantControlledConceptStoreDecorator implements ConceptStore {
    private final ConceptStore delegate;
    private final String conceptName;
    private final ObjectProvider<DataSource> dataSourceProvider;

    public TenantControlledConceptStoreDecorator(
            ConceptStore delegate, String conceptName, ObjectProvider<DataSource> dataSourceProvider) {
        this.delegate = delegate;
        this.conceptName = conceptName;
        this.dataSourceProvider = dataSourceProvider;
    }

    @Override
    public Optional<ConceptRecord> findById(String tenantId, String conceptName, String id) {
        return effectiveStore(tenantId).findById(tenantId, conceptName, id);
    }

    /** B18 (Move 9 A2, {@code docs/ACCEPTED_BOUNDARIES.md}): same reasoning as {@link #query}'s own
     * override -- must forward to the resolved delegate's real locking read, not fall through to
     * {@link ConceptStore}'s unlocked default. */
    @Override
    public Optional<ConceptRecord> findByIdForUpdate(String tenantId, String conceptName, String id) {
        return effectiveStore(tenantId).findByIdForUpdate(tenantId, conceptName, id);
    }

    @Override
    public List<ConceptRecord> findAll(String tenantId, String conceptName) {
        return effectiveStore(tenantId).findAll(tenantId, conceptName);
    }

    /** RUN-1 (R8a): forwards to the resolved delegate's own pushdown override (see
     *  {@code AuditingConceptStoreDecorator}'s twin) rather than {@link ConceptStore}'s
     *  fetch-all-then-trim default. */
    @Override
    public ConceptListSlice<ConceptRecord> findAllCapped(String tenantId, String conceptName, int maxRows) {
        return effectiveStore(tenantId).findAllCapped(tenantId, conceptName, maxRows);
    }

    @Override
    public ConceptRecord save(ConceptRecord record) {
        return effectiveStore(record == null ? null : record.tenantId()).save(record);
    }

    @Override
    public void deleteById(String tenantId, String conceptName, String id) {
        effectiveStore(tenantId).deleteById(tenantId, conceptName, id);
    }

    /** R5.4: same reasoning as {@link #query} immediately above -- forwards to the resolved
     *  delegate's own real {@code deleted_at}-clearing override, not {@link ConceptStore}'s
     *  {@code false} ("not supported") default. */
    @Override
    public boolean restore(String tenantId, String conceptName, String id) {
        return effectiveStore(tenantId).restore(tenantId, conceptName, id);
    }

    /** LNCH-5: forwards to the resolved delegate's own SQL push-down (see AuditingConceptStoreDecorator's twin). */
    @Override
    public ConceptPage query(String tenantId, String conceptName, ConceptQuery query) {
        return effectiveStore(tenantId).query(tenantId, conceptName, query);
    }

    /** Move 10 B1 (LC-B1): same reasoning as {@link #query} immediately above -- forwards to the
     *  resolved delegate's own real SQL {@code GROUP BY}, not {@link ConceptStore}'s
     *  fetch-all-then-aggregate-in-the-JVM default. */
    @Override
    public com.npdev.kernel.concepts.ConceptAggregateResult aggregate(
            String tenantId, String conceptName, com.npdev.kernel.concepts.ConceptAggregateQuery query) {
        return effectiveStore(tenantId).aggregate(tenantId, conceptName, query);
    }

    /** R5.2 (RUN-1 item 4): same reasoning as {@link #query} immediately above -- forwards to the
     *  resolved delegate's own pushdown override, not {@link ConceptStore}'s
     *  fetch-all-then-scan-in-the-JVM default. */
    @Override
    public boolean existsUnique(String tenantId, String conceptName, List<String> fieldNames, List<Object> values, String excludeId) {
        return effectiveStore(tenantId).existsUnique(tenantId, conceptName, fieldNames, values, excludeId);
    }

    private ConceptStore effectiveStore(String tenantId) {
        return isAuditedForTenant(tenantId) ? new AuditingConceptStoreDecorator(delegate, conceptName) : delegate;
    }

    private boolean isAuditedForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return false;
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return false;
        }
        String sql = "SELECT persistence_mode FROM npdev_tenant WHERE tenant_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && "audited".equalsIgnoreCase(resultSet.getString("persistence_mode"));
            }
        } catch (SQLException exception) {
            return false;
        }
    }
}
