package com.finalexec.api.internal;

import com.finalexec.api.*;
import com.finalexec.api.experimental.*;

import com.finalexec.npdev.dto.SemanticPublicationMappingRequest;
import com.finalexec.npdev.service.internal.SemanticPublicationMappingService;
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
@RequestMapping({"/api/v1/admin/semantic-publication-mapping", "/api/admin/semantic-publication-mapping"})
public class SemanticPublicationMappingController {

    private final SemanticPublicationMappingService semanticPublicationMappingService;
    private final RuntimeContextService runtimeContextService;

    public SemanticPublicationMappingController(
            SemanticPublicationMappingService semanticPublicationMappingService,
            RuntimeContextService runtimeContextService
    ) {
        this.semanticPublicationMappingService = semanticPublicationMappingService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getSummary(HttpServletRequest request) {
        requireAdminContext(request);
        return semanticPublicationMappingService.summary();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(HttpServletRequest request) {
        requireAdminContext(request);
        return semanticPublicationMappingService.history();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> map(
            HttpServletRequest request,
            @RequestBody SemanticPublicationMappingRequest body
    ) {
        requireAdminContext(request);

        try {
            return semanticPublicationMappingService.mapRequest(body);
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
