package com.finalexec.api;

import com.finalexec.npdev.service.AggregateRuntime;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
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
    public ResponseEntity<Map<String, Object>> load(
            HttpServletRequest request,
            @PathVariable String aggregateName,
            @PathVariable String rootId
    ) {
        try {
            return ResponseEntity.ok(requireAggregateRuntime().load(aggregateName, rootId, currentContext(request)));
        } catch (IllegalArgumentException ex) {
            return errorBody(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            return errorBody(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    @PostMapping("/{aggregateName}")
    public ResponseEntity<Map<String, Object>> commit(
            HttpServletRequest request,
            @PathVariable String aggregateName,
            @RequestBody(required = false) Map<String, Object> draft
    ) {
        try {
            return ResponseEntity.ok(requireAggregateRuntime().commit(aggregateName, draft, currentContext(request)));
        } catch (IllegalArgumentException ex) {
            return errorBody(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            return errorBody(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    /**
     * Invoke a declared procedure over an in-flight draft and return the patched draft (no persistence).
     * {@code POST /api/runtime/aggregate/{name}/invoke/{procedure}} with the draft tree as the body.
     * See ADR-0004 / P6 — procedure-over-aggregate (e.g. "Gerar Demanda"/recompute).
     */
    @PostMapping("/{aggregateName}/invoke/{procedureName}")
    public ResponseEntity<Map<String, Object>> invoke(
            HttpServletRequest request,
            @PathVariable String aggregateName,
            @PathVariable String procedureName,
            @RequestBody(required = false) Map<String, Object> draft
    ) {
        try {
            return ResponseEntity.ok(requireAggregateRuntime().invoke(aggregateName, procedureName, draft, currentContext(request)));
        } catch (IllegalArgumentException ex) {
            return errorBody(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            return errorBody(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    /**
     * Session 1 (NPDEV_MEGA_ROADMAP.md, 2026-09-14): the on-demand "Recalcular Saldos" affordance --
     * evaluates the named, comma-separated {@code balances[]} rules against the draft in the body
     * and returns it merged with a {@code __balances} report, WITHOUT persisting -- the same
     * "sends the whole unsaved draft, gets a patched draft back" shape {@link #invoke} already uses.
     * {@code POST /api/runtime/aggregate/{name}/check-balances/{balanceNames}} (comma-separated).
     */
    @PostMapping("/{aggregateName}/check-balances/{balanceNames}")
    public ResponseEntity<Map<String, Object>> checkBalances(
            HttpServletRequest request,
            @PathVariable String aggregateName,
            @PathVariable String balanceNames,
            @RequestBody(required = false) Map<String, Object> draft
    ) {
        try {
            List<String> names = Arrays.stream(balanceNames.split(","))
                    .map(String::trim)
                    .filter(name -> !name.isEmpty())
                    .toList();
            return ResponseEntity.ok(requireAggregateRuntime().checkBalances(aggregateName, names, draft));
        } catch (IllegalArgumentException ex) {
            return errorBody(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            return errorBody(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    /**
     * A bare {@code ResponseStatusException} loses its reason under Spring Boot's default
     * {@code server.error.include-message=never} -- the client (see workbench-page.html.mustache's
     * {@code res.b.message} read) never sees the business-language text the underlying
     * {@code IllegalArgumentException}/{@code IllegalStateException} actually carried, only the
     * bare status code. Every other controller in this codebase builds its own {@code "message"}
     * body for this reason (see e.g. {@code AgentProxyController}); this controller must match.
     */
    private ResponseEntity<Map<String, Object>> errorBody(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message == null ? "" : message));
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
