package com.finalexec.api;

import com.finalexec.config.ModelHolder;
import com.finalexec.tasks.HumanTaskService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.KernelRunner;
import com.npdev.kernel.ports.FlowInstanceStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * PROC-2: the human-task inbox. Thin HTTP shell over {@link HumanTaskService}; every rule (who may
 * see a task, who may complete it, form validation, the event it publishes) lives there.
 *
 * <pre>
 *   GET  /api/tasks                          -> {items:[...], count}   open tasks the caller may answer
 *   GET  /api/tasks/{executionId}            -> one task with its form (fields, outcomes)
 *   POST /api/tasks/{executionId}/complete   {stepName, outcome, values} -> {outcome, eventId, executionStatus}
 * </pre>
 *
 * Errors are {@code {error: CODE, message}}: TASK_NOT_FOUND 404, TASK_FORBIDDEN 403, TASK_NOT_OPEN 409,
 * TASK_OUTCOME_INVALID / TASK_FIELD_INVALID 400, TASK_PUBLISH_REFUSED 409. Under {@code /api/}, so the
 * JWT / api-key filters already require a credential.
 */
@RestController
@RequestMapping({"/api/tasks", "/api/v1/tasks"})
public class HumanTaskController {

    private final HumanTaskService service;
    private final RuntimeContextService runtimeContextService;

    @Autowired
    public HumanTaskController(
            ModelHolder modelHolder,
            FlowInstanceStore flowInstanceStore,
            ObjectProvider<KernelRunner> kernelRunner,
            RuntimeContextService runtimeContextService
    ) {
        this(new HumanTaskService(modelHolder::get, flowInstanceStore,
                        (name, payload, correlationId, causationId, ctx) ->
                                kernelRunner.getObject().publishExternalEvent(name, payload, correlationId, causationId, ctx)),
                runtimeContextService);
    }

    HumanTaskController(HumanTaskService service, RuntimeContextService runtimeContextService) {
        this.service = service;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        return guarded(() -> {
            List<Map<String, Object>> items = service.myTasks(context(request));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", items);
            body.put("count", items.size());
            return body;
        });
    }

    @GetMapping("/{executionId}")
    public ResponseEntity<?> detail(HttpServletRequest request, @PathVariable String executionId) {
        return guarded(() -> service.detail(executionId, context(request)));
    }

    @PostMapping("/{executionId}/complete")
    public ResponseEntity<?> complete(HttpServletRequest request, @PathVariable String executionId,
                                      @RequestBody Map<String, Object> body) {
        return guarded(() -> {
            Object stepName = body.get("stepName");
            Object outcome = body.get("outcome");
            Map<String, Object> values = body.get("values") instanceof Map<?, ?> raw ? toStringKeys(raw) : Map.of();
            return service.complete(executionId,
                    stepName == null ? null : String.valueOf(stepName),
                    outcome == null ? null : String.valueOf(outcome),
                    values, context(request));
        });
    }

    private ExecutionContext context(HttpServletRequest request) {
        return runtimeContextService.currentContext(request);
    }

    private static Map<String, Object> toStringKeys(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private ResponseEntity<?> guarded(Supplier<Object> call) {
        try {
            return ResponseEntity.ok(call.get());
        } catch (HumanTaskService.TaskException e) {
            return error(statusFor(e.code()), e.code(), e.getMessage());
        } catch (IllegalStateException e) {
            // KernelRunner.publishExternalEvent: permission or correlation-owner refusal
            return error(HttpStatus.CONFLICT, "TASK_PUBLISH_REFUSED", e.getMessage());
        }
    }

    private static HttpStatus statusFor(String code) {
        return switch (code) {
            case "TASK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "TASK_FORBIDDEN" -> HttpStatus.FORBIDDEN;
            case "TASK_NOT_OPEN" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private static ResponseEntity<?> error(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
}
