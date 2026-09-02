package com.finalexec.npdev.service;

import com.npdev.kernel.ports.ApiKeyCredentialResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the credential-issuance security contract: the raw key resolves correctly once, only a hash
 * is ever persisted (never the raw key, never re-derivable), revocation actually denies, and an
 * unknown key resolves to nothing rather than throwing.
 */
class CredentialRegistryServiceTest {

    private DataSource dataSource;
    private CredentialRegistryService service;

    @BeforeEach
    void setUp() throws SQLException {
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        dataSource = new SingleConnectionUrlDataSource(url);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE npdev_api_credential (credential_id VARCHAR(64) PRIMARY KEY, "
                    + "key_hash VARCHAR(64), tenant_id VARCHAR(120), actor_id VARCHAR(120), "
                    + "roles VARCHAR(255), status VARCHAR(32), created_at_ms BIGINT)");
        }
        service = new CredentialRegistryService(new FixedProvider(dataSource));
    }

    @Test
    void issuedKeyResolvesToTheCorrectPrincipal() {
        Map<String, Object> issued = service.issue("acme", "alice", Set.of("ADMIN"));
        String rawKey = (String) issued.get("apiKey");

        Optional<ApiKeyCredentialResolver.Principal> resolved = service.resolve(rawKey);
        assertTrue(resolved.isPresent());
        assertEquals("acme", resolved.get().tenantId());
        assertEquals("alice", resolved.get().actorId());
        assertEquals(Set.of("ADMIN"), resolved.get().roles());
    }

    @Test
    void rawKeyIsNeverPersisted() throws SQLException {
        Map<String, Object> issued = service.issue("acme", "alice", Set.of("ADMIN"));
        String rawKey = (String) issued.get("apiKey");

        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT key_hash FROM npdev_api_credential")) {
            assertTrue(rs.next());
            String storedHash = rs.getString(1);
            assertNotEquals(rawKey, storedHash, "the raw key must never be stored verbatim");
            assertEquals(64, storedHash.length(), "expected a SHA-256 hex digest");
        }
    }

    @Test
    void unknownKeyResolvesToEmpty() {
        assertTrue(service.resolve("npk_totally-made-up").isEmpty());
        assertTrue(service.resolve(null).isEmpty());
    }

    @Test
    void revokedCredentialNoLongerResolves() {
        Map<String, Object> issued = service.issue("acme", "alice", Set.of("ADMIN"));
        String rawKey = (String) issued.get("apiKey");
        String credentialId = (String) issued.get("credentialId");

        assertTrue(service.resolve(rawKey).isPresent());
        service.revoke(credentialId);
        assertFalse(service.resolve(rawKey).isPresent());
    }

    @Test
    void listNeverExposesRawKeyOrHash() {
        service.issue("acme", "alice", Set.of("ADMIN"));
        List<Map<String, Object>> rows = service.list();
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).containsKey("apiKey"));
        assertFalse(rows.get(0).containsKey("keyHash"));
    }

    @Test
    void revokingUnknownCredentialIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.revoke("ghost"));
    }

    @Test
    void blankTenantOrActorIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.issue("", "alice", Set.of("ADMIN")));
        assertThrows(IllegalArgumentException.class, () -> service.issue("acme", null, Set.of("ADMIN")));
    }

    /** SEC-8 (B17): a deployment-supplied bootstrap key -- the caller already has the raw key and
     * hands this service only its hash; the raw key never passes through issueWithKnownHash at all. */
    @Test
    void issueWithKnownHashResolvesForTheMatchingRawKeyOnly() {
        String rawKey = "operator-generated-key-abcdef123456";
        String hash = CredentialRegistryService.hash(rawKey);

        service.issueWithKnownHash("acme", "bootstrap", Set.of("SUPERUSER"), hash);

        Optional<ApiKeyCredentialResolver.Principal> resolved = service.resolve(rawKey);
        assertTrue(resolved.isPresent());
        assertEquals("bootstrap", resolved.get().actorId());
        assertTrue(service.resolve("some-other-key").isEmpty());
    }

    @Test
    void hashIsStableAndMatchesTheDocumentedAlgorithm() {
        // SHA-256("abc") -- a well-known test vector, pins the exact algorithm/encoding.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CredentialRegistryService.hash("abc"));
    }

    @Test
    void credentialIdForKeyFindsExactlyTheCredentialThatKeyAuthenticates() {
        Map<String, Object> issuedA = service.issue("acme", "alice", Set.of("SUPERUSER"));
        Map<String, Object> issuedB = service.issue("acme", "bob", Set.of("SUPERUSER"));

        Optional<String> foundA = service.credentialIdForKey((String) issuedA.get("apiKey"));
        assertTrue(foundA.isPresent());
        assertEquals(issuedA.get("credentialId"), foundA.get());
        assertNotEquals(issuedB.get("credentialId"), foundA.get());

        assertTrue(service.credentialIdForKey("never-issued").isEmpty());
    }

    @Test
    void credentialIdForKeyDoesNotFindARevokedCredential() {
        Map<String, Object> issued = service.issue("acme", "alice", Set.of("SUPERUSER"));
        service.revoke((String) issued.get("credentialId"));

        assertTrue(service.credentialIdForKey((String) issued.get("apiKey")).isEmpty());
    }

    /** SEC-8 (B17): the whole point of lookupStatus -- unlike resolve(), it must still find a
     * REVOKED row, so the auth filter can tell "revoked" apart from "never issued". */
    @Test
    void lookupStatusDistinguishesRevokedFromNeverIssued() {
        Map<String, Object> issued = service.issue("acme", "alice", Set.of("SUPERUSER"));
        String rawKey = (String) issued.get("apiKey");

        assertEquals(Optional.of(CredentialRegistryService.Status.ACTIVE), service.lookupStatus(rawKey));

        service.revoke((String) issued.get("credentialId"));
        assertEquals(Optional.of(CredentialRegistryService.Status.REVOKED), service.lookupStatus(rawKey));

        assertTrue(service.lookupStatus("never-issued-at-all").isEmpty());
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
