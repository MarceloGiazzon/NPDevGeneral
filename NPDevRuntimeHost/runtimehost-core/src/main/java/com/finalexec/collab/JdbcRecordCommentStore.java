package com.finalexec.collab;

import com.npdev.kernel.dbschema.NpdevRecordCommentTable;
import com.npdev.kernel.storage.sql.SqlDialect;
import com.npdev.kernel.storage.sql.SqlDialects;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PROC-5: the {@code npdev_record_comment} store. Portable SQL; the row cap goes through the dialect. */
public final class JdbcRecordCommentStore implements RecordCommentStore {

    private static final String TABLE = NpdevRecordCommentTable.NAME;
    private static final String COLUMNS =
            "comment_id, tenant_id, concept_name, record_id, author, body, kind, file_json, created_at_ms, deleted_at_ms";

    private final DataSource dataSource;

    public JdbcRecordCommentStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void insert(RecordComment c) {
        String sql = "INSERT INTO " + TABLE + " (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, c.id());
            ps.setString(2, c.tenantId());
            ps.setString(3, c.conceptName());
            ps.setString(4, c.recordId());
            ps.setString(5, c.author());
            ps.setString(6, c.body());
            ps.setString(7, c.kind());
            if (c.fileJson() == null) {
                ps.setNull(8, Types.VARCHAR);
            } else {
                ps.setString(8, c.fileJson());
            }
            ps.setLong(9, c.createdAtMs());
            if (c.deletedAtMs() == null) {
                ps.setNull(10, Types.BIGINT);
            } else {
                ps.setLong(10, c.deletedAtMs());
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not insert record comment", e);
        }
    }

    @Override
    public List<RecordComment> list(String tenantId, String conceptName, String recordId, int limit) {
        try (Connection connection = dataSource.getConnection()) {
            SqlDialect dialect = SqlDialects.forConnection(connection);
            String sql = dialect.limited("SELECT " + COLUMNS + " FROM " + TABLE
                    + " WHERE tenant_id = ? AND concept_name = ? AND record_id = ? AND deleted_at_ms IS NULL"
                    + " ORDER BY created_at_ms ASC, comment_id ASC\n");
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, tenantId);
                ps.setString(2, conceptName);
                ps.setString(3, recordId);
                int index = 4;
                for (int value : dialect.limitOnly().values(limit, 0)) {
                    ps.setInt(index++, value);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<RecordComment> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(map(rs));
                    }
                    return rows;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not list record comments", e);
        }
    }

    @Override
    public Optional<RecordComment> findById(String tenantId, String commentId) {
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE tenant_id = ? AND comment_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, commentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read record comment", e);
        }
    }

    @Override
    public java.util.Set<String> attachmentKeys(String tenantId) {
        String sql = "SELECT file_json FROM " + TABLE
                + " WHERE tenant_id = ? AND kind = ? AND deleted_at_ms IS NULL AND file_json IS NOT NULL";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, tenantId);
            ps.setString(2, RecordComment.KIND_ATTACHMENT);
            java.util.Set<String> keys = new java.util.HashSet<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AttachmentRef.parse(rs.getString(1)).ifPresent(ref -> keys.add(ref.key()));
                }
            }
            return keys;
        } catch (SQLException e) {
            throw new IllegalStateException("could not read attachment keys", e);
        }
    }

    @Override
    public boolean softDelete(String tenantId, String commentId, long nowMs) {
        String sql = "UPDATE " + TABLE + " SET deleted_at_ms = ? WHERE tenant_id = ? AND comment_id = ? AND deleted_at_ms IS NULL";
        try (Connection connection = dataSource.getConnection(); PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, nowMs);
            ps.setString(2, tenantId);
            ps.setString(3, commentId);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("could not delete record comment", e);
        }
    }

    private static RecordComment map(ResultSet rs) throws SQLException {
        long deleted = rs.getLong(10);
        Long deletedAt = rs.wasNull() ? null : deleted;
        return new RecordComment(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getLong(9), deletedAt);
    }
}
