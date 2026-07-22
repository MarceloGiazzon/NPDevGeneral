package com.finalexec.api.internal;

import com.finalexec.api.*;

import com.finalexec.npdev.dto.SourceMutationApprovalDecisionRequest;
import com.finalexec.npdev.service.internal.SourceMutationApprovalGateService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1/admin/source-mutation-approval", "/api/admin/source-mutation-approval"})
public class SourceMutationApprovalGateController {

    private final SourceMutationApprovalGateService sourceMutationApprovalGateService;
    private final RuntimeContextService runtimeContextService;

    public SourceMutationApprovalGateController(
            SourceMutationApprovalGateService sourceMutationApprovalGateService,
            RuntimeContextService runtimeContextService
    ) {
        this.sourceMutationApprovalGateService = sourceMutationApprovalGateService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getSummary(HttpServletRequest request) {
        requireAdminContext(request);
        return sourceMutationApprovalGateService.summary();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(HttpServletRequest request) {
        requireAdminContext(request);
        return sourceMutationApprovalGateService.history();
    }

    @PostMapping("/decision")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> recordDecision(
            HttpServletRequest request,
            @RequestBody SourceMutationApprovalDecisionRequest body
    ) {
        requireAdminContext(request);

        try {
            return sourceMutationApprovalGateService.recordDecision(body);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private void requireAdminContext(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
