package com.finalexec.controlpanel;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.IdentityPackTableNames;
import com.npdev.kernel.dbschema.NpdevTenantTable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

/**
 * Self-healing companion to {@code com.finalexec.auth.IdentityProvisioning.ensureTenantRegistered}
 * (which registers a tenant in real time, the moment its first identity user is provisioned). This
 * runner instead reconciles on every boot, so any tenant whose data predates that real-time hook --
 * or was seeded by a path that writes {@code identity_users} rows directly (profile-driven
 * bootstrap SQL, a seed script, a restored dump) rather than through {@code IdentityProvisioning}
 * -- still ends up with a {@code npdev_tenant} row and is visible/manageable in the ControlPanel's
 * workspace list.
 *
 * <p>Lives in {@code com.finalexec.controlpanel} (alongside {@link SuperUserBootstrapper}, not
 * {@code com.finalexec.npdev.service}) deliberately: that latter package is scanned by an
 * app's {@code build.gradle} against the curated {@code runtime-supported-controllers.json}
 * allowlist and silently excluded from compilation if not listed there; ControlPanel-package
 * classes are unrestricted, same as SuperUserBootstrapper.</p>
 *
 * <p>Fail-open, best-effort: skipped entirely with no physical database (InMemory mode), and any
 * SQL failure is logged, never thrown -- this must never block application startup. The reserved
 * "default" sentinel tenant is deliberately excluded (see {@code TenantRegistryService}).</p>
 */
@Component
public class TenantAutoRegistrationRunner implements ApplicationRunner {

    private final ObjectProvider<DataSource> dataSourceProvider;
    // REG-177: resolved ONCE at construction from the already-available compiledModel, replacing a
    // previous @Value("${npdev.auth.login.user-table:identity_users}") default that (a) hardcoded
    // the pre-versioning literal and (b) was semantically wrong regardless -- this reconciles the
    // BUILT-IN identity pack's own table (the same one IdentityProvisioning writes to), not an
    // app's separately-configurable bonded credential table (LoginController's credentialTable is
    // the right place for that). Empty when this app doesn't compose the identity pack at all.
    private final Optional<IdentityPackTableNames> identityTables;

    public TenantAutoRegistrationRunner(
            ObjectProvider<DataSource> dataSourceProvider,
            CompiledModel compiledModel
    ) {
        this.dataSourceProvider = dataSourceProvider;
        this.identityTables = IdentityPackTableNames.tryResolve(compiledModel);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (identityTables.isEmpty()) {
            return;
        }
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource == null) {
            return;
        }
        String userTable = identityTables.get().usersTable();
        String sql = "INSERT INTO " + NpdevTenantTable.NAME
                + " (tenant_id, display_name, status, created_at_ms) "
                + "SELECT DISTINCT u.tenant_id, u.tenant_id, 'ACTIVE', ? FROM " + userTable + " u "
                + "WHERE u.tenant_id IS NOT NULL AND LOWER(u.tenant_id) <> 'default' "
                + "AND NOT EXISTS (SELECT 1 FROM " + NpdevTenantTable.NAME + " t WHERE t.tenant_id = u.tenant_id)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, Instant.now().toEpochMilli());
            int registered = statement.executeUpdate();
            if (registered > 0) {
                System.out.println("[TenantAutoRegistrationRunner] Registered " + registered
                        + " tenant(s) found in " + userTable + " with no existing " + NpdevTenantTable.NAME
                        + " row (e.g. profile-seeded workspaces created before the ControlPanel registry existed).");
            }
        } catch (SQLException exception) {
            System.out.println("[TenantAutoRegistrationRunner] Skipped: could not reconcile tenant registry ("
                    + exception.getMessage() + ").");
        }
    }
}
