package com.finalexec.api;

import com.finalexec.config.ModelHolder;
import com.finalexec.search.GlobalSearchService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.concepts.ConceptGateway;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * VIEW-1: {@code GET /api/search?q=..&limit=5} -- the global search palette's one request. Thin on
 * purpose: {@link GlobalSearchService} owns the logic and goes through the concept gateway, so
 * permissions, row rules, tenant scoping and field visibility are exactly those of a normal list.
 */
@RestController
@RequestMapping({"/api/v1/search", "/api/search"})
public class GlobalSearchController {

    private final RuntimeContextService runtimeContextService;
    private final GlobalSearchService service;

    public GlobalSearchController(
            RuntimeContextService runtimeContextService,
            ModelHolder modelHolder,
            ObjectProvider<ConceptGateway> conceptGateway
    ) {
        this.runtimeContextService = runtimeContextService;
        this.service = new GlobalSearchService(modelHolder::get, conceptGateway::getIfAvailable);
    }

    @GetMapping
    public Map<String, Object> search(
            HttpServletRequest request,
            @RequestParam(name = "q", required = false, defaultValue = "") String q,
            @RequestParam(name = "limit", required = false, defaultValue = "5") int limit
    ) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (context == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        return service.search(q, limit, context);
    }
}
