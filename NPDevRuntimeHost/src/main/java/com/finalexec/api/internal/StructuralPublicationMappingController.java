package com.finalexec.api.internal;

import com.finalexec.api.*;

import com.finalexec.npdev.dto.StructuralPublicationMappingRequest;
import com.finalexec.npdev.service.internal.StructuralPublicationMappingService;
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
@RequestMapping({"/api/v1/admin/structural-publication-mapping", "/api/admin/structural-publication-mapping"})
public class StructuralPublicationMappingController {

    private final StructuralPublicationMappingService structuralPublicationMappingService;
    private final RuntimeContextService runtimeContextService;

    public StructuralPublicationMappingController(
            StructuralPublicationMappingService structuralPublicationMappingService,
            RuntimeContextService runtimeContextService
    ) {
        this.structuralPublicationMappingService = structuralPublicationMappingService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getSummary(HttpServletRequest request) {
        requireAdminContext(request);
        return structuralPublicationMappingService.summary();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(HttpServletRequest request) {
        requireAdminContext(request);
        return structuralPublicationMappingService.history();
    }

    @PostMapping("/map")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> map(
            HttpServletRequest request,
            @RequestBody StructuralPublicationMappingRequest body
    ) {
        requireAdminContext(request);

        try {
            return structuralPublicationMappingService.mapRequest(body);
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
