package com.finalexec.controlpanel;

import com.finalexec.boundary.BoundaryBootException;
import com.finalexec.npdev.service.CredentialRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SEC-8 (docs/ACCEPTED_BOUNDARIES.md B17): the three ways a bootstrap Super User credential can come
 * into being (issued default / hash-supplied / raw-supplied with an entropy floor), plus that a
 * second boot never re-issues once ANY active SUPERUSER credential exists (the claim flow's own
 * precondition -- {@code SuperUserClaimController} is exercised separately, live, since it needs a
 * real HTTP round trip this unit-test layer does not reach).
 */
class SuperUserBootstrapperTest {

    private DataSource dataSource;
    private CredentialRegistryService credentialRegistryService;
    private Path keyFileDir;

    @BeforeEach
    void setUp() throws SQLException, java.io.IOException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE npdev_api_credential (credential_id VARCHAR(64) PRIMARY KEY, "
                    + "key_hash VARCHAR(64), tenant_id VARCHAR(120), actor_id VARCHAR(120), "
                    + "roles VARCHAR(255), status VARCHAR(32), created_at_ms BIGINT)");
        }
        credentialRegistryService = new CredentialRegistryService(new FixedProvider(dataSource));
        keyFileDir = Files.createTempDirectory("sec8-bootstrapper-test-");
    }

    @Test
    void defaultModeIssuesARandomKeyAndWritesTheKeyFile() {
        SuperUserBootstrapper bootstrapper = new SuperUserBootstrapper(
                credentialRegistryService, false, keyFileDir.toString(), "", "");

        bootstrapper.run(new DefaultApplicationArguments());

        Path keyFile = keyFileDir.resolve("SUPER_USER_KEY.txt");
        assertTrue(Files.exists(keyFile), "SUPER_USER_KEY.txt must still be written in default mode");
        assertEquals(1, credentialRegistryService.list().size());
        assertEquals(SuperUserBootstrapper.BOOTSTRAP_ACTOR_ID, credentialRegistryService.list().get(0).get("actorId"));
    }

    @Test
    void hashSuppliedModeInstallsTheCredentialWithoutWritingAKeyFileOrSeeingTheRawKey() {
        String rawKey = "operator-supplied-raw-key-value-123456";
        String hash = CredentialRegistryService.hash(rawKey);
        SuperUserBootstrapper bootstrapper = new SuperUserBootstrapper(
                credentialRegistryService, false, keyFileDir.toString(), hash, "");

        bootstrapper.run(new DefaultApplicationArguments());

        assertFalse(Files.exists(keyFileDir.resolve("SUPER_USER_KEY.txt")),
                "hash-supplied mode must never write a key file -- this app never had the raw key");
        Optional<?> resolved = credentialRegistryService.resolve(rawKey);
        assertTrue(resolved.isPresent(), "the operator's own key (matching the supplied hash) must authenticate");
    }

    @Test
    void rawSuppliedModeAboveTheFloorInstallsTheCredential() {
        String rawKey = "a".repeat(SuperUserBootstrapper.MIN_RAW_KEY_LENGTH);
        SuperUserBootstrapper bootstrapper = new SuperUserBootstrapper(
                credentialRegistryService, false, keyFileDir.toString(), "", rawKey);

        bootstrapper.run(new DefaultApplicationArguments());

        assertFalse(Files.exists(keyFileDir.resolve("SUPER_USER_KEY.txt")));
        assertTrue(credentialRegistryService.resolve(rawKey).isPresent());
    }

    @Test
    void rawSuppliedModeBelowTheFloorRefusesToBoot() {
        String tooShort = "a".repeat(SuperUserBootstrapper.MIN_RAW_KEY_LENGTH - 1);
        SuperUserBootstrapper bootstrapper = new SuperUserBootstrapper(
                credentialRegistryService, false, keyFileDir.toString(), "", tooShort);

        BoundaryBootException exception = assertThrows(BoundaryBootException.class,
                () -> bootstrapper.run(new DefaultApplicationArguments()));
        assertEquals("B17", exception.getViolation().boundaryId());
        assertTrue(exception.getMessage().contains("B17:bootstrap_key_too_short:"), exception.getMessage());
        assertTrue(credentialRegistryService.list().isEmpty(),
                "a refused boot must not leave a half-installed credential behind");
    }

    @Test
    void aSecondBootNeverReIssuesOnceAnyActiveSuperuserCredentialExists() {
        // Simulates the post-claim state: the bootstrap credential was revoked and a NAMED
        // administrator credential (a different actorId) is the one that is now active.
        credentialRegistryService.issue("__system__", "alice", java.util.Set.of("SUPERUSER"));

        SuperUserBootstrapper bootstrapper = new SuperUserBootstrapper(
                credentialRegistryService, false, keyFileDir.toString(), "", "");
        bootstrapper.run(new DefaultApplicationArguments());

        assertFalse(Files.exists(keyFileDir.resolve("SUPER_USER_KEY.txt")),
                "must not issue a fresh bootstrap credential while a claimed administrator is active");
        assertEquals(1, credentialRegistryService.list().size());
    }

    private record FixedProvider(DataSource dataSource) implements ObjectProvider<DataSource> {
        @Override public DataSource getObject(Object... args) { return dataSource; }
        @Override public DataSource getObject() { return dataSource; }
        @Override public DataSource getIfAvailable() { return dataSource; }
        @Override public DataSource getIfAvailable(Supplier<DataSource> defaultSupplier) { return dataSource; }
        @Override public DataSource getIfUnique() { return dataSource; }
        @Override public DataSource getIfUnique(Supplier<DataSource> defaultSupplier) { return dataSource; }
    }

    private static final class SingleConnectionUrlDataSource implements DataSource {
        private final String url;

        private SingleConnectionUrlDataSource(String url) {
            this.url = url;
        }

        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
