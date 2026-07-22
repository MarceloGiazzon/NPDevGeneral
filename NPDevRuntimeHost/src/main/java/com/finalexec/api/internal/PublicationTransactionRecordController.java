package com.finalexec.api.internal;

import com.finalexec.api.*;

import com.finalexec.npdev.dto.PublicationTransactionRecordRequest;
import com.finalexec.npdev.service.internal.PublicationTransactionRecordService;
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
@RequestMapping({"/api/v1/admin/publication-transactions", "/api/admin/publication-transactions"})
public class PublicationTransactionRecordController {

    private final PublicationTransactionRecordService publicationTransactionRecordService;
    private final RuntimeContextService runtimeContextService;

    public PublicationTransactionRecordController(
            PublicationTransactionRecordService publicationTransactionRecordService,
            RuntimeContextService runtimeContextService
    ) {
        this.publicationTransactionRecordService = publicationTransactionRecordService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getSummary(HttpServletRequest request) {
        requireAdminContext(request);
        return publicationTransactionRecordService.summary();
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(HttpServletRequest request) {
        requireAdminContext(request);
        return publicationTransactionRecordService.history();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> record(
            HttpServletRequest request,
            @RequestBody PublicationTransactionRecordRequest body
    ) {
        requireAdminContext(request);

        try {
            return publicationTransactionRecordService.recordTransaction(body);
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
