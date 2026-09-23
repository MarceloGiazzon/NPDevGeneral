package com.finalexec.api;

import com.finalexec.config.ModelHolder;
import com.finalexec.db.NewConceptSchemaProvisioner;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
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
 * <p><b>{@code /apply} (metadata-only):</b> only the purely descriptive catalogs {@code
 * RuntimeMetadataService} already serves read-only (compiled-metadata.json, metadata/index.json,
 * every metadata/*.manifest.json catalog) -- labels, panel/action/layout/validation-hint metadata,
 * concept/field catalogs used for introspection and UI display. It deliberately does NOT touch
 * {@code compiled-model.json}.
 *
 * <p><b>{@code /model-reload} (REG-208, B28 lift):</b> swaps the actual {@code CompiledModel} behind
 * {@link ModelHolder}, atomically. Every consumer that reads {@link ModelHolder#get()} fresh per use
 * (most controllers/services in this template -- panels, procedures, aggregates, schedules, seeds,
 * the capability registry's own routing table, ...) observes the new model immediately, no restart.
 *
 * <p>As of D1 Phase 2 (REG-236/REG-237/REG-239, 2026-09-21), every residual this list had ever
 * NAMED was closed -- but an exhaustive sweep the next day found three more it had never named
 * (REG-240, REG-241, REG-242; see {@code ledger/boundaries/B28.yml}), so do not read the list below
 * as an enumeration; treat it as a history of what has been checked, not a static inventory.
 * REG-241 (the most consequential of the three) and REG-240 were fixed 2026-09-23:
 * {@code GeneratedCrudRuntimeSupport}'s orchestration subscribers now close and re-register on
 * every reload (a {@code ModelReloadListener} in {@code NpdevCapabilityBindingConfig}), and its
 * {@code identityTables} lookup reads {@code modelSupplier.get()} fresh instead of caching the
 * boot-time resolution. REG-242 was fixed the same day: {@code DocumentRenderController} now reads
 * {@code documents[]} through {@link ModelHolder} instead of injecting {@code NPDevModelProvider}
 * directly, and {@code scripts/quality/check-model-provider-injection.py} (wired into
 * {@code run-ai-knowledge-gate.ps1}) now fails the build if any OTHER bean pins itself to the boot
 * model the same way -- the guard-hole this class's javadoc used to describe as open is now closed
 * mechanically, not just by convention.
 *
 * <p>The kernel-side {@code DefaultExecutionAuthorizationPolicy}'s app-declared-role cache now
 * rebuilds in place on a reload via a {@code ModelReloadListener} registered in {@code
 * NpdevAuthConfig} (REG-239), so a role added, removed or regranted by a hot reload takes effect on
 * the next permission check. A reloaded model whose {@code roles[]} declares an unrecognized grant
 * fails the RELOAD itself (the listener throws inside {@link ModelHolder#swap}'s write lock) and
 * leaves the previously-validated permission set in place -- that grant-name check is the only place
 * in the platform where a grant is validated against the real {@code Permission} enum, so it must
 * not move off a fail-loud path.
 * Everything else `NpdevCapabilityBindingConfig`'s original 4-bean residual list named is
 * now live or live-with-a-scoped-exception: {@code CelInvariantEngine}-backed {@code
 * InvariantEngine}, {@code ConceptGateway}'s semantic policy, and {@code propertyResolver} all
 * observe a reload immediately (REG-236). {@code KernelRunner}'s flow-definition provider observes
 * a reload for every NEW {@code execute()}/{@code resumeExecution()} lookup (REG-237) -- and, as of
 * REG-238 (2026-09-22), resuming a durable {@code WAITING_EVENT} instance now refuses (rather than
 * silently misapplying a stale step index) when the flow's STEP SHAPE changed since it was
 * checkpointed, via a fingerprint stamped at {@code execute()} time and checked at resume.
 * ({@code GeneratedCrudRuntimeSupport} was also in this list until REG-235 (2026-09-21)
 * converted it to read a live {@code Supplier<CompiledModel>}; it is no longer a residual for its
 * OWN invariant/event/capability/binding lookups. That fix does NOT extend to the generated REST
 * CRUD surface itself -- {@code GeneratedConceptCrudController} and its per-concept JPA entity are
 * plain Java baked once from the model at generation time with zero {@code ModelHolder} reference
 * anywhere in either template (entity.mustache, business-concept-crud-controller.mustache): a
 * brand-new FIELD on an existing concept is structurally unreachable through that surface without
 * regenerate + rebuild + restart, confirmed by reading both templates, not just by this class's own
 * residual list.) See {@code NpdevCapabilityBindingConfig}'s own per-bean javadoc for the complete,
 * current list.
 *
 * <p><b>New-concept schema provisioning</b> (REG-244 Phase 4B, {@code
 * npdev.runtime.hotswap.new-concept-provisioning-enabled}, default {@code false}): when enabled AND
 * a physical {@code DataSource} exists, a reload that adds a brand-new, bond-free, non-satellite,
 * non-temporal concept also creates its business table (idempotent {@code CREATE TABLE IF NOT
 * EXISTS} via {@link com.finalexec.db.NewConceptSchemaProvisioner}) in the same call -- additive
 * only, never FKs/uniques/indexes. The response always names both {@code conceptsProvisioned} and
 * {@code conceptsProvisioningFailed} (the latter mapping a concept name to why it was refused or
 * failed), even when the property is off. This makes the new table EXIST; it does not make it
 * CRUD-reachable or visible in the UI manifest -- those are separate, not-yet-shipped phases.
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
    private final boolean fullModelReloadEnabled;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final boolean newConceptProvisioningEnabled;

    public MetadataHotSwapController(
            RuntimeMetadataService runtimeMetadataService,
            RuntimeContextService runtimeContextService,
            ModelHolder modelHolder,
            @Value("${npdev.runtime.hotswap.full-model-reload-enabled:true}") boolean fullModelReloadEnabled,
            ObjectProvider<DataSource> dataSourceProvider,
            @Value("${npdev.runtime.hotswap.new-concept-provisioning-enabled:false}") boolean newConceptProvisioningEnabled
    ) {
        this.runtimeMetadataService = runtimeMetadataService;
        this.runtimeContextService = runtimeContextService;
        this.modelHolder = modelHolder;
        this.fullModelReloadEnabled = fullModelReloadEnabled;
        this.dataSourceProvider = dataSourceProvider;
        this.newConceptProvisioningEnabled = newConceptProvisioningEnabled;
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
     * REG-208 (B28 lift): hot model reload -- parse, compile, and atomically swap the CompiledModel
     * without restart. Requires SUPERUSER. The new model is read from a model.json file at the given
     * path.
     *
     * <p>Enabled by default (2026-09-05, REG-208): {@link ModelHolder#swap} now genuinely propagates
     * to the great majority of consumers (see this class's own top javadoc), and every "structure
     * derived once" holder that could not be made to rebuild in place was found and named, not left
     * to silently go stale -- {@code npdev.runtime.hotswap.full-model-reload-enabled=false} remains
     * available for an operator who wants the OLD refusal instead (e.g. while validating the residual
     * list above is acceptable for their own app).
     */
    @PostMapping("/model-reload")
    public ResponseEntity<Map<String, Object>> modelReload(HttpServletRequest request, @RequestBody Map<String, String> body) {
        requireSuperUser(request);

        if (!fullModelReloadEnabled) {
            return failure(HttpStatus.NOT_FOUND, "FULL_MODEL_RELOAD_DISABLED",
                    "Full model hot-reload is disabled on this instance "
                            + "(npdev.runtime.hotswap.full-model-reload-enabled=false). See "
                            + "MetadataHotSwapController's class javadoc for what does and does not "
                            + "observe a reload; set the property to true (the default) to opt back in.");
        }

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
            // REG-243: this call does not imply a full apply. It swaps the LIVE CompiledModel (every
            // ModelHolder.get() consumer -- CRUD, panels, invariants, orchestrations, documents -- sees
            // the new model immediately), but RuntimeMetadataService's UI-facing catalogs (labels,
            // hints, layout descriptions) are untouched: they require a build-time classification this
            // runtime module cannot perform (RuntimeMetadataService's own javadoc explains why), so a
            // caller wanting those updated too must separately POST /apply with a metadataSourceRoot
            // produced by the classifier. false here is not a failure -- the model swap above genuinely
            // succeeded -- it names what this specific call did and did not reach.
            response.put("uiMetadataCatalogsRefreshed", false);
            // REG-244 Phase 4B: the model swap above already succeeds/fails independently of this --
            // provisioning runs AFTER a successful swap and its own per-concept failures are caught
            // inside NewConceptSchemaProvisioner, never thrown out, so a DDL problem on one concept
            // never undoes an otherwise-successful reload. Always named (never omitted), same
            // "don't hide the limit behind a bare boolean" convention as uiMetadataCatalogsRefreshed:
            // empty lists when the property is off or no DataSource exists, not their absence.
            List<String> conceptsProvisioned = List.of();
            Map<String, String> conceptsProvisioningFailed = Map.of();
            if (newConceptProvisioningEnabled) {
                DataSource dataSource = dataSourceProvider.getIfAvailable();
                if (dataSource != null) {
                    NewConceptSchemaProvisioner.Result result =
                            NewConceptSchemaProvisioner.provision(dataSource, oldModel, newModel);
                    conceptsProvisioned = result.provisioned();
                    conceptsProvisioningFailed = result.failed();
                }
            }
            response.put("conceptsProvisioned", conceptsProvisioned);
            response.put("conceptsProvisioningFailed", conceptsProvisioningFailed);
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
