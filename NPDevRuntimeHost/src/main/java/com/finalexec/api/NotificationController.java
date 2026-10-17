package com.finalexec.api;

import com.finalexec.notify.NotificationStore;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PROC-4: the shell bell's REST surface over {@link NotificationStore}. The recipient is ALWAYS the
 * authenticated actor and the tenant ALWAYS the request's tenant -- there is deliberately no
 * recipient parameter, so a caller can neither read nor mark another user's notifications.
 *
 * <pre>
 *   GET  /api/notifications?unreadOnly=false&amp;limit=20  -> {items:[{id,title,body,link,source,createdAtEpochMs,read}], unreadCount}
 *   GET  /api/notifications/unread-count                -> {count}
 *   POST /api/notifications/{id}/read                   -> 204, or 404 when the id is not the caller's unread row
 *   POST /api/notifications/read-all                    -> {updated}
 * </pre>
 *
 * An actor-less (anonymous) context gets 401. Under {@code /api/}, so the JWT / api-key filters already
 * require a credential.
 */
@RestController
@RequestMapping({"/api/notifications", "/api/v1/notifications"})
public class NotificationController {

    private final NotificationStore store;
    private final RuntimeContextService runtimeContextService;

    public NotificationController(NotificationStore store, RuntimeContextService runtimeContextService) {
        this.store = store;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request,
                                  @RequestParam(name = "unreadOnly", defaultValue = "false") boolean unreadOnly,
                                  @RequestParam(name = "limit", defaultValue = "20") int limit) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasActor()) {
            return unauthorized();
        }
        int clamped = Math.max(1, Math.min(100, limit));
        List<Map<String, Object>> items = new ArrayList<>();
        for (NotificationStore.Notification n : store.list(context.tenantId(), context.actorId(), unreadOnly, clamped)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", n.id());
            item.put("title", n.title());
            item.put("body", n.body());
            item.put("link", n.link());
            item.put("source", n.source());
            item.put("createdAtEpochMs", n.createdAtEpochMs());
            item.put("read", n.read());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("unreadCount", store.countUnread(context.tenantId(), context.actorId()));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<?> unreadCount(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasActor()) {
            return unauthorized();
        }
        return ResponseEntity.ok(Map.of("count", store.countUnread(context.tenantId(), context.actorId())));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<?> markRead(HttpServletRequest request, @PathVariable("id") String id) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasActor()) {
            return unauthorized();
        }
        if (!store.markRead(context.tenantId(), context.actorId(), id, System.currentTimeMillis())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "NOTIFICATION_NOT_FOUND", "message", "No unread notification with that id"));
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<?> readAll(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasActor()) {
            return unauthorized();
        }
        return ResponseEntity.ok(Map.of("updated",
                store.markAllRead(context.tenantId(), context.actorId(), System.currentTimeMillis())));
    }

    private static ResponseEntity<?> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "NOTIFICATION_UNAUTHENTICATED", "message", "Sign in to see notifications"));
    }
}
