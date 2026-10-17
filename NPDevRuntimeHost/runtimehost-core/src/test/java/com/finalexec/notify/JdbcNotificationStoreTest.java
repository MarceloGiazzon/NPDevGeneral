package com.finalexec.notify;

import com.npdev.kernel.storage.sql.H2Dialect;
import com.npdev.kernel.storage.sql.SqlDialects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PROC-4: {@link JdbcNotificationStore} against a real H2 database; mirrors CronFireClaimStoreTest. */
class JdbcNotificationStoreTest {

    private static final String SCHEMA_SQL = """
            CREATE TABLE npdev_notification (
                notification_id VARCHAR(191) NOT NULL,
                tenant_id VARCHAR(191) NOT NULL,
                recipient VARCHAR(191) NOT NULL,
                title VARCHAR(191) NOT NULL,
                body TEXT,
                link VARCHAR(191),
                source VARCHAR(191),
                created_at TIMESTAMP NOT NULL,
                read_at TIMESTAMP,
                PRIMARY KEY (notification_id)
            )
            """;

    private DataSource dataSource;
    private JdbcNotificationStore store;

    @BeforeEach
    void setUp() throws SQLException {
        SqlDialects.setActive(H2Dialect.INSTANCE);
        String url = "jdbc:h2:mem:" + getClass().getSimpleName() + System.nanoTime() + ";DB_CLOSE_DELAY=-1";
        // H2 is runtime-only on this module's classpath: a tiny DataSource over DriverManager is enough.
        dataSource = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        return DriverManager.getConnection(url);
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(SCHEMA_SQL);
        }
        store = new JdbcNotificationStore(dataSource, H2Dialect.INSTANCE);
    }

    @AfterEach
    void tearDown() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    private static NotificationStore.Notification row(String id, String tenant, String recipient, long at) {
        return new NotificationStore.Notification(id, tenant, recipient, "Title " + id, "Body " + id,
                "/npdev-business-ui/collab/tasks.html", "flow:ProposeTrade", at, null);
    }

    @Test
    void listIsNewestFirstScopedToTheRecipientAndCountsUnread() {
        store.insert(row("a1", "t1", "ana", 1_000L));
        store.insert(row("a2", "t1", "ana", 2_000L));
        store.insert(row("a3", "t1", "ana", 3_000L));
        store.insert(row("b1", "t1", "bob", 4_000L));

        List<NotificationStore.Notification> ana = store.list("t1", "ana", false, 20);
        assertEquals(List.of("a3", "a2", "a1"), ana.stream().map(NotificationStore.Notification::id).toList());
        assertEquals("flow:ProposeTrade", ana.get(0).source());
        assertEquals(3, store.countUnread("t1", "ana"));
        assertEquals(1, store.countUnread("t1", "bob"));
        assertEquals(2, store.list("t1", "ana", false, 2).size(), "the row cap is honoured");
    }

    @Test
    void anotherTenantSeesNothing() {
        store.insert(row("a1", "t1", "ana", 1_000L));

        assertTrue(store.list("t2", "ana", false, 20).isEmpty());
        assertEquals(0, store.countUnread("t2", "ana"));
        assertFalse(store.markRead("t2", "ana", "a1", 5_000L));
        assertEquals(0, store.markAllRead("t2", "ana", 5_000L));
    }

    @Test
    void markReadNeverTouchesAnotherUsersRow() {
        store.insert(row("a1", "t1", "ana", 1_000L));
        store.insert(row("b1", "t1", "bob", 2_000L));

        assertFalse(store.markRead("t1", "ana", "b1", 5_000L), "ana cannot mark bob's row");
        assertEquals(1, store.countUnread("t1", "bob"));
        assertTrue(store.markRead("t1", "ana", "a1", 5_000L));
        assertFalse(store.markRead("t1", "ana", "a1", 6_000L), "already read");
        assertEquals(0, store.countUnread("t1", "ana"));

        NotificationStore.Notification read = store.list("t1", "ana", false, 20).get(0);
        assertTrue(read.read());
        assertEquals(5_000L, read.readAtEpochMs());
    }

    @Test
    void unreadOnlyAndMarkAllRead() {
        store.insert(row("a1", "t1", "ana", 1_000L));
        store.insert(row("a2", "t1", "ana", 2_000L));
        store.insert(row("a3", "t1", "ana", 3_000L));
        store.markRead("t1", "ana", "a2", 4_000L);

        assertEquals(List.of("a3", "a1"),
                store.list("t1", "ana", true, 20).stream().map(NotificationStore.Notification::id).toList());
        assertEquals(2, store.markAllRead("t1", "ana", 5_000L));
        assertEquals(0, store.countUnread("t1", "ana"));
        assertTrue(store.list("t1", "ana", true, 20).isEmpty());
    }

    @Test
    void optionalColumnsMayBeNull() {
        store.insert(new NotificationStore.Notification("n1", "t1", "ana", "Only a title", null, null, null, 1_000L, null));

        NotificationStore.Notification stored = store.list("t1", "ana", false, 20).get(0);
        assertNull(stored.body());
        assertNull(stored.link());
        assertNull(stored.source());
        assertNull(stored.readAtEpochMs());
    }
}
