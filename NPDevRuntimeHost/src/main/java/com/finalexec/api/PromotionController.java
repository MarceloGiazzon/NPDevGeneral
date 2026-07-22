package com.finalexec.api;

import com.finalexec.npdev.service.PromotionStateService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Admin surface for the S0-S8 promotion-stage model. See {@link PromotionStateService} for the gate
 * rules this enforces (no stage-skipping; evidence required from S5 on; ADMIN role required to
 * record S7 ReleaseApproved or S8 Released).
 */
@RestController
@RequestMapping("/api/admin/promotion")
public class PromotionController {

    private final PromotionStateService promotionStateService;
    private final RuntimeContextService runtimeContextService;

    public PromotionController(
            PromotionStateService promotionStateService,
            RuntimeContextService runtimeContextService
    ) {
        this.promotionStateService = promotionStateService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> current(HttpServletRequest request) {
        requireAdminContext(request);
        return run(promotionStateService::currentState);
    }

    @PostMapping("/advance")
    public Map<String, Object> advance(HttpServletRequest request, @RequestBody AdvanceRequest body) {
        ExecutionContext context = requireAdminContextOrAnyAuthenticated(request);
        PromotionStateService.Stage targetStage = parseStage(body == null ? null : body.stage());
        try {
            return promotionStateService.advance(targetStage, body == null ? null : body.evidence(), context);
        } catch (PromotionStateService.PromotionRejectedException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    private PromotionStateService.Stage parseStage(String stage) {
        if (stage == null || stage.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "stage is required");
        }
        try {
            return PromotionStateService.Stage.valueOf(stage.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown stage: " + stage);
        }
    }

    private Map<String, Object> run(java.util.function.Supplier<Map<String, Object>> call) {
        try {
            return call.get();
        } catch (IllegalStateException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
        }
    }

    private void requireAdminContext(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.roles().contains("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    // Any authenticated caller may attempt to advance (the gate inside PromotionStateService is what
    // actually enforces the ADMIN requirement for S7/S8, so that a non-admin's attempt is recorded as
    // a real REJECTED audit event rather than bouncing before it's ever logged).
    private ExecutionContext requireAdminContextOrAnyAuthenticated(HttpServletRequest request) {
        return runtimeContextService.currentContext(request);
    }

    public record AdvanceRequest(String stage, String evidence) {
    }
}
