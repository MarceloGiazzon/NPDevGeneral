package com.finalexec.api;

import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping({"/api/v1/admin/runtime/metadata", "/api/admin/runtime/metadata"})
public class RuntimeMetadataController {

    private final RuntimeMetadataService runtimeMetadataService;
    private final RuntimeContextService runtimeContextService;

    public RuntimeMetadataController(
            RuntimeMetadataService runtimeMetadataService,
            RuntimeContextService runtimeContextService
    ) {
        this.runtimeMetadataService = runtimeMetadataService;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> overview(HttpServletRequest request) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.overview());
    }

    @GetMapping("/index")
    public Map<String, Object> metadataIndex(HttpServletRequest request) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.metadataIndex());
    }

    @GetMapping("/catalogs/{catalogName}")
    public Map<String, Object> catalog(
            HttpServletRequest request,
            @PathVariable String catalogName,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String ownerName,
            @RequestParam(required = false) String fieldPath
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.catalog(catalogName, concept, ownerName, fieldPath));
    }

    @GetMapping("/concepts")
    public Map<String, Object> concepts(
            HttpServletRequest request,
            @RequestParam(required = false) String concept
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.concepts(concept));
    }

    @GetMapping("/concepts/{conceptName}")
    public Map<String, Object> concept(
            HttpServletRequest request,
            @PathVariable String conceptName
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.concept(conceptName));
    }

    @GetMapping("/procedures")
    public Map<String, Object> procedures(
            HttpServletRequest request,
            @RequestParam(required = false) String procedure
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.procedures(procedure));
    }

    @GetMapping("/panels")
    public Map<String, Object> panels(
            HttpServletRequest request,
            @RequestParam(required = false) String panel
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.panels(panel));
    }

    @GetMapping("/fields")
    public Map<String, Object> fields(
            HttpServletRequest request,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String fieldPath
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.fields(concept, fieldPath));
    }

    @GetMapping("/enums")
    public Map<String, Object> enums(
            HttpServletRequest request,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String fieldPath
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.enums(concept, fieldPath));
    }

    @GetMapping("/references")
    public Map<String, Object> references(
            HttpServletRequest request,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String fieldPath
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.references(concept, fieldPath));
    }

    @GetMapping("/actions")
    public Map<String, Object> actions(
            HttpServletRequest request,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String ownerName
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.actions(concept, ownerName));
    }

    @GetMapping("/layout")
    public Map<String, Object> layout(
            HttpServletRequest request,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String fieldPath
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.layout(concept, fieldPath));
    }

    @GetMapping("/validation-support")
    public Map<String, Object> validationSupport(
            HttpServletRequest request,
            @RequestParam(required = false) String concept,
            @RequestParam(required = false) String fieldPath
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.validationSupport(concept, fieldPath));
    }

    @GetMapping("/preview/{conceptName}")
    public Map<String, Object> previewSupport(
            HttpServletRequest request,
            @PathVariable String conceptName
    ) {
        requireAdminContext(request);
        return run(() -> runtimeMetadataService.previewSupport(conceptName));
    }

    private Map<String, Object> run(MetadataCall metadataCall) {
        try {
            return metadataCall.get();
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (NoSuchElementException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    private void requireAdminContext(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    @FunctionalInterface
    private interface MetadataCall {
        Map<String, Object> get();
    }
}
