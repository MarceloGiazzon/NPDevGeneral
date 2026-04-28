package com.finalexec.api;

import com.finalexec.execution.DirectExecutionGateway;
import com.finalexec.execution.DirectExecutionRequest;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class DirectExecutionGatewayController {

    private final DirectExecutionGateway directExecutionGateway;
    private final RuntimeContextService runtimeContextService;

    public DirectExecutionGatewayController(
            DirectExecutionGateway directExecutionGateway,
            RuntimeContextService runtimeContextService
    ) {
        this.directExecutionGateway = directExecutionGateway;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping({"/api/v1/admin/direct-execution-gateway", "/api/admin/direct-execution-gateway"})
    public Map<String, Object> getSummary(HttpServletRequest request) {
        requireAdminContext(request);
        try {
            return directExecutionGateway.summary();
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    @GetMapping({"/api/v1/admin/direct-execution-gateway/history", "/api/admin/direct-execution-gateway/history"})
    public Map<String, Object> getHistory(HttpServletRequest request) {
        requireAdminContext(request);
        try {
            return directExecutionGateway.history();
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    @PostMapping({"/api/v1/execute/flow", "/api/execute/flow"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> executeFlow(
            HttpServletRequest request,
            @RequestBody DirectExecutionRequest body
    ) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        try {
            return directExecutionGateway.execute(body, context);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (SecurityException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    @PostMapping({"/api/v1/execute/panel-action", "/api/execute/panel-action"})
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> executePanelAction(
            HttpServletRequest request,
            @RequestBody DirectExecutionRequest body
    ) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        try {
            return directExecutionGateway.executePanelAction(body, context);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (SecurityException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    private void requireAdminContext(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
