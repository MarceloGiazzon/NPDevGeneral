package com.finalexec.notify;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PROC-4: notify.send argument forms, fan-out, link safety and the failure codes. */
class NotifyCapabilityHandlerTest {

    private static final Map<String, Object> STATE = Map.of("tenantId", "t1", "actorId", "tito",
            "causationId", "flow:ProposeTrade");

    private final InMemoryNotificationStore store = new InMemoryNotificationStore();
    private final NotifyCapabilityHandler handler = new NotifyCapabilityHandler(store, () -> 42L);

    private static CapabilityCall call(String operation, List<Object> args) {
        return new CapabilityCall("notify", "NotifyCapability", "notify", operation, args);
    }

    @Test
    void positionalArgsInsertOneRowWithTheTemplateRendered() {
        CapabilityResult result = handler.invoke(call("send", List.of("tavo", "New trade ${code} from ${who}",
                "Answer within 7 days.", Map.of("code", "T-7", "who", "tito"), "/npdev-business-ui/collab/tasks.html")), STATE);

        assertTrue(result.ok(), String.valueOf(result.error()));
        assertEquals(Map.of("notified", 1), result.value());
        NotificationStore.Notification n = store.list("t1", "tavo", false, 10).get(0);
        assertEquals("New trade T-7 from tito", n.title());
        assertEquals("Answer within 7 days.", n.body());
        assertEquals("/npdev-business-ui/collab/tasks.html", n.link());
        assertEquals("flow:ProposeTrade", n.source());
        assertEquals(42L, n.createdAtEpochMs());
        assertNull(n.readAtEpochMs());
    }

    @Test
    void singleMapArgIsReadByKey() {
        Map<String, Object> named = new java.util.LinkedHashMap<>();
        named.put("to", "tavo");
        named.put("title", "Hello ${code}");
        named.put("templateVars", Map.of("code", "X1"));
        CapabilityResult result = handler.invoke(call("send", List.of(named)), STATE);

        assertTrue(result.ok());
        NotificationStore.Notification n = store.list("t1", "tavo", false, 10).get(0);
        assertEquals("Hello X1", n.title());
        assertNull(n.body());
        assertNull(n.link());
    }

    @Test
    void aListOfRecipientsFansOutAndDeduplicates() {
        CapabilityResult result = handler.invoke(call("send", List.of(List.of("ana", "bob", "ana", " "), "Heads up")), STATE);

        assertTrue(result.ok());
        assertEquals(Map.of("notified", 2), result.value());
        assertEquals(1, store.countUnread("t1", "ana"));
        assertEquals(1, store.countUnread("t1", "bob"));
    }

    @Test
    void nonSiteRelativeLinksAreDropped() {
        for (String bad : List.of("https://evil", "//evil.com/x", "/\\evil.com", "javascript:alert(1)")) {
            handler.invoke(call("send", List.of("tavo", "T", "B", Map.of(), bad)), STATE);
        }
        List<NotificationStore.Notification> all = store.list("t1", "tavo", false, 100);
        assertEquals(4, all.size());
        assertTrue(all.stream().allMatch(n -> n.link() == null), "every unsafe link must be dropped");
    }

    @Test
    void unsupportedOperationAndMissingTenantAndBadArgsFailWithStableCodes() {
        CapabilityResult unsupported = handler.invoke(call("foo", List.of("tavo", "T")), STATE);
        assertFalse(unsupported.ok());
        assertEquals("NOTIFY_OPERATION_UNSUPPORTED", unsupported.error().code());

        CapabilityResult noTenant = handler.invoke(call("send", List.of("tavo", "T")), Map.of("actorId", "tito"));
        assertEquals("NOTIFY_TENANT_MISSING", noTenant.error().code());

        assertEquals("NOTIFY_ARGS_INVALID", handler.invoke(call("send", List.of()), STATE).error().code());
        assertEquals("NOTIFY_ARGS_INVALID", handler.invoke(call("send", List.of("tavo", " ")), STATE).error().code());
        assertEquals(0, store.countUnread("t1", "tavo"));
    }
}
