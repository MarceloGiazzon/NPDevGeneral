package com.finalexec.api.internal;

import com.finalexec.api.*;

import com.finalexec.npdev.service.internal.RuntimeTopologyExplorerService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class RuntimeTopologyExplorerController {

    private final RuntimeTopologyExplorerService runtimeTopologyExplorerService;

    public RuntimeTopologyExplorerController(RuntimeTopologyExplorerService runtimeTopologyExplorerService) {
        this.runtimeTopologyExplorerService = runtimeTopologyExplorerService;
    }

    @GetMapping({"/api/v1/runtime/topology", "/api/runtime/topology"})
    public Map<String, Object> topology() {
        return runtimeTopologyExplorerService.topology();
    }

    @GetMapping({"/api/v1/runtime/executions", "/api/runtime/executions"})
    public Map<String, Object> executions() {
        return runtimeTopologyExplorerService.executions();
    }

    @GetMapping({"/api/v1/runtime/capabilities", "/api/runtime/capabilities"})
    public Map<String, Object> capabilities() {
        return runtimeTopologyExplorerService.capabilities();
    }

    @GetMapping({"/api/v1/runtime/links", "/api/runtime/links"})
    public Map<String, Object> links() {
        return runtimeTopologyExplorerService.links();
    }
}
