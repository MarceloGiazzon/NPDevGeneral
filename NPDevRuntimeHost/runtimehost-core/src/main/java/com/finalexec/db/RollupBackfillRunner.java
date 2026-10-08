package com.finalexec.db;

import com.npdev.kernel.dbschema.NpdevTenantTable;
import com.npdev.kernel.ports.ConceptStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * P8: runs {@link RollupConceptStoreDecorator#backfill} once per tenant at boot, so records that
 * predate a {@code rollups[]} declaration (or its freshly added column) show their real value
 * instead of NULL until a child write happens to touch them.
 *
 * <p>Tenants: the implicit "default" plus every {@code npdev_tenant} row (no physical database --
 * InMemory mode -- means "default" only). Fail-open like {@code TenantAutoRegistrationRunner}: a
 * failure is logged, never thrown -- a rollup that stays NULL must not block startup.</p>
 */
@Component
public class RollupBackfillRunner implements ApplicationRunner {
    private static final Logger LOG = Logger.getLogger(RollupBackfillRunner.class.getName());
    private static final String DEFAULT_TENANT_ID = "default";

    private final ObjectProvider<ConceptStore> conceptStore;
    private final ObjectProvider<DataSource> dataSource;

    public RollupBackfillRunner(ObjectProvider<ConceptStore> conceptStore, ObjectProvider<DataSource> dataSource) {
        this.conceptStore = conceptStore;
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!(conceptStore.getIfAvailable() instanceof RollupConceptStoreDecorator rollups)) {
            return;
        }
        for (String tenantId : tenants()) {
            try {
                int examined = rollups.backfill(tenantId);
                if (examined > 0) {
                    LOG.info("[RollupBackfillRunner] Recomputed rollups on " + examined + " record(s) for tenant " + tenantId);
                }
            } catch (RuntimeException failure) {
                LOG.log(Level.WARNING, "[RollupBackfillRunner] Rollup backfill failed for tenant " + tenantId, failure);
            }
        }
    }

    private Set<String> tenants() {
        Set<String> tenants = new LinkedHashSet<>();
        tenants.add(DEFAULT_TENANT_ID);
        DataSource source = dataSource.getIfAvailable();
        if (source == null) {
            return tenants;
        }
        try (Connection connection = source.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT tenant_id FROM " + NpdevTenantTable.NAME);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String tenantId = rows.getString(1);
                if (tenantId != null && !tenantId.isBlank()) {
                    tenants.add(tenantId.trim());
                }
            }
        } catch (Exception noRegistry) {
            LOG.log(Level.FINE, "[RollupBackfillRunner] No tenant registry; backfilling the default tenant only", noRegistry);
        }
        return tenants;
    }
}
