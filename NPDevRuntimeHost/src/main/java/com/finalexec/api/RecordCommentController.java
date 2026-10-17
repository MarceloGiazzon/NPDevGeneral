package com.finalexec.api;

import com.finalexec.collab.RecordCommentService;
import com.finalexec.collab.RecordCommentService.CommentException;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PROC-5: the comment thread of a record -- thin HTTP over {@link RecordCommentService}, where every
 * rule lives (above all the read gate: a thread is reachable only by a caller who can read the record).
 *
 * <pre>
 *   GET    /api/records/{concept}/{id}/comments
 *   POST   /api/records/{concept}/{id}/comments          {"body": "..."}
 *   DELETE /api/records/{concept}/{id}/comments/{commentId}
 * </pre>
 *
 * 404 for an unreadable record or a concept without {@code collaboration.comments}, 400 for a bad
 * body, 403 only for deleting someone else's comment.
 */
@RestController
@RequestMapping({"/api/records", "/api/v1/records"})
public class RecordCommentController {

    private final RecordCommentService service;
    private final RuntimeContextService runtimeContextService;

    @Autowired
    public RecordCommentController(RecordCommentService service, RuntimeContextService runtimeContextService) {
        this.service = service;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping("/{concept}/{id}/comments")
    public ResponseEntity<?> list(HttpServletRequest request, @PathVariable String concept, @PathVariable String id) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        try {
            return ResponseEntity.ok(service.list(concept, id, context));
        } catch (CommentException e) {
            return failure(e);
        }
    }

    @PostMapping("/{concept}/{id}/comments")
    public ResponseEntity<?> add(HttpServletRequest request, @PathVariable String concept, @PathVariable String id,
                                 @RequestBody(required = false) Map<String, Object> payload) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        Object body = payload == null ? null : payload.get("body");
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(service.add(concept, id, body == null ? null : String.valueOf(body), context));
        } catch (CommentException e) {
            return failure(e);
        }
    }

    @DeleteMapping("/{concept}/{id}/comments/{commentId}")
    public ResponseEntity<?> delete(HttpServletRequest request, @PathVariable String concept, @PathVariable String id,
                                    @PathVariable String commentId) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        try {
            service.delete(concept, id, commentId, context);
            return ResponseEntity.noContent().build();
        } catch (CommentException e) {
            return failure(e);
        }
    }

    private static ResponseEntity<Map<String, Object>> failure(CommentException e) {
        HttpStatus status = switch (e.failure()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BODY_INVALID -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.failure().name());
        body.put("message", e.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
