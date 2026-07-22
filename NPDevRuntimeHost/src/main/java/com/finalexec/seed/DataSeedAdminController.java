package com.finalexec.seed;

import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Admin surface for named seed/mock datasets declared under an app's {@code definition/seeds/}
 * (see {@link SeedDataService}). ADMIN- or SUPERUSER-gated, following the same manual
 * {@code requireAdmin} guard every other hand-written admin controller in this package uses (e.g.
 * {@code com.finalexec.api.TenantAdminController}) rather than an annotation.
 */
@RestController
@RequestMapping("/api/admin/seeds")
public class DataSeedAdminController {

    private final SeedDataService seedDataService;
    private final RuntimeContextService runtimeContextService;

    public DataSeedAdminController(SeedDataService seedDataService, RuntimeContextService runtimeContextService) {
        this.seedDataService = seedDataService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        requireAdmin(request);
        return seedDataService.listAvailable();
    }

    /**
     * {@code tenantId} lets a Super User (whose own context has no real business tenant) target a
     * seed run at a specific tenant, e.g. one just created via {@code TenantAdminController}. An
     * ordinary business ADMIN's context is already scoped to their own tenant and must not be able
     * to redirect a seed run into a tenant they don't belong to, so the override itself requires
     * SUPERUSER, not just ADMIN.
     */
    @PostMapping("/{id}/run")
    public SeedDataService.SeedRunResult run(
            HttpServletRequest request, @PathVariable String id,
            @RequestParam(required = false) String tenantId
    ) {
        ExecutionContext context = requireAdmin(request);
        ExecutionContext effectiveContext = context;
        if (tenantId != null && !tenantId.isBlank()) {
            if (!context.roles().contains("SUPERUSER")) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only a Super User may target another tenant's seed run");
            }
            effectiveContext = new ExecutionContext(tenantId, context.actorId(), context.tags(), context.roles());
        }
        try {
            return seedDataService.run(id, effectiveContext);
        } catch (SeedDataService.SeedNotFoundException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, exception.getMessage());
        } catch (SeedDataService.SeedLoadException | IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
    }

    private ExecutionContext requireAdmin(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.roles().contains("ADMIN") && !context.roles().contains("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return context;
    }
}
