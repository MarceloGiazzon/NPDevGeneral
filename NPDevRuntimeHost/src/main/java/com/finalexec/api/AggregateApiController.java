package com.finalexec.api;

import com.finalexec.npdev.service.AggregateRuntime;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Read and commit a declared aggregate as a nested tree.
 * {@code GET /api/runtime/aggregate/{name}/{rootId}} loads;
 * {@code POST /api/runtime/aggregate/{name}} commits a draft tree. See ADR-0004 / P0 / P4.
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

    @PostMapping("/{aggregateName}")
    public Map<String, Object> commit(
            HttpServletRequest request,
            @PathVariable String aggregateName,
            @RequestBody(required = false) Map<String, Object> draft
    ) {
        try {
            return requireAggregateRuntime().commit(aggregateName, draft, currentContext(request));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    /**
     * Invoke a declared procedure over an in-flight draft and return the patched draft (no persistence).
     * {@code POST /api/runtime/aggregate/{name}/invoke/{procedure}} with the draft tree as the body.
     * See ADR-0004 / P6 — procedure-over-aggregate (e.g. "Gerar Demanda"/recompute).
     */
    @PostMapping("/{aggregateName}/invoke/{procedureName}")
    public Map<String, Object> invoke(
            HttpServletRequest request,
            @PathVariable String aggregateName,
            @PathVariable String procedureName,
            @RequestBody(required = false) Map<String, Object> draft
    ) {
        try {
            return requireAggregateRuntime().invoke(aggregateName, procedureName, draft, currentContext(request));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
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
