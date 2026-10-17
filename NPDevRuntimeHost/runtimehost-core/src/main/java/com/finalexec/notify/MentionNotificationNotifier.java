package com.finalexec.notify;

import com.finalexec.collab.RecordCommentService;

import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * PROC-4 x PROC-5: turns an {@code @mention} in a record comment into a bell notification for the mentioned
 * user. The comment service only hands over mentions of other users, in the commenter's tenant; the link it
 * supplies is re-checked here to stay site-relative, so the bell can never be pointed off-site.
 */
public final class MentionNotificationNotifier implements RecordCommentService.MentionNotifier {

    private final NotificationStore store;
    private final LongSupplier clock;

    public MentionNotificationNotifier(NotificationStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void mentioned(RecordCommentService.Mention mention) {
        if (mention == null || mention.recipient() == null || mention.recipient().isBlank()
                || mention.tenantId() == null || mention.tenantId().isBlank()) {
            return;
        }
        String link = mention.link() != null && NotifyCapabilityHandler.isSiteRelative(mention.link())
                ? mention.link() : null;
        String label = mention.conceptLabel() == null || mention.conceptLabel().isBlank()
                ? mention.conceptName() : mention.conceptLabel();
        store.insert(new NotificationStore.Notification(UUID.randomUUID().toString(), mention.tenantId(),
                mention.recipient().trim(), mention.author() + " mentioned you on a " + label,
                mention.excerpt(), link, mention.source(), clock.getAsLong(), null));
    }
}
