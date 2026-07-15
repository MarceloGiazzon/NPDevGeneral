package com.finalexec.seed;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * LNCH-9: {@code GET /api/admin/export} -- the user-level "export my data" escape hatch. Same
 * ADMIN/SUPERUSER gate and same {@code tenantId} override rule as
 * {@link DataSeedAdminController} (an ordinary business ADMIN's context is already scoped to
 * their own tenant; redirecting the export at another tenant requires SUPERUSER).
 */
@RestController
@RequestMapping("/api/admin/export")
public class TenantExportController {

    private final TenantExportService tenantExportService;
    private final RuntimeContextService runtimeContextService;

    public TenantExportController(TenantExportService tenantExportService, RuntimeContextService runtimeContextService) {
        this.tenantExportService = tenantExportService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public ObjectNode export(HttpServletRequest request, @RequestParam(required = false) String tenantId) {
        ExecutionContext context = requireAdmin(request);
        ExecutionContext effectiveContext = context;
        if (tenantId != null && !tenantId.isBlank()) {
            if (!context.roles().contains("SUPERUSER")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a Super User may export another tenant's data");
            }
            effectiveContext = new ExecutionContext(tenantId, context.actorId(), context.tags(), context.roles());
        }
        return tenantExportService.export(effectiveContext);
    }

    private ExecutionContext requireAdmin(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.roles().contains("ADMIN") && !context.roles().contains("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return context;
    }
}
