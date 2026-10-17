package com.finalexec.notify;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PROC-4: non-persistent store used when {@code npdev.storage.mode} is in-memory (the default). Same
 * semantics as {@link JdbcNotificationStore}; rows are lost on restart, like every other in-proc store.
 */
public final class InMemoryNotificationStore implements NotificationStore {

    private final List<Notification> rows = new CopyOnWriteArrayList<>();

    @Override
    public void insert(Notification notification) {
        rows.add(Objects.requireNonNull(notification, "notification"));
    }

    @Override
    public List<Notification> list(String tenantId, String recipient, boolean unreadOnly, int limit) {
        Comparator<Notification> newestFirst = Comparator.comparingLong(Notification::createdAtEpochMs)
                .thenComparing(Notification::id).reversed();
        return rows.stream()
                .filter(n -> n.tenantId().equals(tenantId) && n.recipient().equals(recipient))
                .filter(n -> !unreadOnly || n.readAtEpochMs() == null)
                .sorted(newestFirst)
                .limit(Math.max(1, limit))
                .toList();
    }

    @Override
    public long countUnread(String tenantId, String recipient) {
        return rows.stream()
                .filter(n -> n.tenantId().equals(tenantId) && n.recipient().equals(recipient)
                        && n.readAtEpochMs() == null)
                .count();
    }

    @Override
    public synchronized boolean markRead(String tenantId, String recipient, String notificationId, long nowEpochMs) {
        for (int i = 0; i < rows.size(); i++) {
            Notification n = rows.get(i);
            if (n.id().equals(notificationId) && n.tenantId().equals(tenantId)
                    && n.recipient().equals(recipient) && n.readAtEpochMs() == null) {
                rows.set(i, withRead(n, nowEpochMs));
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized int markAllRead(String tenantId, String recipient, long nowEpochMs) {
        int updated = 0;
        for (int i = 0; i < rows.size(); i++) {
            Notification n = rows.get(i);
            if (n.tenantId().equals(tenantId) && n.recipient().equals(recipient) && n.readAtEpochMs() == null) {
                rows.set(i, withRead(n, nowEpochMs));
                updated++;
            }
        }
        return updated;
    }

    private static Notification withRead(Notification n, long nowEpochMs) {
        return new Notification(n.id(), n.tenantId(), n.recipient(), n.title(), n.body(), n.link(),
                n.source(), n.createdAtEpochMs(), nowEpochMs);
    }
}
