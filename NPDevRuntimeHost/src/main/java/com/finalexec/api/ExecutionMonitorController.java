package com.finalexec.api;

import com.finalexec.npdev.service.ExecutionMonitorService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class ExecutionMonitorController {

    private static final String GENERATED_EXECUTION_DETAIL_ROUTE = "/api/executions/{executionId}";

    private final ExecutionMonitorService executionMonitorService;
    private final RuntimeContextService runtimeContextService;

    public ExecutionMonitorController(
            ExecutionMonitorService executionMonitorService,
            RuntimeContextService runtimeContextService
    ) {
        this.executionMonitorService = executionMonitorService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping({"/api/v1/executions/active", "/api/executions/active"})
    public Map<String, Object> active(HttpServletRequest request) {
        return run(() -> executionMonitorService.active(currentContext(request)));
    }

    @GetMapping({"/api/v1/executions/history", "/api/executions/history"})
    public Map<String, Object> history(HttpServletRequest request) {
        return run(() -> executionMonitorService.history(currentContext(request)));
    }

    /**
     * R2.2: the stuck queue. A literal segment, so it out-ranks the generated
     * {@code ExecutionQueryController}'s {@code /api/executions/{executionId}} the same way the
     * sibling {@code /active} and {@code /history} routes above already do.
     *
     * <p>Read posture matches the rest of this controller -- any authenticated caller. Seeing which
     * executions need attention is the same class of information {@code /active} already returns;
     * the SUPERUSER gate belongs on the write below, not on looking.
     */
    @GetMapping({"/api/v1/executions/stuck", "/api/executions/stuck"})
    public Map<String, Object> stuck(HttpServletRequest request) {
        return run(() -> executionMonitorService.stuck(currentContext(request)));
    }

    /**
     * R2.2: hand a STUCK execution back to the resume sweep.
     *
     * <p><b>SUPERUSER, not ADMIN, and that distinction is the whole gate.</b> Copied verbatim from
     * {@code AgentProxyController.requireSuperUser}, which documents the measurement: in an
     * {@code auth.mode=none} app the generated {@code RuntimeContextService} hands ADMIN to every
     * anonymous caller, so {@code hasRole("ADMIN")} is no gate at all in exactly the dev apps most
     * likely to be exposed. SUPERUSER is never in that fallback set. The neighbouring generated
     * {@code AdminController.requireAdminContext} is the precedent NOT to copy here.
     *
     * <p>After R2.1 a STUCK instance is always one whose resume genuinely kept throwing, so
     * un-sticking is an assertion that the underlying fault is fixed -- a claim only an operator can
     * make, and a cheap way to re-run a failing capability call in a loop if anyone can make it.
     */
    @PostMapping({"/api/v1/executions/{executionId}/unstick", "/api/executions/{executionId}/unstick"})
    public ResponseEntity<Map<String, Object>> unstick(
            @PathVariable String executionId,
            HttpServletRequest request
    ) {
        requireSuperUser(request);
        Map<String, Object> result = executionMonitorService.unstick(executionId, currentContext(request));
        return ResponseEntity.status(statusFor(result.get("outcome"))).body(result);
    }

    private static HttpStatus statusFor(Object outcome) {
        return switch (String.valueOf(outcome)) {
            case "UNSTUCK" -> HttpStatus.OK;
            case "NOT_FOUND" -> HttpStatus.NOT_FOUND;
            // Not STUCK (or STUCK with no awaited event to return to): the request is well-formed
            // and the caller is allowed, the instance is simply in the wrong state for it.
            default -> HttpStatus.CONFLICT;
        };
    }

    private void requireSuperUser(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    // Detail remains served by the generated route: /api/executions/{executionId}
    @GetMapping({"/api/v1/executions/{executionId}/links", "/api/executions/{executionId}/links"})
    public Map<String, Object> links(
            @PathVariable String executionId,
            HttpServletRequest request
    ) {
        try {
            return executionMonitorService.links(executionId, currentContext(request));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (SecurityException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @GetMapping({"/api/v1/executions/detail-route", "/api/executions/detail-route"})
    public Map<String, Object> detailRoute() {
        return Map.of(
                "surfaceName", "Execution Monitor",
                "detailRouteTemplate", GENERATED_EXECUTION_DETAIL_ROUTE
        );
    }

    private ExecutionContext currentContext(HttpServletRequest request) {
        return runtimeContextService.currentContext(request);
    }

    private Map<String, Object> run(ExecutionMonitorCall call) {
        try {
            return call.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (SecurityException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @FunctionalInterface
    private interface ExecutionMonitorCall {
        Map<String, Object> get();
    }
}
