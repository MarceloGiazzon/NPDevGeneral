package com.finalexec.agent;

import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledModel;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * AGENT-1 (A6.1): reads/writes the identity pack's {@code identity::ExternalIdentity} table --
 * the same table Google/GitHub OAuth linking already uses ({@code OAuthGoogleAuthService}), keyed
 * by {@code (provider, provider_subject)}. {@code provider} here is {@code "telegram"} /
 * {@code "whatsapp"}, never {@code "google"}/{@code "github"} (those rows are owned by the OAuth
 * flow). A row stores the link between an outside identity and an {@code identity::User}.
 *
 * <p>Written fresh rather than extracted from {@code OAuthGoogleAuthService} (the plan's original
 * suggestion) to avoid touching that already-working, security-sensitive login path for this --
 * same SQL shape, same table, zero shared mutable state, and {@code OAuthGoogleAuthServiceTest}
 * stays untouched and green by construction.
 */
public final class ExternalIdentityStore {

    public record ActiveUser(String username, String tenantId) {
    }

    /** identity::ExternalIdentity.providerSubject and identity::User.username maxLength (pack.json). */
    static final int MAX_SUBJECT_LENGTH = 255;
    static final int MAX_USERNAME_LENGTH = 120;
    static final int MAX_TENANT_ID_LENGTH = 128;

    private final DataSource dataSource;

    public ExternalIdentityStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Table names for the two identity-pack concepts this store touches, or {@code null} if the
     *  identity pack is not composed in this app's model. */
    public record Tables(String usersTable, String externalIdentityTable) {
        public static Tables resolve(CompiledModel model) {
            String users = tableName(model, "identity::User");
            String external = tableName(model, "identity::ExternalIdentity");
            if (users == null || external == null) {
                return null;
            }
            return new Tables(users, external);
        }

        private static String tableName(CompiledModel model, String conceptName) {
            return model.findConcept(conceptName)
                    .map(CompiledConcept::getTableName)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse(null);
        }
    }

    public Optional<String> findLinkOwner(Tables tables, String provider, String subject) {
        if (!fits(subject, MAX_SUBJECT_LENGTH)) {
            return Optional.empty(); // longer than the column: no stored row can match it
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT user_id FROM " + tables.externalIdentityTable()
                                + " WHERE provider = ? AND provider_subject = ?")) {
            ps.setString(1, provider);
            ps.setString(2, subject);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("user_id")) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw storeFailure("findLinkOwner", ex);
        }
    }

    public boolean insertLink(Tables tables, String userId, String provider, String subject, String tenantId) {
        if (!fits(subject, MAX_SUBJECT_LENGTH) || !fits(tenantId, MAX_TENANT_ID_LENGTH)) {
            throw new IllegalArgumentException("ExternalIdentityStore.insertLink: subject over "
                    + MAX_SUBJECT_LENGTH + " or tenant id over " + MAX_TENANT_ID_LENGTH + " characters");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO " + tables.externalIdentityTable()
                                + " (id, tenant_id, user_id, provider, provider_subject, linked_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setObject(1, UUID.randomUUID());
            ps.setString(2, tenantId == null || tenantId.isBlank() ? "dev" : tenantId.trim());
            ps.setObject(3, UUID.fromString(userId));
            ps.setString(4, provider);
            ps.setString(5, subject);
            ps.setTimestamp(6, Timestamp.from(Instant.now()));
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            // SQLState class 23 (integrity constraint): a concurrent link of the same (provider,
            // subject) won the race -- a real "not inserted", which the caller tells the user to retry.
            if (ex.getSQLState() != null && ex.getSQLState().startsWith("23")) {
                return false;
            }
            throw storeFailure("insertLink", ex);
        }
    }

    public boolean deleteLink(Tables tables, String provider, String subject) {
        if (!fits(subject, MAX_SUBJECT_LENGTH)) {
            return false; // longer than the column: no stored row can match it
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM " + tables.externalIdentityTable()
                                + " WHERE provider = ? AND provider_subject = ?")) {
            ps.setString(1, provider);
            ps.setString(2, subject);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw storeFailure("deleteLink", ex);
        }
    }

    /** Scoped unlink: deletes the given user's own link for this provider, regardless of subject --
     *  so a caller can never remove a DIFFERENT user's link by guessing a provider name. */
    public boolean deleteLinkForUser(Tables tables, String userId, String provider) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM " + tables.externalIdentityTable()
                                + " WHERE user_id = ? AND provider = ?")) {
            ps.setObject(1, UUID.fromString(userId));
            ps.setString(2, provider);
            return ps.executeUpdate() > 0;
        } catch (SQLException ex) {
            throw storeFailure("deleteLinkForUser", ex);
        }
    }

    /** Every provider this user has linked -- for the "connected as ... / Unlink" list on
     *  agent-link.html. */
    public java.util.List<String> linkedProviders(Tables tables, String userId) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT provider FROM " + tables.externalIdentityTable() + " WHERE user_id = ?")) {
            ps.setObject(1, UUID.fromString(userId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("provider"));
                }
            }
        } catch (SQLException ex) {
            throw storeFailure("linkedProviders", ex);
        }
        return out;
    }

    /** How many accounts are linked to this provider, total -- for the status probe (A9). */
    public int countLinked(Tables tables, String provider) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT COUNT(*) FROM " + tables.externalIdentityTable() + " WHERE provider = ?")) {
            ps.setString(1, provider);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException ex) {
            throw storeFailure("countLinked", ex);
        }
    }

    public Optional<ActiveUser> findActiveUserById(Tables tables, String userId) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT username, tenant_id, active FROM " + tables.usersTable() + " WHERE id = ?")) {
            ps.setObject(1, UUID.fromString(userId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !rs.getBoolean("active")) {
                    return Optional.empty();
                }
                return Optional.of(new ActiveUser(rs.getString("username"), rs.getString("tenant_id")));
            }
        } catch (SQLException ex) {
            throw storeFailure("findActiveUserById", ex);
        }
    }

    /** The current user's own id + tenant, resolved by username -- used when minting a link code
     *  for the caller. */
    public Optional<String> findUserIdByUsername(Tables tables, String username, String tenantId) {
        if (!fits(username, MAX_USERNAME_LENGTH) || !fits(tenantId, MAX_TENANT_ID_LENGTH)) {
            return Optional.empty(); // longer than the column: no stored row can match it
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "SELECT id FROM " + tables.usersTable() + " WHERE username = ? AND tenant_id = ?")) {
            ps.setString(1, username);
            ps.setString(2, tenantId == null || tenantId.isBlank() ? "dev" : tenantId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString("id")) : Optional.empty();
            }
        } catch (SQLException ex) {
            throw storeFailure("findUserIdByUsername", ex);
        }
    }

    /**
     * A database fault is never answered as "not linked" / "no such user": on this auth path that
     * would turn an outage into a security verdict the caller cannot tell from a real one (REG-39).
     * Every user id reaching this store was read from the identity tables, so a malformed UUID is a
     * bug and propagates as-is rather than being folded into "not found" either.
     */
    private static boolean fits(String value, int max) {
        return value == null || value.length() <= max;
    }

    private static IllegalStateException storeFailure(String operation, SQLException ex) {
        return new IllegalStateException(
                "ExternalIdentityStore." + operation + " failed (SQLState " + ex.getSQLState() + ")", ex);
    }
}
