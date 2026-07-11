package com.finalexec.api;

import com.finalexec.npdev.service.AggregateRuntime;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Read a declared aggregate as a nested tree.
 * {@code GET /api/runtime/aggregate/{aggregateName}/{rootId}}. See ADR-0004 / P0.
 */
@RestController
@RequestMapping({"/api/v1/runtime/aggregate", "/api/runtime/aggregate"})
public class AggregateApiController {

    private final RuntimeContextService runtimeContextService;
    private final AggregateRuntime aggregateRuntime;

    public AggregateApiController(RuntimeContextService runtimeContextService) {
        this(runtimeContextService, null);
    }

    @Autowired
    public AggregateApiController(
            RuntimeContextService runtimeContextService,
            AggregateRuntime aggregateRuntime
    ) {
        this.runtimeContextService = runtimeContextService;
        this.aggregateRuntime = aggregateRuntime;
    }

    @GetMapping("/{aggregateName}/{rootId}")
    public Map<String, Object> load(
            HttpServletRequest request,
            @PathVariable String aggregateName,
            @PathVariable String rootId
    ) {
        try {
            return requireAggregateRuntime().load(aggregateName, rootId, currentContext(request));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    private ExecutionContext currentContext(HttpServletRequest request) {
        return runtimeContextService.currentContext(request);
    }

    private AggregateRuntime requireAggregateRuntime() {
        if (aggregateRuntime == null) {
            throw new IllegalStateException("Aggregate runtime is not configured.");
        }
        return aggregateRuntime;
    }
}
