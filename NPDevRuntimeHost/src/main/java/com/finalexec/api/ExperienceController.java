package com.finalexec.api;

import com.finalexec.config.ModelHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finalexec.experience.ExperienceCatalog;
import com.finalexec.experience.ManifestBuilder;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.generated.runtime.dto.FlowExecutionResponse;
import com.npdev.generated.runtime.service.KernelFacade;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import com.npdev.kernel.ExecutionResult;
import com.npdev.kernel.ExecutionStatus;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * FORM P5-T2: the runtime half of "experiences per role".
 *
 * <ul>
 *   <li>{@code GET /api/x/me} -- the caller's experience (first declared whose {@code for} intersects their roles,
 *       else {@code null}), the screens it reaches and the caller's roles. Authenticated like every {@code /api}
 *       route: the auth filters cover it and no exemption is added.</li>
 *   <li>{@code GET /x/<experience>/} (also {@code /x/<experience>}, {@code /x/<experience>/index.html} and a
 *       {@code /x/<experience>/<file>} with an extension) -- forwards to the static client under {@code /x/}. The
 *       shell carries no data; every byte of data arrives through authenticated {@code /api} calls. An unknown
 *       experience is a 404. A path segment without a dot is never matched, so the static files themselves
 *       ({@code /x/index.html}, {@code /x/app.js}) keep being served by the resource handler.</li>
 *   <li>{@code POST /api/x/flows/<flow>/execute} -- the ONLY route that starts an {@code expose: ui} flow from a
 *       browser. See {@link ExperienceCatalog#authorizeFlowStart}; anything not authorised is a 404 (same answer as a
 *       missing flow). An authorised flow runs through {@code KernelFacade.executeFlow} AS THE CALLER, with the same
 *       response mapping as the public flow route, so the flow's roles and the concepts' access rules still apply.</li>
 * </ul>
 */
@RestController
public class ExperienceController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExperienceCatalog catalog;
    private final KernelFacade kernelFacade;
    private final RuntimeContextService runtimeContextService;
    private final ModelHolder modelHolder;

    @Autowired
    public ExperienceController(
            KernelFacade kernelFacade,
            RuntimeContextService runtimeContextService,
            ModelHolder modelHolder
    ) {
        this(ExperienceCatalog.fromClasspath(), kernelFacade, runtimeContextService, modelHolder);
    }

    ExperienceController(
            ExperienceCatalog catalog,
            KernelFacade kernelFacade,
            RuntimeContextService runtimeContextService,
            ModelHolder modelHolder
    ) {
        this.catalog = catalog;
        this.kernelFacade = kernelFacade;
        this.runtimeContextService = runtimeContextService;
        this.modelHolder = modelHolder;
    }

    @GetMapping("/api/x/me")
    public Map<String, Object> me(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        return catalog.me(context.roles(), context.actorId());
    }

    @GetMapping({"/x/{experience:[^.]+}", "/x/{experience:[^.]+}/"})
    public void shell(@PathVariable String experience, HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        forward("/x/index.html", experience, request, response);
    }

    /**
     * FORM P5-T7: the web app manifest of an installable experience (device mobile or both); 404 for an unknown
     * experience or a {@code web} one. A literal segment, so it is more specific than {@link #clientFile} below and wins.
     */
    @GetMapping("/x/{experience:[^.]+}/manifest.webmanifest")
    public ResponseEntity<byte[]> manifest(@PathVariable String experience) throws IOException {
        Optional<Map<String, Object>> manifest = catalog.experienceNamed(experience).flatMap(ManifestBuilder::build);
        if (manifest.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No manifest for experience: " + experience);
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/manifest+json"))
                .body(JSON.writeValueAsBytes(manifest.get()));
    }

    @GetMapping("/x/{experience:[^.]+}/icon-192.png")
    public ResponseEntity<byte[]> icon192(@PathVariable String experience) throws IOException {
        return icon(experience, 192);
    }

    @GetMapping("/x/{experience:[^.]+}/icon-512.png")
    public ResponseEntity<byte[]> icon512(@PathVariable String experience) throws IOException {
        return icon(experience, 512);
    }

    /**
     * The icon the generator wrote to {@code static/x/icons/<name>-<size>.png}, read from the classpath here instead of
     * forwarded to the resource handler: {@code /x/icons/<file>} would match {@link #clientFile} as an experience called
     * "icons". The experience must exist and be installable, and the file stem is a sanitised literal, never the raw URL.
     */
    private ResponseEntity<byte[]> icon(String experience, int size) throws IOException {
        if (catalog.experienceNamed(experience).filter(ManifestBuilder::installable).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No icon for experience: " + experience);
        }
        ClassPathResource file = new ClassPathResource("static/x/icons/" + ManifestBuilder.iconStem(experience) + "-" + size + ".png");
        if (!file.exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No icon for experience: " + experience);
        }
        try (InputStream in = file.getInputStream()) {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                    .body(in.readAllBytes());
        }
    }

    @GetMapping("/x/{experience:[^.]+}/{file:.+\\..+}")
    public void clientFile(
            @PathVariable String experience,
            @PathVariable String file,
            HttpServletRequest request,
            HttpServletResponse response
    ) throws ServletException, IOException {
        forward("/x/" + file, experience, request, response);
    }

    private void forward(String target, String experience, HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if (!catalog.hasExperience(experience)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Experience not found: " + experience);
        }
        request.getRequestDispatcher(target).forward(request, response);
    }

    @PostMapping("/api/x/flows/{flowName}/execute")
    public ResponseEntity<FlowExecutionResponse> execute(
            @PathVariable String flowName,
            @RequestBody(required = false) Map<String, Object> payload,
            HttpServletRequest request
    ) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        Optional<String> flow = catalog.authorizeFlowStart(context.roles(), flowName, this::flowFacts);
        if (flow.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Flow not found: " + flowName);
        }
        ExecutionResult result = kernelFacade.executeFlow(flow.get(), payload, context);
        return ResponseEntity.status(mapStatus(result.getStatus())).body(FlowExecutionResponse.from(result));
    }

    private Optional<ExperienceCatalog.FlowFacts> flowFacts(String name) {
        for (CompiledFlow flow : modelHolder.get().getFlows()) {
            if (name.equalsIgnoreCase(flow.getName())) {
                return Optional.of(new ExperienceCatalog.FlowFacts(flow.getName(), flow.getExpose()));
            }
        }
        return Optional.empty();
    }

    /** The same mapping as the public {@code FlowExecutionController} route. */
    private static HttpStatus mapStatus(ExecutionStatus status) {
        if (status == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (status) {
            case OK -> HttpStatus.OK;
            case WAITING_EVENT -> HttpStatus.ACCEPTED;
            case INPUT_VALIDATION_FAILED, INVARIANT_FAILED, CAPABILITY_FAILED, EVENT_PERSIST_FAILED,
                    EVENT_PAYLOAD_INVALID, FAILED -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    }
}
