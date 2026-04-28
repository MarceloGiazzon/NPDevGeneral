package com.finalexec.api;

import com.finalexec.npdev.service.ExecutionMonitorService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
