package com.finalexec.api.internal;

import com.finalexec.api.*;
import com.finalexec.api.experimental.*;

import com.finalexec.npdev.dto.PublicationRollbackExecutionRequest;
import com.finalexec.npdev.service.internal.PublicationRollbackExecutorService;
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
@RequestMapping({"/api/v1/admin/publication-rollback", "/api/admin/publication-rollback"})
public class PublicationRollbackExecutorController {

    private final PublicationRollbackExecutorService publicationRollbackExecutorService;
    private final RuntimeContextService runtimeContextService;

    public PublicationRollbackExecutorController(
            PublicationRollbackExecutorService publicationRollbackExecutorService,
            RuntimeContextService runtimeContextService
    ) {
        this.publicationRollbackExecutorService = publicationRollbackExecutorService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getSummary(HttpServletRequest request) {
        requireAdminContext(request);
        return publicationRollbackExecutorService.summary();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(HttpServletRequest request) {
        requireAdminContext(request);
        return publicationRollbackExecutorService.history();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> execute(
            HttpServletRequest request,
            @RequestBody PublicationRollbackExecutionRequest body
    ) {
        requireAdminContext(request);

        try {
            return publicationRollbackExecutorService.execute(body);
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
