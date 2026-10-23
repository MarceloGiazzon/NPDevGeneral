package com.finalexec.api;


import com.finalexec.npdev.dto.RealPublicationExecutionRequest;
import com.finalexec.npdev.service.RealPublicationExecutorService;
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
@RequestMapping({"/api/v1/admin/real-publication-executor", "/api/admin/real-publication-executor"})
public class RealPublicationExecutorController {

    private final RealPublicationExecutorService realPublicationExecutorService;
    private final RuntimeContextService runtimeContextService;

    public RealPublicationExecutorController(
            RealPublicationExecutorService realPublicationExecutorService,
            RuntimeContextService runtimeContextService
    ) {
        this.realPublicationExecutorService = realPublicationExecutorService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getSummary(HttpServletRequest request) {
        requireAdminContext(request);
        return realPublicationExecutorService.summary();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(HttpServletRequest request) {
        requireSuperUser(request);
        return realPublicationExecutorService.history();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> execute(
            HttpServletRequest request,
            @RequestBody RealPublicationExecutionRequest body
    ) {
        requireSuperUser(request);

        try {
            return realPublicationExecutorService.execute(body);
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
