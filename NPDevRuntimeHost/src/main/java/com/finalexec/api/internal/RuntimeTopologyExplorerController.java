package com.finalexec.api.internal;

import com.finalexec.api.*;

import com.finalexec.npdev.service.internal.RuntimeTopologyExplorerService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
public class RuntimeTopologyExplorerController {

    private final RuntimeTopologyExplorerService runtimeTopologyExplorerService;
    private final RuntimeContextService runtimeContextService;

    public RuntimeTopologyExplorerController(
            RuntimeTopologyExplorerService runtimeTopologyExplorerService,
            RuntimeContextService runtimeContextService
    ) {
        this.runtimeTopologyExplorerService = runtimeTopologyExplorerService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping({"/api/v1/runtime/topology", "/api/runtime/topology"})
    public Map<String, Object> topology(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        return runtimeTopologyExplorerService.topology();
    }

    @GetMapping({"/api/v1/runtime/executions", "/api/runtime/executions"})
    public Map<String, Object> executions(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        return runtimeTopologyExplorerService.executions();
    }

    @GetMapping({"/api/v1/runtime/capabilities", "/api/runtime/capabilities"})
    public Map<String, Object> capabilities(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        return runtimeTopologyExplorerService.capabilities();
    }

    @GetMapping({"/api/v1/runtime/links", "/api/runtime/links"})
    public Map<String, Object> links(HttpServletRequest httpRequest) {
        requireSuperUser(httpRequest);
        return runtimeTopologyExplorerService.links();
    }

    private void requireSuperUser(HttpServletRequest httpRequest) {
        ExecutionContext context = runtimeContextService.currentContext(httpRequest);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
