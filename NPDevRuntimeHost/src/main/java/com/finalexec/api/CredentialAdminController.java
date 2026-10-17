package com.finalexec.api;

import com.finalexec.config.ModelHolder;
import com.finalexec.npdev.service.CredentialAdminPolicy;
import com.finalexec.npdev.service.CredentialRegistryService;
import com.npdev.dsl.v1.compiled.CompiledRole;
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

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Admin surface for runtime API-key credential issuance (T5: completes the tenant lifecycle T4
 * started -- a tenant can now be onboarded AND given a working key without a regenerate/restart).
 * ADMIN-gated. The raw key is returned exactly once by {@code POST}; it is never retrievable again.
 *
 * <p>INT-8: a SUPERUSER administers every tenant's credentials; an ADMIN without SUPERUSER is pinned
 * to its own tenant (cannot issue for, list or revoke another tenant's keys). Requested roles must be
 * built-in or declared in the model. A key can carry a {@code label} and an {@code expiresAt}; the
 * list shows {@code lastUsedAtMs}. See {@link CredentialAdminPolicy}.</p>
 */
@RestController
@RequestMapping("/api/admin/credentials")
public class CredentialAdminController {

    private final CredentialRegistryService credentialRegistryService;
    private final RuntimeContextService runtimeContextService;
    private final ModelHolder modelHolder;

    public CredentialAdminController(
            CredentialRegistryService credentialRegistryService,
            RuntimeContextService runtimeContextService,
            ModelHolder modelHolder
    ) {
        this.credentialRegistryService = credentialRegistryService;
        this.runtimeContextService = runtimeContextService;
        this.modelHolder = modelHolder;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        ExecutionContext context = requireAdmin(request);
        String filter = CredentialAdminPolicy.tenantFilter(isSuperUser(context), context.tenantId());
        return run(() -> credentialRegistryService.list(filter));
    }

    @PostMapping
    public Map<String, Object> issue(HttpServletRequest request, @RequestBody IssueRequest body) {
        ExecutionContext context = requireAdmin(request);
        boolean superUser = isSuperUser(context);
        return run(() -> {
            String tenant = CredentialAdminPolicy.effectiveTenant(
                    superUser, context.tenantId(), body == null ? null : body.tenantId());
            Set<String> roles = CredentialAdminPolicy.validateRoles(
                    body == null ? null : body.roles(), declaredRoleNames(), superUser);
            Long expiresAtMs = CredentialAdminPolicy.parseExpiry(
                    body == null ? null : body.expiresAt(), Clock.systemUTC());
            return credentialRegistryService.issue(
                    tenant,
                    body == null ? null : body.actorId(),
                    roles,
                    body == null ? null : body.label(),
                    expiresAtMs,
                    context.actorId());
        });
    }

    @PostMapping("/{credentialId}/revoke")
    public Map<String, Object> revoke(HttpServletRequest request, @PathVariable String credentialId) {
        ExecutionContext context = requireAdmin(request);
        String filter = CredentialAdminPolicy.tenantFilter(isSuperUser(context), context.tenantId());
        run(() -> {
            credentialRegistryService.revoke(credentialId, filter);
            return null;
        });
        return Map.of("credentialId", credentialId, "status", "REVOKED");
    }

    private <T> T run(Supplier<T> call) {
        try {
            return call.get();
        } catch (CredentialAdminPolicy.ForbiddenException exception) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    private ExecutionContext requireAdmin(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.roles().contains("ADMIN") && !isSuperUser(context)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
        return context;
    }

    private static boolean isSuperUser(ExecutionContext context) {
        return context.hasRole("SUPERUSER");
    }

    private List<String> declaredRoleNames() {
        List<CompiledRole> roles = modelHolder.get().getRoles();
        return roles == null ? List.of() : roles.stream().map(CompiledRole::name).toList();
    }

    public record IssueRequest(String tenantId, String actorId, List<String> roles, String label, String expiresAt) {
    }
}
