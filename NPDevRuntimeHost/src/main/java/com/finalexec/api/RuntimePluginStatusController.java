package com.finalexec.api;

import com.finalexec.npdev.service.RuntimePluginStatusSummary;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1/admin/runtime/plugin-status", "/api/admin/runtime/plugin-status"})
public class RuntimePluginStatusController {

    private final RuntimePluginStatusSummary runtimePluginStatusSummary;
    private final RuntimeContextService runtimeContextService;

    public RuntimePluginStatusController(
            RuntimePluginStatusSummary runtimePluginStatusSummary,
            RuntimeContextService runtimeContextService
    ) {
        this.runtimePluginStatusSummary = runtimePluginStatusSummary;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getPluginStatus(HttpServletRequest request) {
        requireAdminContext(request);
        try {
            return runtimePluginStatusSummary.toSummary();
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    private void requireAdminContext(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
