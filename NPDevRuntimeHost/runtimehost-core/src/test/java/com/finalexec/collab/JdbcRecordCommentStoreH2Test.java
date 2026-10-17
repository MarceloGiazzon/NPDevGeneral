package com.finalexec.collab;

import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** PROC-5: the JDBC store's SQL (portable statements + the dialect's row cap) against a real H2. */
class JdbcRecordCommentStoreH2Test {

    private JdbcRecordCommentStore store;

    @BeforeEach
    void setUp() throws Exception {
        SqlDialects.setActive(H2Dialect.INSTANCE);
        DataSource dataSource = new UrlDataSource("jdbc:h2:mem:comments-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE npdev_record_comment (comment_id VARCHAR(64) PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,"
                    + " concept_name VARCHAR(128) NOT NULL, record_id VARCHAR(128) NOT NULL, author VARCHAR(128) NOT NULL,"
                    + " body CLOB NOT NULL, kind VARCHAR(16) NOT NULL, file_json CLOB, created_at_ms BIGINT NOT NULL,"
                    + " deleted_at_ms BIGINT)");
        }
        store = new JdbcRecordCommentStore(dataSource);
    }

    /** The same minimal DataSource the other runtimehost-core H2 tests use. */
    private static final class UrlDataSource implements DataSource {
        private final String url;

        private UrlDataSource(String url) {
            this.url = url;
        }

        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger(getClass().getName()); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    private static RecordComment row(String id, String tenant, String record, long at) {
        return new RecordComment(id, tenant, "Mosaic", record, "tito", "body " + id, RecordComment.KIND_COMMENT, null, at, null);
    }

    @Test
    void rejectsAKeyLongerThanTheBound() {
        String tooLong = "t".repeat(JdbcRecordCommentStore.MAX_KEY_LENGTH + 1);
        assertThrows(IllegalArgumentException.class, () -> store.findById(tooLong, "c1"));
        assertThrows(IllegalArgumentException.class, () -> store.attachmentKeys(tooLong));
        assertThrows(IllegalArgumentException.class, () -> store.insert(row("c1", tooLong, "r1", 1L)));
        assertEquals("tenant", JdbcRecordCommentStore.key("tenant", JdbcRecordCommentStore.MAX_KEY_LENGTH));
    }

    @Test
    void listsLiveRowsOfOneThreadOldestFirstAndCapsThem() {
        store.insert(row("b", "t1", "r1", 20));
        store.insert(row("a", "t1", "r1", 10));
        store.insert(row("c", "t1", "r1", 30));
        store.insert(row("x", "t1", "r2", 5));
        store.insert(row("y", "t2", "r1", 5));
        assertEquals(List.of("a", "b", "c"), store.list("t1", "Mosaic", "r1", 10).stream().map(RecordComment::id).toList());
        assertEquals(List.of("a", "b"), store.list("t1", "Mosaic", "r1", 2).stream().map(RecordComment::id).toList());
        assertEquals(List.of("y"), store.list("t2", "Mosaic", "r1", 10).stream().map(RecordComment::id).toList());
    }

    @Test
    void softDeleteHidesTheRowFromTheListButFindByIdStillSeesIt() {
        store.insert(row("a", "t1", "r1", 10));
        assertTrue(store.softDelete("t1", "a", 99));
        assertFalse(store.softDelete("t1", "a", 100), "already deleted");
        assertFalse(store.softDelete("t2", "a", 100), "other tenant");
        assertEquals(List.of(), store.list("t1", "Mosaic", "r1", 10));
        RecordComment found = store.findById("t1", "a").orElseThrow();
        assertEquals(99L, found.deletedAtMs());
        assertTrue(store.findById("t2", "a").isEmpty());
    }

    @Test
    void attachmentRowsKeepTheirFileJson() {
        String fileJson = new AttachmentRef("local", "t1/plan.pdf", "application/pdf", 7, "plan.pdf").toJson();
        store.insert(new RecordComment("f1", "t1", "Mosaic", "r1", "tito", "plan.pdf", RecordComment.KIND_ATTACHMENT,
                fileJson, 10, null));
        store.insert(row("c1", "t1", "r1", 11));
        RecordComment found = store.list("t1", "Mosaic", "r1", 10).get(0);
        assertEquals(RecordComment.KIND_ATTACHMENT, found.kind());
        assertEquals(fileJson, found.fileJson());
        assertEquals(java.util.Set.of("t1/plan.pdf"), store.attachmentKeys("t1"), "comments contribute no keys");
        assertTrue(store.attachmentKeys("t2").isEmpty());
        store.softDelete("t1", "f1", 99);
        assertTrue(store.attachmentKeys("t1").isEmpty(), "a deleted attachment no longer protects its object");
    }
}
