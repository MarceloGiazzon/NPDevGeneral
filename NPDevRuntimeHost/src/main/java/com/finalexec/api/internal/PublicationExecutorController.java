package com.finalexec.api.internal;

import com.finalexec.api.*;

import com.finalexec.npdev.dto.PublicationExecutionRequest;
import com.finalexec.npdev.service.internal.PublicationExecutorService;
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
@RequestMapping({"/api/v1/admin/publication-executor", "/api/admin/publication-executor"})
public class PublicationExecutorController {

    private final PublicationExecutorService publicationExecutorService;
    private final RuntimeContextService runtimeContextService;

    public PublicationExecutorController(
            PublicationExecutorService publicationExecutorService,
            RuntimeContextService runtimeContextService
    ) {
        this.publicationExecutorService = publicationExecutorService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getSummary(HttpServletRequest request) {
        requireAdminContext(request);
        return publicationExecutorService.summary();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(HttpServletRequest request) {
        requireAdminContext(request);
        return publicationExecutorService.history();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> execute(
            HttpServletRequest request,
            @RequestBody PublicationExecutionRequest body
    ) {
        requireAdminContext(request);

        try {
            return publicationExecutorService.executePublication(body);
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
