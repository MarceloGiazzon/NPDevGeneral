package com.finalexec.api;

import com.finalexec.config.ModelHolder;
import com.finalexec.npdev.service.RuntimeMetadataService;
import com.npdev.dsl.v1.ast.ModelAst;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiler.ModelCompiler;
import com.npdev.dsl.v1.parser.JsonModelParser;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R1.7 (roadmap Wave 1, "hot metadata swap: METADATA_ONLY edits into the running JVM"): the
 * authenticated trigger for {@link RuntimeMetadataService#applyMetadataOnlyReload}, so an operator or
 * agent that already ran the existing, already-tested offline classifier
 * ({@code :generator:classifyModelChange}, the same task {@code Update-AppMetadata.ps1} drives) can
 * push a METADATA_ONLY result into a RUNNING app's own metadata catalogs without stopping it.
 *
 * <p><b>What this does and does not swap.</b> Only the purely descriptive catalogs
 * {@code RuntimeMetadataService} already serves read-only (compiled-metadata.json, metadata/index.json,
 * every metadata/*.manifest.json catalog) -- labels, panel/action/layout/validation-hint metadata,
 * concept/field catalogs used for introspection and UI display. It deliberately does NOT touch
 * {@code compiled-model.json}: that file backs the Spring-singleton {@code CompiledModel} bean
 * ({@code NPDevModelProvider}) wired into {@code KernelRunner}/{@code ConceptGateway}/
 * {@code PanelRuntime}/{@code CelInvariantEngine}/{@code CapabilityRegistry} and a dozen other
 * singletons at application-context startup -- every one of those beans is constructed ONCE with a
 * plain (non-reloadable) {@code CompiledModel} reference, so making THAT swappable without a restart
 * would require {@code NPDevKernel}/{@code NPDevGenerator} changes (a live/mutable model reference
 * threaded through every consumer), which is out of this module's scope. See this controller's own
 * class javadoc on {@link RuntimeMetadataService#applyMetadataOnlyReload} for the full argument that
 * this narrower scope is still safe: nothing wired from the descriptive catalogs can change what SQL a
 * panel runs or what an invariant enforces.
 *
 * <p><b>Two different gates, deliberately</b> (same posture as {@link AgentProxyController}).
 * {@code /status} answers any authenticated ADMIN caller, matching {@link RuntimeMetadataController}'s
 * own gate on every other read here. {@code /apply} MUTATES what every caller of the metadata catalogs
 * sees, so it requires SUPERUSER via the same manual {@code requireSuperUser} idiom every hand-written
 * admin controller in this package uses -- specifically not {@code hasRole("ADMIN")}, because in an
 * {@code auth.mode=none} app the generated {@code RuntimeContextService} hands ADMIN to every
 * anonymous caller; SUPERUSER is never in that fallback set.
 *
 * <p><b>Registration, and why this package.</b> The simple name is listed in
 * {@code npdev/runtime-supported-controllers.json}'s {@code allowedControllers}, per the same
 * three-enforcement-point convention {@link AgentProxyController}'s own javadoc documents in full.
 */
@RestController
@RequestMapping({"/api/v1/admin/runtime/metadata-hotswap", "/api/admin/runtime/metadata-hotswap"})
public class MetadataHotSwapController {

    private static final Logger LOG = LoggerFactory.getLogger(MetadataHotSwapController.class);

    private final RuntimeMetadataService runtimeMetadataService;
    private final RuntimeContextService runtimeContextService;
    private final ModelHolder modelHolder;

    public MetadataHotSwapController(
            RuntimeMetadataService runtimeMetadataService,
            RuntimeContextService runtimeContextService,
            ModelHolder modelHolder
    ) {
        this.runtimeMetadataService = runtimeMetadataService;
        this.runtimeContextService = runtimeContextService;
        this.modelHolder = modelHolder;
    }

    /**
     * The classification + reasons are exactly {@code ModelChangeClassifierMain}'s own report shape
     * ({@code classification}/{@code classificationReasons}), and {@code metadataSourceRoot} is the
     * {@code <dir>} its {@code --emitMetadataTo <dir>} flag already writes -- a caller that already ran
     * that task can pass its own output straight through, unchanged.
     */
    public record ApplyRequest(String classification, List<String> classificationReasons, String metadataSourceRoot) {
    }

    /** Any authenticated ADMIN caller -- read-only, same gate {@link RuntimeMetadataController} uses
     * for every other endpoint here. */
    @GetMapping("/status")
    public Map<String, Object> status(HttpServletRequest request) {
        requireAdminContext(request);
        return runtimeMetadataService.reloadStatus();
    }

    /**
     * Every failure returns an EXPLICIT body rather than throwing {@link ResponseStatusException} --
     * same reasoning {@link AgentProxyController#generate} documents: Spring Boot defaults
     * {@code server.error.include-message} to {@code never}, so a thrown exception's reason would
     * otherwise arrive at the caller as an empty string, and "not METADATA_ONLY, got SAFE_ADDITIVE"
     * is the entire point of the 409. The 403 from {@link #requireSuperUser} is the exception, and
     * stays an exception: there is nothing to tell an unauthorized caller.
     */
    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(HttpServletRequest request, @RequestBody ApplyRequest body) {
        requireSuperUser(request);

        if (body == null || body.metadataSourceRoot() == null || body.metadataSourceRoot().isBlank()) {
            return failure(HttpStatus.BAD_REQUEST, "SOURCE_ROOT_REQUIRED", "metadataSourceRoot is required");
        }

        Path sourceRoot;
        try {
            sourceRoot = Paths.get(body.metadataSourceRoot()).toAbsolutePath().normalize();
        } catch (InvalidPathException invalid) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_ROOT", invalid.getMessage());
        }

        try {
            RuntimeMetadataService.MetadataReloadResult result = runtimeMetadataService.applyMetadataOnlyReload(
                    body.classification(), body.classificationReasons(), sourceRoot);
            LOG.info("metadata hot-swap applied: generation={} catalogsUpdated={}",
                    result.generation(), result.catalogsUpdated().size());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("metadataGeneration", result.generation());
            response.put("appliedAt", result.appliedAt().toString());
            response.put("catalogsUpdated", result.catalogsUpdated());
            response.put("classificationReasons", result.classificationReasons());
            return ResponseEntity.ok(response);
        } catch (RuntimeMetadataService.MetadataChangeRefusedException refused) {
            LOG.info("metadata hot-swap refused: classification={}", refused.classification());
            return failure(HttpStatus.CONFLICT, "NOT_METADATA_ONLY", refused.getMessage());
        } catch (IllegalArgumentException invalid) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_METADATA_SOURCE", invalid.getMessage());
        } catch (IOException failed) {
            LOG.warn("metadata hot-swap failed: sourceRoot={}", sourceRoot, failed);
            return failure(HttpStatus.INTERNAL_SERVER_ERROR, "RELOAD_FAILED",
                    failed.getMessage() == null ? "I/O failure applying metadata reload" : failed.getMessage());
        }
    }

    private static ResponseEntity<Map<String, Object>> failure(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        return ResponseEntity.status(status).body(body);
    }

    /**
     * B28: hot model reload -- parse, compile, and atomically swap the CompiledModel without restart.
     * Requires SUPERUSER. The new model is read from a model.json file at the given path.
     */
    @PostMapping("/model-reload")
    public ResponseEntity<Map<String, Object>> modelReload(HttpServletRequest request, @RequestBody Map<String, String> body) {
        requireSuperUser(request);

        String modelPath = body.get("modelPath");
        if (modelPath == null || modelPath.isBlank()) {
            return failure(HttpStatus.BAD_REQUEST, "MODEL_PATH_REQUIRED", "modelPath is required");
        }

        Path modelFile;
        try {
            modelFile = Paths.get(modelPath).toAbsolutePath().normalize();
        } catch (InvalidPathException invalid) {
            return failure(HttpStatus.BAD_REQUEST, "INVALID_MODEL_PATH", invalid.getMessage());
        }

        if (!modelFile.toFile().isFile()) {
            return failure(HttpStatus.BAD_REQUEST, "MODEL_NOT_FOUND", "File not found: " + modelFile);
        }

        try {
            ModelAst ast = new JsonModelParser().parse(modelFile);
            CompiledModel newModel = new ModelCompiler().compile(ast);
            CompiledModel oldModel = modelHolder.swap(newModel);
            LOG.info("B28 hot model reload: swapped successfully (old concepts={}, new concepts={})",
                    oldModel.getConcepts().size(), newModel.getConcepts().size());

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("ok", true);
            response.put("concepts", newModel.getConcepts().size());
            response.put("flows", newModel.getFlows().size());
            response.put("procedures", newModel.getProcedures().size());
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOG.warn("B28 hot model reload failed: modelPath={}", modelFile, e);
            return failure(HttpStatus.INTERNAL_SERVER_ERROR, "RELOAD_FAILED",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void requireAdminContext(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    private void requireSuperUser(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasRole("SUPERUSER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
