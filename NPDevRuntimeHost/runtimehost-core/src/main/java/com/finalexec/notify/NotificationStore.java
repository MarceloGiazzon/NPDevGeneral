package com.finalexec.notify;

import java.util.List;

/** PROC-4: per-user in-app notification storage behind the shell bell. Every call is tenant + recipient scoped. */
public interface NotificationStore {

    record Notification(String id, String tenantId, String recipient, String title, String body,
                        String link, String source, long createdAtEpochMs, Long readAtEpochMs) {
        public boolean read() {
            return readAtEpochMs != null;
        }
    }

    void insert(Notification notification);

    /** Newest first. unreadOnly filters read_at IS NULL. limit is clamped to 1..100 by callers. */
    List<Notification> list(String tenantId, String recipient, boolean unreadOnly, int limit);

    long countUnread(String tenantId, String recipient);

    /** Returns true when an unread row of THIS recipient was updated (never touches another user's row). */
    boolean markRead(String tenantId, String recipient, String notificationId, long nowEpochMs);

    int markAllRead(String tenantId, String recipient, long nowEpochMs);
}
