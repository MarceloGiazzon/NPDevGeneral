package com.finalexec.controlpanel;

import com.finalexec.npdev.service.CredentialRegistryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * SEC-8 (B17, {@code BOUNDARY_LIFT_PLAN_2026-09-02.md} work package 1.4): the claim flow --
 * {@code SuperUserBootstrapper}'s own credential (issued, hash-supplied, or raw-supplied) is meant
 * to be a bootstrap HANDOFF, not a permanent identity. This is the one call an operator makes to
 * turn it into a named administrator: authenticate with the bootstrap key, name a real administrator
 * (a fresh, independent SUPERUSER credential under a chosen {@code actorId}), and revoke EXACTLY the
 * credential that authenticated this call -- so a normal claim never touches any OTHER active
 * SUPERUSER credential a deployment might already hold (e.g. from a prior claim, or a
 * {@code force-reissue} predating this one).
 *
 * <p>Requires the LIVE super-key path specifically ({@link
 * SuperUserCredentialAuthFilter#SUPER_USER_AUTHENTICATED_ATTRIBUTE}, the same REG-22 marker {@code
 * ActuatorAdminGuardFilter} gates on) -- a business JWT that happens to carry a SUPERUSER role must
 * not be able to mint a new Super User credential and revoke the caller's own. There is no
 * transaction spanning issue+revoke (the credential table has no cross-row transactional need
 * elsewhere in this class either); the issue happens first, so a failure revoking the old credential
 * still leaves the operator holding a working new key rather than locked out entirely.</p>
 */
@RestController
@RequestMapping("/api/admin/superuser")
public class SuperUserClaimController {

    private static final String SUPERUSER_ROLE = "SUPERUSER";
    private static final String SYSTEM_TENANT_ID = "__system__";

    private final CredentialRegistryService credentialRegistryService;

    public SuperUserClaimController(CredentialRegistryService credentialRegistryService) {
        this.credentialRegistryService = credentialRegistryService;
    }

    public record ClaimRequest(String actorId) {
    }

    @PostMapping("/claim")
    public ResponseEntity<Map<String, Object>> claim(
            @RequestBody(required = false) ClaimRequest request, HttpServletRequest httpRequest
    ) {
        boolean viaSuperKey = Boolean.TRUE.equals(httpRequest.getAttribute(
                SuperUserCredentialAuthFilter.SUPER_USER_AUTHENTICATED_ATTRIBUTE));
        if (!viaSuperKey) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "claim_requires_super_user_key",
                    "boundaryId", "B17",
                    "message", "The claim endpoint must be called with a valid X-Super-User-Key header -- "
                            + "a business API key or JWT carrying a SUPERUSER role is not accepted here."));
        }
        String actorId = request == null ? null : request.actorId();
        if (actorId == null || actorId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "actorId is required"));
        }

        String presentedKey = httpRequest.getHeader(SuperUserCredentialAuthFilter.HEADER_NAME);
        java.util.Optional<String> bootstrapCredentialId = credentialRegistryService.credentialIdForKey(presentedKey);

        Map<String, Object> issued = credentialRegistryService.issue(
                SYSTEM_TENANT_ID, actorId.trim(), Set.of(SUPERUSER_ROLE));

        boolean bootstrapRevoked = false;
        if (bootstrapCredentialId.isPresent()) {
            credentialRegistryService.revoke(bootstrapCredentialId.get());
            bootstrapRevoked = true;
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apiKey", issued.get("apiKey"));
        body.put("actorId", issued.get("actorId"));
        body.put("bootstrapCredentialRevoked", bootstrapRevoked);
        body.put("warning", "This key is shown once and is not retrievable again. Store it now.");
        return ResponseEntity.ok(body);
    }
}
