package com.finalexec.api;

import com.finalexec.collab.RecordCommentService;
import com.finalexec.collab.RecordCommentService.CommentException;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ports.FileHandle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * PROC-5/PROC-6: the thread of a record -- thin HTTP over {@link RecordCommentService}, where every rule
 * lives (above all the read gate: a thread is reachable only by a caller who can read the record).
 *
 * <pre>
 *   GET    /api/records/{concept}/{id}/comments
 *   POST   /api/records/{concept}/{id}/comments          {"body": "..."}
 *   DELETE /api/records/{concept}/{id}/comments/{commentId}
 *   POST   /api/records/{concept}/{id}/attachments        (multipart "file")
 *   GET    /api/records/{concept}/{id}/attachments/{commentId}
 * </pre>
 *
 * 404 for an unreadable record or a concept without the matching {@code collaboration} flag, 400 for a bad
 * body, 403 only for deleting someone else's comment, 413/415 for an attachment over the size / type limits.
 */
@RestController
@RequestMapping({"/api/records", "/api/v1/records"})
public class RecordCommentController {

    private static final Set<String> INLINE_SAFE_CONTENT_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf");

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

    /**
     * PROC-6: attach a file to the record's thread (multipart {@code file}). 413 over the size limit, 415 for a
     * content type outside {@code npdev.collab.attachments.content-types}.
     */
    @PostMapping("/{concept}/{id}/attachments")
    public ResponseEntity<?> attach(HttpServletRequest request, @PathVariable String concept, @PathVariable String id,
                                    @RequestPart("file") MultipartFile file) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        try (InputStream content = file.getInputStream()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(service.addAttachment(concept, id,
                    file.getOriginalFilename(), file.getContentType(), file.getSize(), content, context));
        } catch (CommentException e) {
            return failure(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    /**
     * PROC-6: download an attachment. Gated by the RECORD's read access (not by {@code /api/files}, which only
     * checks the key's tenant prefix). Same hardened headers as {@code FileUploadController#download}.
     */
    @GetMapping("/{concept}/{id}/attachments/{commentId}")
    public void download(HttpServletRequest request, HttpServletResponse response, @PathVariable String concept,
                         @PathVariable String id, @PathVariable String commentId) throws IOException {
        ExecutionContext context = runtimeContextService.currentContext(request);
        FileHandle handle;
        try {
            handle = service.openAttachment(concept, id, commentId, context);
        } catch (CommentException e) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        boolean inlineSafe = INLINE_SAFE_CONTENT_TYPES.contains(handle.contentType().toLowerCase(Locale.ROOT));
        response.setContentType(handle.contentType());
        response.setHeader("Content-Disposition",
                (inlineSafe ? "inline" : "attachment") + "; filename=\"" + safeName(handle.originalName()) + "\"");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Content-Security-Policy", "default-src 'none'; sandbox");
        try {
            service.copyAttachment(handle, response.getOutputStream());
        } catch (CommentException e) {
            if (!response.isCommitted()) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND);
            }
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

    private static String safeName(String value) {
        return (value == null ? "file" : value).replace("\"", "'").replace("\r", "").replace("\n", "");
    }

    private static ResponseEntity<Map<String, Object>> failure(CommentException e) {
        HttpStatus status = switch (e.failure()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BODY_INVALID -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case UNSUPPORTED_TYPE -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
        };
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.failure().name());
        body.put("message", e.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
