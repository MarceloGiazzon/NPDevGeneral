package com.finalexec.notify;

import com.npdev.kernel.storage.sql.PaginationClause;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * PROC-4: persistent {@link NotificationStore} over the {@code npdev_notification} internal table.
 * Portable SQL only; the row cap goes through {@link SqlDialect#limited(String)} (STOR-1). Every
 * statement carries {@code tenant_id = ? AND recipient = ?}, so a caller can never touch another
 * user's row.
 */
public final class JdbcNotificationStore implements NotificationStore {

    private static final String COLUMNS =
            "notification_id, tenant_id, recipient, title, body, link, source, created_at, read_at";

    private final DataSource dataSource;
    private final SqlDialect dialect;

    public JdbcNotificationStore(DataSource dataSource) {
        this(dataSource, SqlDialects.active());
    }

    /** Explicit dialect, for tests that pin an engine rather than reading the active one. */
    public JdbcNotificationStore(DataSource dataSource, SqlDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public void insert(Notification n) {
        String sql = "INSERT INTO npdev_notification (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, n.id());
            statement.setString(2, n.tenantId());
            statement.setString(3, n.recipient());
            statement.setString(4, n.title());
            setNullableString(statement, 5, n.body());
            setNullableString(statement, 6, n.link());
            setNullableString(statement, 7, n.source());
            statement.setTimestamp(8, new Timestamp(n.createdAtEpochMs()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not store notification: " + e.getMessage(), e);
        }
    }

    @Override
    public List<Notification> list(String tenantId, String recipient, boolean unreadOnly, int limit) {
        String sql = dialect.limited("SELECT " + COLUMNS + " FROM npdev_notification "
                + "WHERE tenant_id = ? AND recipient = ?"
                + (unreadOnly ? " AND read_at IS NULL" : "")
                + "\nORDER BY created_at DESC, notification_id DESC\n");
        PaginationClause pagination = dialect.limitOnly();
        List<Notification> out = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, recipient);
            int index = 3;
            for (int value : pagination.values(Math.max(1, limit), 0)) {
                statement.setInt(index++, value);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    Timestamp readAt = rs.getTimestamp(9);
                    out.add(new Notification(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6), rs.getString(7), rs.getTimestamp(8).getTime(),
                            readAt == null ? null : readAt.getTime()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not list notifications: " + e.getMessage(), e);
        }
        return out;
    }

    @Override
    public long countUnread(String tenantId, String recipient) {
        String sql = "SELECT COUNT(*) FROM npdev_notification "
                + "WHERE tenant_id = ? AND recipient = ? AND read_at IS NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, recipient);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not count notifications: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean markRead(String tenantId, String recipient, String notificationId, long nowEpochMs) {
        String sql = "UPDATE npdev_notification SET read_at = ? "
                + "WHERE notification_id = ? AND tenant_id = ? AND recipient = ? AND read_at IS NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, new Timestamp(nowEpochMs));
            statement.setString(2, notificationId);
            statement.setString(3, tenantId);
            statement.setString(4, recipient);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not mark notification read: " + e.getMessage(), e);
        }
    }

    @Override
    public int markAllRead(String tenantId, String recipient, long nowEpochMs) {
        String sql = "UPDATE npdev_notification SET read_at = ? "
                + "WHERE tenant_id = ? AND recipient = ? AND read_at IS NULL";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, new Timestamp(nowEpochMs));
            statement.setString(2, tenantId);
            statement.setString(3, recipient);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Could not mark notifications read: " + e.getMessage(), e);
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
