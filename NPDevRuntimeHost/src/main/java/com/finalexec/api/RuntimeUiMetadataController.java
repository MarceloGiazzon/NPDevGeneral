package com.finalexec.api;

import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.PermissionAwareUiMetadataService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1/runtime/metadata/ui", "/api/runtime/metadata/ui"})
public class RuntimeUiMetadataController {

    private final PermissionAwareUiMetadataService permissionAwareUiMetadataService;
    private final RuntimeContextService runtimeContextService;
    private final PanelRuntime panelRuntime;

    public RuntimeUiMetadataController(
            PermissionAwareUiMetadataService permissionAwareUiMetadataService,
            RuntimeContextService runtimeContextService
    ) {
        this(permissionAwareUiMetadataService, runtimeContextService, null);
    }

    @Autowired
    public RuntimeUiMetadataController(
            PermissionAwareUiMetadataService permissionAwareUiMetadataService,
            RuntimeContextService runtimeContextService,
            PanelRuntime panelRuntime
    ) {
        this.permissionAwareUiMetadataService = permissionAwareUiMetadataService;
        this.runtimeContextService = runtimeContextService;
        this.panelRuntime = panelRuntime;
    }

    @GetMapping("/actions")
    public Map<String, Object> actions(
            HttpServletRequest request,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String ownerName
    ) {
        return run(() -> permissionAwareUiMetadataService.actions(concept, ownerName, currentContext(request)));
    }

    @GetMapping("/fields")
    public Map<String, Object> fields(
            HttpServletRequest request,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String fieldPath
    ) {
        return run(() -> permissionAwareUiMetadataService.fields(concept, fieldPath, currentContext(request)));
    }

    /** F2.2: single-call UI contract for a screen, composing the existing {@code /fields}/{@code
     * /actions} filters (see {@link PermissionAwareUiMetadataService#bundle}'s javadoc for the full
     * design rationale, incl. why layout/enums/references/transitions/validation/invocations are
     * passed through unfiltered rather than gaining a brand-new permission filter each). */
    @GetMapping("/bundle")
    public Map<String, Object> bundle(
            HttpServletRequest request,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String panel
    ) {
        return run(() -> permissionAwareUiMetadataService.bundle(concept, panel, currentContext(request)));
    }

    @GetMapping("/preview/{conceptName}")
    public Map<String, Object> previewSupport(
            HttpServletRequest request,
            @PathVariable String conceptName
    ) {
        return run(() -> permissionAwareUiMetadataService.previewSupport(conceptName, currentContext(request)));
    }

    @GetMapping("/panels/{panelName}")
    public Map<String, Object> loadPanel(
            HttpServletRequest request,
            @PathVariable String panelName,
            @RequestParam(required = false) String id
    ) {
        Map<String, Object> input = (id == null || id.isBlank()) ? Map.of() : Map.of("id", id);
        return run(() -> requirePanelRuntime().loadPanel(panelName, input, currentContext(request)));
    }

    @PostMapping("/panels/{panelName}/actions/{actionName}")
    public Map<String, Object> executePanelAction(
            HttpServletRequest request,
            @PathVariable String panelName,
            @PathVariable String actionName,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        return run(() -> requirePanelRuntime().executeAction(panelName, actionName, body, currentContext(request)));
    }

    /** LIFT-ROWOPS-P3: creates a row in a declared Panel dataSource with {@code rowOps: [add]}. */
    @PostMapping("/panels/{panelName}/dataSources/{dataSourceName}/rows")
    public Map<String, Object> createPanelRow(
            HttpServletRequest request,
            @PathVariable String panelName,
            @PathVariable String dataSourceName,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        return run(() -> requirePanelRuntime().createRow(panelName, dataSourceName, body, currentContext(request)));
    }

    /** LIFT-ROWOPS-P3: deletes a row from a declared Panel dataSource with {@code rowOps: [delete]}. */
    @DeleteMapping("/panels/{panelName}/dataSources/{dataSourceName}/rows/{id}")
    public Map<String, Object> deletePanelRow(
            HttpServletRequest request,
            @PathVariable String panelName,
            @PathVariable String dataSourceName,
            @PathVariable String id
    ) {
        return run(() -> requirePanelRuntime().deleteRow(panelName, dataSourceName, id, currentContext(request)));
    }

    private ExecutionContext currentContext(HttpServletRequest request) {
        return runtimeContextService.currentContext(request);
    }

    private Map<String, Object> run(MetadataCall metadataCall) {
        try {
            return metadataCall.get();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    private PanelRuntime requirePanelRuntime() {
        if (panelRuntime == null) {
            throw new IllegalStateException("Panel runtime is not configured.");
        }
        return panelRuntime;
    }

    @FunctionalInterface
    private interface MetadataCall {
        Map<String, Object> get();
    }
}
