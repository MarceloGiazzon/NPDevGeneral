package com.finalexec.api.internal;

import com.finalexec.api.*;

import com.finalexec.npdev.dto.SemanticBehaviorCanonicalizationRequest;
import com.finalexec.npdev.dto.SemanticBehaviorWriteBackRequest;
import com.finalexec.npdev.service.internal.SemanticBehaviorWriteBackCanonicalizationService;
import com.finalexec.npdev.service.internal.SemanticBehaviorWriteBackService;
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
@RequestMapping({"/api/v1/admin/model/semantic-behavior-writeback", "/api/admin/model/semantic-behavior-writeback"})
public class SemanticBehaviorWriteBackController {

    private final SemanticBehaviorWriteBackService semanticBehaviorWriteBackService;
    private final SemanticBehaviorWriteBackCanonicalizationService semanticBehaviorWriteBackCanonicalizationService;
    private final RuntimeContextService runtimeContextService;

    public SemanticBehaviorWriteBackController(
            SemanticBehaviorWriteBackService semanticBehaviorWriteBackService,
            SemanticBehaviorWriteBackCanonicalizationService semanticBehaviorWriteBackCanonicalizationService,
            RuntimeContextService runtimeContextService
    ) {
        this.semanticBehaviorWriteBackService = semanticBehaviorWriteBackService;
        this.semanticBehaviorWriteBackCanonicalizationService = semanticBehaviorWriteBackCanonicalizationService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getSummary(HttpServletRequest request) {
        requireAdminContext(request);
        return semanticBehaviorWriteBackService.summary();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(HttpServletRequest request) {
        requireAdminContext(request);
        return semanticBehaviorWriteBackService.history();
    }

    @GetMapping("/execution/history")
    public Map<String, Object> getExecutionHistory(HttpServletRequest request) {
        requireAdminContext(request);
        return semanticBehaviorWriteBackService.executionHistory();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> submit(
            HttpServletRequest request,
            @RequestBody SemanticBehaviorWriteBackRequest body
    ) {
        requireAdminContext(request);

        try {
            return semanticBehaviorWriteBackService.submit(body);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping("/apply")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> apply(
            HttpServletRequest request,
            @RequestBody SemanticBehaviorWriteBackRequest body
    ) {
        requireAdminContext(request);

        try {
            return semanticBehaviorWriteBackService.execute(body);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/canonicalization")
    public Map<String, Object> getCanonicalizationSummary(HttpServletRequest request) {
        requireAdminContext(request);
        return semanticBehaviorWriteBackCanonicalizationService.summary();
    }

    @GetMapping("/canonicalization/history")
    public Map<String, Object> getCanonicalizationHistory(HttpServletRequest request) {
        requireAdminContext(request);
        return semanticBehaviorWriteBackCanonicalizationService.history();
    }

    @PostMapping("/canonicalization/plan")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> planCanonicalization(
            HttpServletRequest request,
            @RequestBody SemanticBehaviorCanonicalizationRequest body
    ) {
        requireAdminContext(request);

        try {
            return semanticBehaviorWriteBackCanonicalizationService.canonicalize(body);
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