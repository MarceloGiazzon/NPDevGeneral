package com.finalexec.api;

import com.finalexec.npdev.service.BetaSecurityRoleEvaluator;
import com.finalexec.npdev.service.SupportDiagnosticsService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class SupportDiagnosticsController {

    private final SupportDiagnosticsService supportDiagnosticsService;
    private final BetaSecurityRoleEvaluator betaSecurityRoleEvaluator;
    private final RuntimeContextService runtimeContextService;

    public SupportDiagnosticsController(
            SupportDiagnosticsService supportDiagnosticsService,
            BetaSecurityRoleEvaluator betaSecurityRoleEvaluator,
            RuntimeContextService runtimeContextService
    ) {
        this.supportDiagnosticsService = supportDiagnosticsService;
        this.betaSecurityRoleEvaluator = betaSecurityRoleEvaluator;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping({"/api/v1/support/diagnostics", "/api/support/diagnostics"})
    public Map<String, Object> diagnostics(HttpServletRequest request) {
        return run(() -> supportDiagnosticsService.diagnostics(requirePrivilegedContext(request)));
    }

    @GetMapping({"/api/v1/support/issues", "/api/support/issues"})
    public Map<String, Object> issues(HttpServletRequest request) {
        return run(() -> supportDiagnosticsService.issues(requirePrivilegedContext(request)));
    }

    @GetMapping({"/api/v1/support/traces", "/api/support/traces"})
    public Map<String, Object> traces(HttpServletRequest request) {
        return run(() -> supportDiagnosticsService.traces(requirePrivilegedContext(request)));
    }

    @GetMapping({"/api/v1/support/blocked-states", "/api/support/blocked-states"})
    public Map<String, Object> blockedStates(HttpServletRequest request) {
        return run(() -> supportDiagnosticsService.blockedStates(requirePrivilegedContext(request)));
    }

    private ExecutionContext requirePrivilegedContext(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!betaSecurityRoleEvaluator.hasPrivilegedAccess(context)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Support diagnostics require ADMIN, OPERATOR, or SUPPORT role claims."
            );
        }
        return context;
    }

    private Map<String, Object> run(SupportDiagnosticsCall call) {
        try {
            return call.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    @FunctionalInterface
    private interface SupportDiagnosticsCall {
        Map<String, Object> get();
    }
}
