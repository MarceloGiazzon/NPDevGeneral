package com.finalexec.api;

import com.finalexec.npdev.service.TenantRegistryService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Admin surface for the runtime tenant lifecycle (create / list / enable / disable). ADMIN-gated.
 * Membership (who is in a tenant with which role) is managed separately through the identity-pack
 * CRUD; this controller manages the tenants themselves and their suspension status.
 */
@RestController
@RequestMapping("/api/admin/tenants")
public class TenantAdminController {

    private final TenantRegistryService tenantRegistryService;
    private final RuntimeContextService runtimeContextService;

    public TenantAdminController(
            TenantRegistryService tenantRegistryService,
            RuntimeContextService runtimeContextService
    ) {
        this.tenantRegistryService = tenantRegistryService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        requireAdmin(request);
        return run(tenantRegistryService::list);
    }

    @PostMapping
    public Map<String, Object> create(HttpServletRequest request, @RequestBody CreateRequest body) {
        requireAdmin(request);
        return run(() -> tenantRegistryService.create(
                body == null ? null : body.tenantId(),
                body == null ? null : body.displayName()));
    }

    @PostMapping("/{tenantId}/disable")
    public Map<String, Object> disable(HttpServletRequest request, @PathVariable String tenantId) {
        requireAdmin(request);
        return run(() -> tenantRegistryService.setStatus(tenantId, TenantRegistryService.Status.DISABLED));
    }

    @PostMapping("/{tenantId}/enable")
    public Map<String, Object> enable(HttpServletRequest request, @PathVariable String tenantId) {
        requireAdmin(request);
        return run(() -> tenantRegistryService.setStatus(tenantId, TenantRegistryService.Status.ACTIVE));
    }

    private <T> T run(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    private void requireAdmin(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.roles().contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    public record CreateRequest(String tenantId, String displayName) {
    }
}
