package com.finalexec.api;


import com.finalexec.npdev.dto.StructuralPublicationMappingRequest;
import com.finalexec.npdev.service.StructuralPublicationMappingService;
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
        requireSuperUser(request);
        return structuralPublicationMappingService.history();
    }

    @PostMapping("/map")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> map(
            HttpServletRequest request,
            @RequestBody StructuralPublicationMappingRequest body
    ) {
        requireSuperUser(request);

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

    // History and every POST: SUPERUSER, not ADMIN. These records live in one untenanted store and the
    // POSTs drive the shared model/source pipeline, while in an auth.mode=none app every anonymous
    // caller holds ADMIN -- same posture as MetadataHotSwapController /apply.
    private void requireSuperUser(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
