package com.finalexec.notify;

import com.finalexec.collab.RecordCommentService.Mention;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PROC-4 x PROC-5: a comment @mention becomes one bell notification for the mentioned user only. */
class MentionNotificationNotifierTest {

    private final InMemoryNotificationStore store = new InMemoryNotificationStore();
    private final MentionNotificationNotifier notifier = new MentionNotificationNotifier(store, () -> 77L);

    private static Mention mention(String tenant, String recipient, String link) {
        return new Mention(tenant, recipient, "tito", "Trade", "Trade", "rec-1",
                "hey @tavo look at this", link, "comment:Trade/rec-1");
    }

    @Test
    void mentionBecomesAnUnreadNotificationForTheMentionedUserInTheirTenant() {
        notifier.mentioned(mention("t1", "tavo", "/npdev-business-ui/index.html#concept-Trade"));

        List<NotificationStore.Notification> mine = store.list("t1", "tavo", false, 10);
        assertEquals(1, mine.size());
        NotificationStore.Notification n = mine.get(0);
        assertEquals("tito mentioned you on a Trade", n.title());
        assertEquals("hey @tavo look at this", n.body());
        assertEquals("/npdev-business-ui/index.html#concept-Trade", n.link());
        assertEquals("comment:Trade/rec-1", n.source());
        assertEquals(77L, n.createdAtEpochMs());
        assertNull(n.readAtEpochMs());
        assertEquals(0, store.countUnread("t1", "tito"), "the author is not notified");
        assertEquals(0, store.countUnread("t2", "tavo"), "another tenant sees nothing");
    }

    @Test
    void anOffSiteLinkIsDroppedAndIncompleteMentionsAreIgnored() {
        notifier.mentioned(mention("t1", "tavo", "https://evil.example/x"));
        assertNull(store.list("t1", "tavo", false, 10).get(0).link());

        notifier.mentioned(mention("t1", " ", "/x"));
        notifier.mentioned(mention(null, "tavo", "/x"));
        notifier.mentioned(null);
        assertEquals(1, store.list("t1", "tavo", false, 10).size());
        assertTrue(store.list("t1", " ", false, 10).isEmpty());
    }
}
