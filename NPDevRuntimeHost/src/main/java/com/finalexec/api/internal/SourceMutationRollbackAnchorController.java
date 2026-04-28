package com.finalexec.api.internal;

import com.finalexec.api.*;
import com.finalexec.api.experimental.*;

import com.finalexec.npdev.dto.SourceMutationRollbackAnchorCreateRequest;
import com.finalexec.npdev.service.internal.SourceMutationRollbackAnchorService;
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
@RequestMapping({"/api/v1/admin/source-mutation-rollback-anchor", "/api/admin/source-mutation-rollback-anchor"})
public class SourceMutationRollbackAnchorController {

    private final SourceMutationRollbackAnchorService sourceMutationRollbackAnchorService;
    private final RuntimeContextService runtimeContextService;

    public SourceMutationRollbackAnchorController(
            SourceMutationRollbackAnchorService sourceMutationRollbackAnchorService,
            RuntimeContextService runtimeContextService
    ) {
        this.sourceMutationRollbackAnchorService = sourceMutationRollbackAnchorService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getSummary(HttpServletRequest request) {
        requireAdminContext(request);
        return sourceMutationRollbackAnchorService.summary();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(HttpServletRequest request) {
        requireAdminContext(request);
        return sourceMutationRollbackAnchorService.history();
    }

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> create(
            HttpServletRequest request,
            @RequestBody SourceMutationRollbackAnchorCreateRequest body
    ) {
        requireAdminContext(request);

        try {
            return sourceMutationRollbackAnchorService.createAnchor(body);
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
