package com.finalexec.api;

import com.finalexec.npdev.service.PanelRuntime;
import com.finalexec.npdev.service.PermissionAwareUiMetadataService;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
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

    /**
     * Move 5 (docs/MOVE5_CLOSE_ALL_OPEN_PLAN.md, Wave 4 / Gap 7): a {@code resultAs: "download"}
     * action's response carries a download descriptor (see {@link PanelRuntime#executeAction})
     * instead of the usual JSON envelope -- streamed here as a real file response (Content-
     * Disposition: attachment) rather than a JSON blob a client would have to save by hand.
     */
    @PostMapping("/panels/{panelName}/actions/{actionName}")
    public ResponseEntity<?> executePanelAction(
            HttpServletRequest request,
            @PathVariable String panelName,
            @PathVariable String actionName,
            @RequestBody(required = false) Map<String, Object> body
    ) {
        Map<String, Object> response = run(() ->
                requirePanelRuntime().executeAction(panelName, actionName, body, currentContext(request)));
        if ("download".equals(response.get("resultAs"))) {
            String filename = String.valueOf(response.getOrDefault("filename", "download"));
            String contentType = String.valueOf(response.getOrDefault("contentType", "application/octet-stream"));
            Object content = response.get("result");
            byte[] bytes = content == null ? new byte[0] : String.valueOf(content).getBytes(StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(bytes);
        }
        return ResponseEntity.ok(response);
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

    /**
     * R5.6 (roadmap Wave 1): the closing half of "nothing carries a user locale server-side" --
     * {@code ExecutionContext} already has a read side for a {@code "locale"} tag
     * ({@link ExecutionContext#locale()}, added by the DSL/kernel half of this work) but nothing
     * populated it, because the generated {@code RuntimeContextService} (a mustache template out of
     * this module's reach) never reads one. Populated HERE instead, from the standard
     * {@code Accept-Language} header every browser already sends with zero app-specific wiring --
     * chosen over a stored per-user preference because it needs no new concept/field/endpoint to
     * exist for the done-when demo ("switching user locale live" is exactly what re-sending the
     * request with a different header does), and because it is the one locale signal that reaches
     * every unauthenticated/first-load request too (a stored preference only exists once someone is
     * logged in and has set it). A per-user stored preference remains a natural LATER addition
     * layered on top (an explicit {@code locale} tag beats this header the same way an explicit
     * {@code X-Tag-locale} would, per {@link ExecutionContext#withTag}'s override semantics) -- not
     * done here, out of this task's scope.
     */
    private ExecutionContext currentContext(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        String requestedLocale = primaryLocaleTag(request);
        return requestedLocale == null ? context : context.withTag("locale", requestedLocale);
    }

    /**
     * The request's locale, taken from the standard {@code Accept-Language} header (RFC 9110) --
     * only the FIRST (highest-priority) language range, stripped of its optional {@code ;q=} weight
     * (e.g. {@code "pt-BR,pt;q=0.9,en;q=0.8"} resolves to {@code "pt-BR"}). The kernel's
     * {@code LabelResolver} already implements the exact-tag / same-language-ignoring-region
     * fallback a lower-priority range in the same header would otherwise exist to express, so only
     * the first range is read.
     * Returns null (not {@code ""}) when the header is absent/blank/wildcard-only, so
     * {@link ExecutionContext#locale()} correctly reports "no locale requested" rather than a
     * tag that can never match anything.
     */
    private static String primaryLocaleTag(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.ACCEPT_LANGUAGE);
        if (header == null || header.isBlank()) {
            return null;
        }
        String firstRange = header.split(",", 2)[0].trim();
        String tag = firstRange.split(";", 2)[0].trim();
        return tag.isBlank() || "*".equals(tag) ? null : tag;
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
