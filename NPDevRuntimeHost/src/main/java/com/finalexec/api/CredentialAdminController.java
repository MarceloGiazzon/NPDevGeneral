package com.finalexec.api;

import com.finalexec.npdev.service.CredentialRegistryService;
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
import java.util.Set;
import java.util.function.Supplier;

/**
 * Admin surface for runtime API-key credential issuance (T5: completes the tenant lifecycle T4
 * started -- a tenant can now be onboarded AND given a working key without a regenerate/restart).
 * ADMIN-gated. The raw key is returned exactly once by {@code POST}; it is never retrievable again.
 */
@RestController
@RequestMapping("/api/admin/credentials")
public class CredentialAdminController {

    private final CredentialRegistryService credentialRegistryService;
    private final RuntimeContextService runtimeContextService;

    public CredentialAdminController(
            CredentialRegistryService credentialRegistryService,
            RuntimeContextService runtimeContextService
    ) {
        this.credentialRegistryService = credentialRegistryService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public List<Map<String, Object>> list(HttpServletRequest request) {
        requireAdmin(request);
        return run(credentialRegistryService::list);
    }

    @PostMapping
    public Map<String, Object> issue(HttpServletRequest request, @RequestBody IssueRequest body) {
        requireAdmin(request);
        Set<String> roles = body == null || body.roles() == null ? Set.of() : Set.copyOf(body.roles());
        return run(() -> credentialRegistryService.issue(
                body == null ? null : body.tenantId(),
                body == null ? null : body.actorId(),
                roles));
    }

    @PostMapping("/{credentialId}/revoke")
    public Map<String, Object> revoke(HttpServletRequest request, @PathVariable String credentialId) {
        requireAdmin(request);
        run(() -> {
            credentialRegistryService.revoke(credentialId);
            return null;
        });
        return Map.of("credentialId", credentialId, "status", "REVOKED");
    }

    private <T> T run(Supplier<T> call) {
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

    public record IssueRequest(String tenantId, String actorId, List<String> roles) {
    }
}
