package com.npdev.generator.emitters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledMetadataCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledOrchestration;
import com.npdev.dsl.v1.compiled.CompiledOrchestrationAction;
import com.npdev.dsl.v1.compiled.CompiledPanel;
import com.npdev.dsl.v1.compiled.CompiledPanelAction;
import com.npdev.dsl.v1.compiled.CompiledPresentationMetadata;
import com.npdev.dsl.v1.compiled.CompiledProcedure;
import com.npdev.dsl.v1.parser.ResolvedModelSource;
import com.npdev.generator.packs.BuiltinPackComposer;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class RuntimeApiEmitter extends AbstractEmitter {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public RuntimeApiEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model, Path modelSourcePath) {
        emit(model, null, modelSourcePath);
    }

    public void emit(CompiledModel model, ResolvedModelSource resolvedModelSource, Path modelSourcePath) {
        emit(model, resolvedModelSource, modelSourcePath, "ADMIN");
    }

    public void emit(
            CompiledModel model,
            ResolvedModelSource resolvedModelSource,
            Path modelSourcePath,
            String superUserRole
    ) {
        writer.deleteRelativeIfExists("src/main/java/com/npdev/generated/runtime/adapters/InProcEventStoreAdapter.java");

        Map<String, Object> ctx = new HashMap<>();
        ctx.put("compiledModelPathDefault", "npdev-generated/src/main/resources/npdev/compiled-model.json");
        ctx.put("configPackage", "com.npdev.generated.runtime.config");
        ctx.put("modelPackage", "com.npdev.generated.runtime.model");
        ctx.put("servicePackage", "com.npdev.generated.runtime.service");
        ctx.put("apiPackage", "com.npdev.generated.runtime.api");
        ctx.put("dtoPackage", "com.npdev.generated.runtime.dto");

        Map<String, String> uiSelection = readCanonicalUiSelection();
        String canonicalSurface = uiSelection.getOrDefault("canonicalSurface", "runtime-served-ui");
        String canonicalUiRoute = uiSelection.getOrDefault("canonicalRoute", "/npdev-ui/");
        String alternateUiRoute = uiSelection.getOrDefault("alternateRoute", "/npdev-ui-react/");
        String canonicalUiRootRedirect = canonicalUiRoute.endsWith("/")
                ? canonicalUiRoute + "index.html"
                : canonicalUiRoute + "/index.html";
        ctx.put("canonicalUiSurface", canonicalSurface);
        ctx.put("canonicalUiRoute", canonicalUiRoute);
        ctx.put("alternateUiRoute", alternateUiRoute);
        ctx.put("canonicalUiRootRedirect", canonicalUiRootRedirect);

        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/model/NPDevModelProvider.java",
                templates.render("npdev-runtime-model-provider.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/config/NPDevRuntimeConfig.java",
                templates.render("npdev-runtime-config.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/config/GeneratedBindingManifestLoader.java",
                templates.render("npdev-runtime-binding-manifest-loader.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/config/GeneratedPermissionManifestLoader.java",
                templates.render("npdev-runtime-permission-manifest-loader.mustache", ctx)
        );
writer.writeRelative(
        "src/main/java/com/npdev/generated/runtime/config/GeneratedRuntimeOverridesLoader.java",
        templates.render("npdev-runtime-runtime-overrides-loader.mustache", ctx)
);
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/service/KernelFacade.java",
                templates.render("npdev-runtime-kernel-facade.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/service/RuntimeContextService.java",
                templates.render("npdev-runtime-context-service.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/config/RuntimeApiKeyAuthFilter.java",
                templates.render("npdev-runtime-api-key-auth-filter.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/FlowExecutionResponse.java",
                templates.render("npdev-runtime-flow-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/FlowDefinitionResponse.java",
                templates.render("npdev-runtime-flow-definition-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/EventPublishRequest.java",
                templates.render("npdev-runtime-event-publish-request.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/EventPublishResponse.java",
                templates.render("npdev-runtime-event-publish-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/ExecutionQueryResponse.java",
                templates.render("npdev-runtime-execution-query-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/ExecutionSummaryResponse.java",
                templates.render("npdev-runtime-execution-summary-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/EventQueryResponse.java",
                templates.render("npdev-runtime-event-query-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/EventMetaSummaryResponse.java",
                templates.render("npdev-runtime-event-meta-summary-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/TraceSummaryResponse.java",
                templates.render("npdev-runtime-trace-summary-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/AuditRecordResponse.java",
                templates.render("npdev-runtime-audit-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/CorrelationTimelineResponse.java",
                templates.render("npdev-runtime-correlation-timeline-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/AdminCircuitResponse.java",
                templates.render("npdev-runtime-admin-circuit-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/dto/AdminIdempotencyResponse.java",
                templates.render("npdev-runtime-admin-idempotency-response.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/api/FlowExecutionController.java",
                templates.render("npdev-runtime-flow-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/api/EventIngestionController.java",
                templates.render("npdev-runtime-event-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/api/TraceController.java",
                templates.render("npdev-runtime-trace-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/api/ExecutionQueryController.java",
                templates.render("npdev-runtime-execution-query-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/api/EventQueryController.java",
                templates.render("npdev-runtime-event-query-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/api/CorrelationController.java",
                templates.render("npdev-runtime-correlation-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/api/AuditController.java",
                templates.render("npdev-runtime-audit-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/api/AdminController.java",
                templates.render("npdev-runtime-admin-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/api/UiRedirectController.java",
                templates.render("npdev-runtime-ui-redirect-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/api/OpenApiController.java",
                templates.render("npdev-runtime-openapi-controller.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/resources/static/npdev-ui/index.html",
                templates.render("npdev-ui-index.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/resources/static/npdev-ui/app.js",
                templates.render("npdev-ui-app.mustache", ctx)
        );
        writer.writeRelative(
                "src/main/resources/static/npdev-ui/style.css",
                templates.render("npdev-ui-style.mustache", ctx)
        );
        emitOptionalReactUiAssets();
        writer.writeRelative(
                "src/main/resources/npdev/ui-boundary/canonical-ui-selection.json",
                readCanonicalUiSelectionJson()
        );
        writer.writeRelative(
                "src/main/resources/npdev-runtime-actuator.properties",
                templates.render("npdev-runtime-actuator-properties.mustache", ctx)
        );

        if (resolvedModelSource != null) {
            writer.writeRelative("src/main/resources/npdev/model.json", resolvedModelSource.resolvedModelJson());
            writer.writeRelative("src/main/resources/npdev/model-source-manifest.json", emitModelSourceManifest(resolvedModelSource));
        } else if (modelSourcePath != null && Files.exists(modelSourcePath)) {
            writer.writeRelative("src/main/resources/npdev/model.json", readModelSource(modelSourcePath));
        }
        GeneratedPluginMountPlan pluginMountPlan = GeneratedPluginMountPlan.fromModelSource(resolvedModelSource, modelSourcePath);

        writer.writeRelative(
                "src/main/resources/npdev/compiled-model.json",
                CompiledModelCanonicalJson.toJson(model)
        );
        writer.writeRelative(
                "src/main/resources/npdev/compiled-metadata.json",
                resolvedModelSource == null
                        ? CompiledMetadataCanonicalJson.toJson(modelSourcePath, model)
                        : CompiledMetadataCanonicalJson.toJson(resolvedModelSource.resolvedRoot(), model)
        );

        writer.writeRelative(
                "src/main/resources/npdev/bindings/dev.bindings.json",
                emitBindingManifest("dev.bindings.json", pluginMountPlan)
        );
        writer.writeRelative(
                "src/main/resources/npdev/bindings/alt.bindings.json",
                emitBindingManifest("alt.bindings.json", pluginMountPlan)
        );
        writer.writeRelative(
                "src/main/resources/npdev/security/dev.permissions.json",
                generatePermissionManifest(model, resolvedModelSource, modelSourcePath, superUserRole)
        );
        writer.writeRelative(
                "src/main/resources/npdev/security/dev.ui-metadata-policy.json",
                generateUiMetadataPolicyManifest(model)
        );
        writer.writeRelative(
                "src/main/resources/npdev/runtime/dev.runtime.json",
                readDefaultRuntimeManifest()
        );
        writer.writeRelative(
                "src/main/resources/npdev/plugins/default.plugin-manifest.json",
                emitPluginManifest("default.plugin-manifest.json", pluginMountPlan)
        );
        writer.writeRelative(
                "src/main/resources/npdev/plugins/dev.plugin-manifest.json",
                emitPluginManifest("dev.plugin-manifest.json", pluginMountPlan)
        );
        writer.writeRelative(
                "src/main/resources/npdev/plugins/warning.plugin-manifest.json",
                emitPluginManifest("warning.plugin-manifest.json", pluginMountPlan)
        );
        for (String fileName : listPluginPackageDescriptorFiles()) {
            writer.writeRelative(
                    "src/main/resources/npdev/plugin-packages/" + fileName,
                    readPluginPackageDescriptor(fileName)
            );
        }
        for (GeneratedPluginMountPlan.PackageGroup packageGroup : pluginMountPlan.packageGroups()) {
            GeneratedPluginMountPlan.Mount representative = packageGroup.representative();
            writer.writeRelative(
                    "src/main/resources/npdev/plugin-packages/" + representative.packageFileName(),
                    emitGeneratedPluginPackageDescriptor(packageGroup)
            );
        }
        writer.writeRelative(
                "src/main/resources/npdev/plugin-packages/index.json",
                readPluginPackageIndex(pluginMountPlan)
        );
        emitJavaSourceMounts(pluginMountPlan);
    }

    private static String readModelSource(Path modelSourcePath) {
        try {
            return Files.readString(modelSourcePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed reading model source: " + modelSourcePath, e);
        }
    }

    private static String emitModelSourceManifest(ResolvedModelSource source) {
        String included = source.includedFiles().stream()
                .sorted(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT)))
                .map(path -> "    \"" + jsonEscape(path.toString().replace('\\', '/')) + "\"")
                .collect(Collectors.joining("," + System.lineSeparator()));
        return """
{
  "manifestVersion": "1.0.0",
  "rootModel": "%s",
  "canonicalRootDirectory": "%s",
  "includedFiles": [
%s
  ]
}
""".formatted(
                jsonEscape(source.rootModelPath().toString().replace('\\', '/')),
                jsonEscape(source.canonicalRootDirectory().toString().replace('\\', '/')),
                included
        );
    }

    private static String readCanonicalUiSelectionJson() {
        Path selectionPath = resolveProjectResourcePath("ui-boundary", "canonical-ui-selection.json");
        if (selectionPath == null) {
            return """
{
  "canonicalSurface": "runtime-served-ui",
  "canonicalRoute": "/npdev-ui/",
  "alternateSurface": "react-workbench",
  "alternateRoute": "/npdev-ui-react/",
  "promotionMode": "explicit-switch"
}
""";
        }
        try {
            return Files.readString(selectionPath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new RuntimeException("Failed reading canonical UI selection: " + selectionPath, exception);
        }
    }

    private static Map<String, String> readCanonicalUiSelection() {
        String json = readCanonicalUiSelectionJson();
        Map<String, String> values = new HashMap<>();
        values.put("canonicalSurface", extractJsonStringValue(json, "canonicalSurface", "runtime-served-ui"));
        values.put("canonicalRoute", extractJsonStringValue(json, "canonicalRoute", "/npdev-ui/"));
        values.put("alternateRoute", extractJsonStringValue(json, "alternateRoute", "/npdev-ui-react/"));
        return values;
    }

    private static String extractJsonStringValue(String json, String key, String defaultValue) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
        Matcher matcher = pattern.matcher(json);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return defaultValue;
    }

    private static String readBindingManifest(String fileName) {
        Path manifestPath = resolveProjectResourcePath("bindings", fileName);
        if (manifestPath == null) {
            throw new RuntimeException("Required binding manifest source not found: resources\\bindings\\" + fileName);
        }
        try {
            return Files.readString(manifestPath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new RuntimeException("Failed reading binding manifest: " + manifestPath, exception);
        }
    }

    private static String emitBindingManifest(String fileName, GeneratedPluginMountPlan pluginMountPlan) {
        String source = readBindingManifest(fileName);
        if (pluginMountPlan.isEmpty()) {
            return source;
        }
        try {
            ObjectNode root = (ObjectNode) OBJECT_MAPPER.readTree(stripJsonBom(source));
            ArrayNode bindings = root.withArray("bindings");
            Set<String> existing = new LinkedHashSet<>();
            for (JsonNode binding : bindings) {
                existing.add(bindingKey(
                        binding.path("capability").asText(),
                        binding.path("adapterId").asText()
                ));
            }
            for (GeneratedPluginMountPlan.Mount mount : pluginMountPlan.mounts()) {
                String key = bindingKey(mount.capability(), mount.adapterId());
                if (existing.contains(key)) {
                    continue;
                }
                ObjectNode binding = OBJECT_MAPPER.createObjectNode();
                binding.put("capability", mount.capability());
                binding.put("capabilityType", mount.capabilityType());
                binding.put("adapterId", mount.adapterId());
                binding.put("adapterClass", "");
                binding.put("environment", "");
                binding.put("tenantId", "");
                bindings.add(binding);
                existing.add(key);
            }
            return prettyJson(root);
        } catch (IOException exception) {
            throw new RuntimeException("Failed emitting generated binding manifest: " + fileName, exception);
        }
    }

    private static String generatePermissionManifest(
            CompiledModel model,
            ResolvedModelSource resolvedModelSource,
            Path modelSourcePath,
            String superUserRole
    ) {
        String superUserRoleKey = (superUserRole == null || superUserRole.isBlank())
                ? "admin" : superUserRole.trim().toLowerCase(Locale.ROOT);

        Set<String> permissions = new LinkedHashSet<>();
        permissions.add("flow.execute");
        permissions.add("capability.invoke");
        permissions.add("event.publish");

        List<PermissionGrantSpec> grants = new ArrayList<>();

        // Generated CRUD controllers require an explicit grant per persisted concept and
        // operation (see GeneratedCrudRuntimeSupport.checkCrudPermission, which checks
        // "<operation>:<conceptName>" lowercased). Without these the generated Business UI
        // and CRUD API cannot read or write at all under kernel-controlled CRUD.
        // Concepts contributed by built-in platform packs (identity/workspace) are reserved
        // to the configured super-user role; ordinary business concepts are also opened to
        // the generic "user" role so regular authenticated users can use the app.
        for (CompiledConcept concept : model.getConcepts()) {
            if (concept == null || concept.getName() == null || concept.getName().isBlank()) {
                continue;
            }
            if (concept.getTableName() == null || concept.getTableName().isBlank()) {
                continue;
            }
            String conceptKey = concept.getName().toLowerCase(Locale.ROOT);
            boolean adminOnly = isAdminConcept(concept.getName());
            for (String operation : List.of("create", "update", "delete", "read", "list")) {
                String permission = operation + ":" + conceptKey;
                // Blank tenantId is a wildcard match in PermissionGrant.matches() -- these grants are
                // role-based capability checks ("can an admin/user create this concept type at all"),
                // not data access, so they apply to every platform tenant. Row-level data isolation is
                // a separate, already-enforced concern (tenant_id scoping in JdbcBusinessConceptStore /
                // InMemoryConceptStore). Without this, a tenant created at runtime via
                // /api/admin/tenants authenticates fine but gets 403 on every generated CRUD call,
                // because the old hardcoded tenantId="dev" only ever matched the one tenant the
                // generator itself knows about at generation time.
                grants.add(new PermissionGrantSpec(permission, "", "", superUserRoleKey));
                if (!adminOnly && !"user".equals(superUserRoleKey)) {
                    grants.add(new PermissionGrantSpec(permission, "", "", "user"));
                }
            }
        }

        for (CompiledFlow flow : model.getFlows()) {
            addIfPresent(permissions, flow.getAction() == null ? null : flow.getAction().getPermissionHint());
            collectStepPermissionHints(flow.getSteps(), permissions);
        }
        for (CompiledOrchestration orchestration : model.getOrchestrationRules()) {
            for (CompiledOrchestrationAction action : orchestration.getActions()) {
                addIfPresent(permissions, action.getAction() == null ? null : action.getAction().getPermissionHint());
            }
        }
        for (CompiledProcedure procedure : model.getProcedures()) {
            for (String requirement : procedure.permissionRequirements()) {
                addIfPresent(permissions, requirement);
            }
        }
        for (CompiledPanel panel : model.getPanels()) {
            addRoleVisibilityPermission(permissions, panel.visibility());
            for (CompiledPanelAction action : panel.actions()) {
                for (String requirement : action.permissionRequirements()) {
                    addIfPresent(permissions, requirement);
                }
            }
        }

        AiBetaSecurityMetadata aiSecurity = readAiBetaSecurityMetadata(resolvedModelSource, modelSourcePath);
        permissions.addAll(aiSecurity.allDeclaredPermissions());

        for (String permission : permissions) {
            grants.add(new PermissionGrantSpec(permission, "", "", superUserRoleKey));
        }
        // event.publish backs the mutation-event side effect of every CRUD write (see
        // GeneratedCrudRuntimeSupport.publishMutationEvent), which a business "user" role
        // must already have cleared a per-concept create/update/delete grant to reach. Without
        // this, every business-concept write under kernel-controlled CRUD throws after the
        // permission check passes, because the event-publish step is unconditionally
        // admin-only.
        if (!"user".equals(superUserRoleKey)) {
            grants.add(new PermissionGrantSpec("event.publish", "", "", "user"));
        }
        for (AiBetaUser user : aiSecurity.testUsers()) {
            Set<String> userPermissions = new LinkedHashSet<>(List.of(
                    "flow.execute",
                    "capability.invoke",
                    "event.publish"
            ));
            for (String role : user.roles()) {
                addIfPresent(userPermissions, role);
                userPermissions.addAll(aiSecurity.permissionsForRole(role));
            }
            for (String permission : userPermissions) {
                grants.add(new PermissionGrantSpec(permission, user.tenantId(), user.userId(), ""));
                for (String role : user.roles()) {
                    grants.add(new PermissionGrantSpec(permission, user.tenantId(), "", role));
                }
            }
        }

        String grantsJson = grants.stream()
                .sorted(Comparator.comparing(PermissionGrantSpec::permission)
                        .thenComparing(PermissionGrantSpec::tenantId)
                        .thenComparing(PermissionGrantSpec::actorId)
                        .thenComparing(PermissionGrantSpec::role))
                .map(grant -> """
    {
      "permission": "%s",
      "tenantId": "%s",
      "actorId": "%s",
      "role": "%s"
    }""".formatted(
                        jsonEscape(grant.permission()),
                        jsonEscape(grant.tenantId()),
                        jsonEscape(grant.actorId()),
                        jsonEscape(grant.role())
                ))
                .collect(Collectors.joining("," + System.lineSeparator()));

        return """
{
  "grants": [
%s
  ]
}
""".formatted(grantsJson);
    }

    /** True for concepts contributed by a built-in platform pack (the internal NPDev tables). */
    private static boolean isAdminConcept(String conceptName) {
        if (conceptName == null) {
            return false;
        }
        int sep = conceptName.indexOf("::");
        if (sep < 0) {
            return false;
        }
        return BuiltinPackComposer.BUILTIN_PACK_ALIASES.contains(conceptName.substring(0, sep));
    }

    private static void collectStepPermissionHints(Iterable<CompiledFlowStep> steps, Set<String> permissions) {
        if (steps == null) {
            return;
        }
        for (CompiledFlowStep step : steps) {
            if (step == null) {
                continue;
            }
            addIfPresent(permissions, step.getAction() == null ? null : step.getAction().getPermissionHint());
            collectStepPermissionHints(step.getThenSteps(), permissions);
            collectStepPermissionHints(step.getElseSteps(), permissions);
        }
    }

    private static void addRoleVisibilityPermission(Set<String> permissions, String visibility) {
        if (visibility == null) {
            return;
        }
        String trimmed = visibility.trim();
        if (trimmed.regionMatches(true, 0, "role:", 0, 5) && trimmed.length() > 5) {
            addIfPresent(permissions, trimmed.substring(5));
        }
    }

    private static AiBetaSecurityMetadata readAiBetaSecurityMetadata(ResolvedModelSource resolvedModelSource, Path modelSourcePath) {
        if (resolvedModelSource != null) {
            return readAiBetaSecurityMetadata(resolvedModelSource.resolvedRoot(), "resolved model source");
        }
        if (modelSourcePath == null || !Files.isRegularFile(modelSourcePath)) {
            return AiBetaSecurityMetadata.empty();
        }
        try {
            return readAiBetaSecurityMetadata(OBJECT_MAPPER.readTree(modelSourcePath.toFile()), modelSourcePath.toString());
        } catch (IOException exception) {
            throw new RuntimeException("Failed reading AI beta security metadata from " + modelSourcePath, exception);
        }
    }

    private static AiBetaSecurityMetadata readAiBetaSecurityMetadata(JsonNode root, String sourceLabel) {
        if (root == null) {
            return AiBetaSecurityMetadata.empty();
        }
        try {
            JsonNode metadata = root.path("metadata");
            Map<String, Set<String>> permissionsByRole = new HashMap<>();
            for (JsonNode roleNode : metadata.path("roles")) {
                String roleId = text(roleNode, "roleId");
                if (roleId.isBlank()) {
                    continue;
                }
                Set<String> rolePermissions = new LinkedHashSet<>();
                rolePermissions.add(roleId);
                for (JsonNode permissionNode : roleNode.path("permissions")) {
                    addIfPresent(rolePermissions, permissionNode.asText(""));
                }
                permissionsByRole.put(roleId.toLowerCase(Locale.ROOT), rolePermissions);
            }

            List<AiBetaUser> users = new ArrayList<>();
            for (JsonNode userNode : metadata.path("auth").path("testUsers")) {
                String userId = text(userNode, "userId");
                String tenantId = text(userNode, "tenantId");
                if (userId.isBlank() || tenantId.isBlank()) {
                    continue;
                }
                List<String> roles = new ArrayList<>();
                for (JsonNode roleNode : userNode.path("roles")) {
                    String role = roleNode.asText("").trim();
                    if (!role.isBlank()) {
                        roles.add(role);
                    }
                }
                if (!roles.isEmpty()) {
                    users.add(new AiBetaUser(userId, tenantId, List.copyOf(roles)));
                }
            }
            return new AiBetaSecurityMetadata(Map.copyOf(permissionsByRole), List.copyOf(users));
        } catch (RuntimeException exception) {
            throw new RuntimeException("Failed reading AI beta security metadata from " + sourceLabel, exception);
        }
    }

    private static String text(JsonNode node, String fieldName) {
        JsonNode value = node == null ? null : node.get(fieldName);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String generateUiMetadataPolicyManifest(CompiledModel model) {
        java.util.List<String> fieldPolicies = new java.util.ArrayList<>();
        java.util.List<String> actionPolicies = new java.util.ArrayList<>();

        for (CompiledConcept entity : model.getConcepts()) {
            for (CompiledField field : entity.getFields()) {
                CompiledPresentationMetadata ui = field.getUi();
                if (ui == null || isBlank(ui.getReadonlyWhen()) && isBlank(ui.getVisibleWhen())) {
                    continue;
                }
                String state = isBlank(ui.getVisibleWhen()) ? "readonly" : "hidden";
                String reason = isBlank(ui.getReadonlyWhen()) ? ui.getVisibleWhen() : ui.getReadonlyWhen();
                fieldPolicies.add("""
    {
      "concept": "%s",
      "fieldPath": "%s",
      "statesByRole": {
        "ADMIN": "editable",
        "SUPPORT": "%s",
        "USER": "%s"
      },
      "messagesByState": {
        "%s": "Model rule: %s"
      }
    }""".formatted(
                        jsonEscape(entity.getName()),
                        jsonEscape(field.getName()),
                        state,
                        state,
                        state,
                        jsonEscape(reason)
                ));
            }
        }

        for (CompiledFlow flow : model.getFlows()) {
            if (flow.getAction() != null && !isBlank(flow.getAction().getPermissionHint())) {
                actionPolicies.add(actionPolicy(flow.getName(), flow.getName(), "flow"));
            }
            collectStepActionPolicies(flow, flow.getSteps(), actionPolicies);
        }
        for (CompiledOrchestration orchestration : model.getOrchestrationRules()) {
            int index = 1;
            for (CompiledOrchestrationAction action : orchestration.getActions()) {
                if (action.getAction() != null && !isBlank(action.getAction().getPermissionHint())) {
                    actionPolicies.add(actionPolicy(orchestration.getName() + "#" + index, orchestration.getName(), "orchestrationAction"));
                }
                index++;
            }
        }

        return """
{
  "policyVersion": "1.0.0",
  "fieldPolicies": [%s%s
  ],
  "actionPolicies": [%s%s
  ]
}
""".formatted(
                fieldPolicies.isEmpty() ? "" : System.lineSeparator(),
                String.join("," + System.lineSeparator(), fieldPolicies),
                actionPolicies.isEmpty() ? "" : System.lineSeparator(),
                String.join("," + System.lineSeparator(), actionPolicies)
        );
    }

    private static void collectStepActionPolicies(CompiledFlow flow, Iterable<CompiledFlowStep> steps, java.util.List<String> actionPolicies) {
        if (steps == null) {
            return;
        }
        for (CompiledFlowStep step : steps) {
            if (step == null) {
                continue;
            }
            if (step.getAction() != null && !isBlank(step.getAction().getPermissionHint())) {
                actionPolicies.add(actionPolicy(step.getName(), flow.getName(), "flowStep"));
            }
            collectStepActionPolicies(flow, step.getThenSteps(), actionPolicies);
            collectStepActionPolicies(flow, step.getElseSteps(), actionPolicies);
        }
    }

    private static String actionPolicy(String name, String ownerName, String kind) {
        return """
    {
      "name": "%s",
      "ownerName": "%s",
      "kind": "%s",
      "denyMode": "disabled",
      "denialMessage": "This action requires the model-defined permission for %s."
    }""".formatted(jsonEscape(name), jsonEscape(ownerName), jsonEscape(kind), jsonEscape(ownerName));
    }

    private static void addIfPresent(Set<String> values, String value) {
        if (!isBlank(value)) {
            values.add(value.trim());
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private record PermissionGrantSpec(String permission, String tenantId, String actorId, String role) {
        PermissionGrantSpec {
            permission = permission == null ? "" : permission.trim();
            tenantId = tenantId == null ? "" : tenantId.trim();
            actorId = actorId == null ? "" : actorId.trim();
            role = role == null ? "" : role.trim();
        }
    }

    private record AiBetaUser(String userId, String tenantId, List<String> roles) {
        AiBetaUser {
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }

    private record AiBetaSecurityMetadata(Map<String, Set<String>> permissionsByRole, List<AiBetaUser> testUsers) {
        AiBetaSecurityMetadata {
            permissionsByRole = permissionsByRole == null ? Map.of() : Map.copyOf(permissionsByRole);
            testUsers = testUsers == null ? List.of() : List.copyOf(testUsers);
        }

        static AiBetaSecurityMetadata empty() {
            return new AiBetaSecurityMetadata(Map.of(), List.of());
        }

        Set<String> permissionsForRole(String role) {
            if (role == null) {
                return Set.of();
            }
            return permissionsByRole.getOrDefault(role.trim().toLowerCase(Locale.ROOT), Set.of());
        }

        Set<String> allDeclaredPermissions() {
            Set<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, Set<String>> entry : permissionsByRole.entrySet()) {
                addIfPresent(out, entry.getKey());
                out.addAll(entry.getValue());
            }
            return Set.copyOf(out);
        }
    }

    private static String readDefaultRuntimeManifest() {
        Path manifestPath = Paths.get("resources", "runtime", "dev.runtime.json");
        if (Files.exists(manifestPath)) {
            try {
                return Files.readString(manifestPath, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException("Failed reading runtime manifest: " + manifestPath, e);
            }
        }
        return """
{
  "capabilityOverrides": [
    { "capability": "persistence", "useAdapterId": "memory" }
  ]
}
""";
    }

    private static String readPluginManifest(String fileName) {
        Path manifestPath = resolveProjectResourcePath("Plugins", fileName);
        if (manifestPath == null) {
            throw new RuntimeException("Required plugin manifest source not found: resources\\Plugins\\" + fileName);
        }
        try {
            return Files.readString(manifestPath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new RuntimeException("Failed reading plugin manifest: " + manifestPath, exception);
        }
    }

    private static String emitPluginManifest(String fileName, GeneratedPluginMountPlan pluginMountPlan) {
        String source = readPluginManifest(fileName);
        if (pluginMountPlan.isEmpty()) {
            return source;
        }
        try {
            ObjectNode root = (ObjectNode) OBJECT_MAPPER.readTree(stripJsonBom(source));
            ArrayNode plugins = root.withArray("plugins");
            Set<String> existingAdapters = new LinkedHashSet<>();
            for (JsonNode plugin : plugins) {
                for (JsonNode adapter : plugin.path("adapters")) {
                    existingAdapters.add(adapterContributionKey(
                            adapter.path("capability").asText(),
                            adapter.path("operation").asText(),
                            adapter.path("adapterId").asText()
                    ));
                }
            }
            for (GeneratedPluginMountPlan.PackageGroup packageGroup : pluginMountPlan.packageGroups()) {
                ArrayNode adapters = OBJECT_MAPPER.createArrayNode();
                for (GeneratedPluginMountPlan.Mount mount : packageGroup.mounts()) {
                    String key = adapterContributionKey(mount.capability(), mount.operation(), mount.adapterId());
                    if (existingAdapters.contains(key)) {
                        continue;
                    }
                    ObjectNode adapter = OBJECT_MAPPER.createObjectNode();
                    adapter.put("capability", mount.capability());
                    adapter.put("operation", mount.operation());
                    adapter.put("adapterId", mount.adapterId());
                    adapter.put("bindingKey", mount.bindingKey());
                    ObjectNode implementation = OBJECT_MAPPER.createObjectNode();
                    implementation.put("kind", "runtimeRef");
                    implementation.put("ref", mount.runtimeRef());
                    adapter.set("implementation", implementation);
                    adapters.add(adapter);
                    existingAdapters.add(key);
                }
                if (adapters.isEmpty()) {
                    continue;
                }
                GeneratedPluginMountPlan.Mount representative = packageGroup.representative();
                ObjectNode plugin = OBJECT_MAPPER.createObjectNode();
                plugin.put("pluginId", representative.pluginId());
                plugin.put("displayName", representative.displayName());
                plugin.put("version", representative.version());
                plugin.put("enabled", true);
                plugin.set("adapters", adapters);
                plugins.add(plugin);
            }
            return prettyJson(root);
        } catch (IOException exception) {
            throw new RuntimeException("Failed emitting generated plugin manifest: " + fileName, exception);
        }
    }

    private static String readPluginPackageDescriptor(String fileName) {
        Path descriptorPath = resolveProjectResourcePath("PluginPackages", fileName);
        if (descriptorPath == null) {
            throw new RuntimeException("Required plugin package source not found: resources\\PluginPackages\\" + fileName);
        }
        try {
            return Files.readString(descriptorPath, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new RuntimeException("Failed reading plugin package descriptor: " + descriptorPath, exception);
        }
    }

    private static String readPluginPackageIndex(GeneratedPluginMountPlan pluginMountPlan) {
        List<String> packageFiles = new ArrayList<>(listPluginPackageDescriptorFiles());
        for (GeneratedPluginMountPlan.PackageGroup packageGroup : pluginMountPlan.packageGroups()) {
            packageFiles.add(packageGroup.representative().packageFileName());
        }
        packageFiles = packageFiles.stream()
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        String resources = packageFiles.stream()
                .map(fileName -> "    \"npdev/plugin-packages/" + fileName + "\"")
                .collect(java.util.stream.Collectors.joining("," + System.lineSeparator()));
        return """
{
  "location": "npdev/plugin-packages",
  "resources": [
%s
  ]
}
""".formatted(resources);
    }

    private static String emitGeneratedPluginPackageDescriptor(GeneratedPluginMountPlan.PackageGroup packageGroup) {
        GeneratedPluginMountPlan.Mount representative = packageGroup.representative();
        if (representative.mountKind() == GeneratedPluginMountPlan.MountKind.JAVA_SOURCE) {
            return emitJavaSourcePluginPackageDescriptor(packageGroup);
        }
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("packageFormatVersion", "1.0");
        root.put("packageId", representative.packageId());
        root.put("displayName", "Generated " + representative.capability() + " capability package");
        root.put("version", "1.0.0");
        root.put("description", "Generated package descriptor for model-defined capability " + representative.capability() + ".");
        root.put("provider", "NPDev Generator");

        ObjectNode compatibility = OBJECT_MAPPER.createObjectNode();
        compatibility.put("npdevRuntimeApiVersion", "1.0");
        compatibility.put("minBootstrapVersion", "1.0.0");
        root.set("compatibility", compatibility);

        ObjectNode trust = OBJECT_MAPPER.createObjectNode();
        trust.put("mode", "internal");
        trust.put("source", "generated-model");
        trust.put("level", "trusted");
        root.set("trust", trust);

        ObjectNode signature = OBJECT_MAPPER.createObjectNode();
        signature.put("algorithm", "sha256");
        signature.put("digest", "generated-model-deterministic-placeholder");
        signature.put("status", "generated");
        signature.put("verifiedBy", "npdev-generator");
        root.set("signature", signature);

        ObjectNode provenance = OBJECT_MAPPER.createObjectNode();
        provenance.put("sourceType", "model.json");
        provenance.put("sourceLocation", "model.json");
        provenance.put("attestation", "generated-from-model-customCapabilities");
        root.set("provenance", provenance);

        ArrayNode artifacts = OBJECT_MAPPER.createArrayNode();
        ObjectNode artifact = OBJECT_MAPPER.createObjectNode();
        artifact.put("kind", "runtimeRefBundle");
        artifact.put("path", "built-in://generic-mounted-capability");
        artifacts.add(artifact);
        root.set("artifacts", artifacts);

        ArrayNode capabilities = OBJECT_MAPPER.createArrayNode();
        for (GeneratedPluginMountPlan.Mount mount : packageGroup.mounts()) {
            ObjectNode capability = OBJECT_MAPPER.createObjectNode();
            capability.put("capability", mount.capability());
            capability.put("operation", mount.operation());
            capability.put("adapterId", mount.adapterId());
            capabilities.add(capability);
        }
        root.set("capabilities", capabilities);

        ObjectNode pluginManifest = OBJECT_MAPPER.createObjectNode();
        pluginManifest.put("path", "npdev/plugins/default.plugin-manifest.json");
        root.set("pluginManifest", pluginManifest);

        return prettyJson(root);
    }

    private static String emitJavaSourcePluginPackageDescriptor(GeneratedPluginMountPlan.PackageGroup packageGroup) {
        GeneratedPluginMountPlan.Mount representative = packageGroup.representative();
        GeneratedPluginMountPlan.JavaSourceDescriptor descriptor = representative.javaSource();
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("packageFormatVersion", "1.0");
        root.put("packageId", descriptor.packageId());
        root.put("displayName", descriptor.displayName());
        root.put("version", descriptor.version());
        root.put("description", "Artifact-local Java source package for model-defined capability " + representative.capability() + ".");
        root.put("provider", "External artifact bundle");

        ObjectNode compatibility = OBJECT_MAPPER.createObjectNode();
        compatibility.put("npdevRuntimeApiVersion", "1.0");
        compatibility.put("minBootstrapVersion", "1.0.0");
        root.set("compatibility", compatibility);

        ObjectNode trust = OBJECT_MAPPER.createObjectNode();
        trust.put("mode", "local-dev");
        trust.put("source", "external-artifact-bundle");
        trust.put("level", "developer-supplied");
        root.set("trust", trust);

        ObjectNode signature = OBJECT_MAPPER.createObjectNode();
        signature.put("algorithm", "sha256");
        signature.put("digest", "artifact-local-java-source");
        signature.put("status", "generated");
        signature.put("verifiedBy", "npdev-generator");
        root.set("signature", signature);

        ObjectNode provenance = OBJECT_MAPPER.createObjectNode();
        provenance.put("sourceType", "capability.plugin.json");
        provenance.put("sourceLocation", descriptor.artifactRoot().relativize(descriptor.descriptorPath()).toString().replace('\\', '/'));
        provenance.put("attestation", "generated-from-artifact-local-java-source");
        root.set("provenance", provenance);

        ArrayNode artifacts = OBJECT_MAPPER.createArrayNode();
        ObjectNode artifact = OBJECT_MAPPER.createObjectNode();
        artifact.put("kind", "javaSource");
        artifact.put("path", descriptor.sourceRoot());
        artifacts.add(artifact);
        root.set("artifacts", artifacts);

        ArrayNode capabilities = OBJECT_MAPPER.createArrayNode();
        for (GeneratedPluginMountPlan.Mount mount : packageGroup.mounts()) {
            ObjectNode capability = OBJECT_MAPPER.createObjectNode();
            capability.put("capability", mount.capability());
            capability.put("operation", mount.operation());
            capability.put("adapterId", mount.adapterId());
            capabilities.add(capability);
        }
        root.set("capabilities", capabilities);

        ObjectNode pluginManifest = OBJECT_MAPPER.createObjectNode();
        pluginManifest.put("path", "npdev/plugins/default.plugin-manifest.json");
        root.set("pluginManifest", pluginManifest);

        return prettyJson(root);
    }

    private void emitJavaSourceMounts(GeneratedPluginMountPlan pluginMountPlan) {
        List<GeneratedPluginMountPlan.JavaSourcePackageGroup> javaSourceGroups = pluginMountPlan.javaSourcePackageGroups();
        if (javaSourceGroups.isEmpty()) {
            return;
        }
        emitJavaSourceFiles(javaSourceGroups);
        writer.writeRelative(
                "src/main/java/com/npdev/generated/runtime/config/GeneratedJavaSourceCapabilityProviders.java",
                javaSourceProvidersSource(javaSourceGroups)
        );
    }

    private void emitJavaSourceFiles(List<GeneratedPluginMountPlan.JavaSourcePackageGroup> javaSourceGroups) {
        Set<String> emitted = new LinkedHashSet<>();
        for (GeneratedPluginMountPlan.JavaSourcePackageGroup group : javaSourceGroups) {
            GeneratedPluginMountPlan.JavaSourceDescriptor descriptor = group.descriptor();
            try (java.util.stream.Stream<Path> stream = Files.walk(descriptor.resolvedSourceRoot())) {
                List<Path> sources = stream
                        .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".java"))
                        .sorted(Comparator.comparing(path -> descriptor.resolvedSourceRoot().relativize(path).toString(), String.CASE_INSENSITIVE_ORDER))
                        .toList();
                for (Path source : sources) {
                    Path relative = descriptor.resolvedSourceRoot().relativize(source);
                    String destination = "src/main/java/" + relative.toString().replace('\\', '/');
                    if (!emitted.add(destination.toLowerCase(Locale.ROOT))) {
                        throw new IllegalStateException("Duplicate artifact-local Java source destination: " + destination);
                    }
                    writer.writeRelative(destination, Files.readString(source, StandardCharsets.UTF_8));
                }
            } catch (IOException exception) {
                throw new RuntimeException("Failed emitting artifact-local Java source for " + descriptor.capability(), exception);
            }
        }
    }

    private static String javaSourceProvidersSource(List<GeneratedPluginMountPlan.JavaSourcePackageGroup> javaSourceGroups) {
        StringBuilder source = new StringBuilder();
        source.append("""
                package com.npdev.generated.runtime.config;

                import com.finalexec.npdev.service.ArtifactLocalJavaSourceCapabilityHandler;
                import com.finalexec.npdev.service.RuntimePluginRealizationProvider;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                import java.util.Map;

                @Configuration
                public class GeneratedJavaSourceCapabilityProviders {

                """);
        Set<String> methodNames = new LinkedHashSet<>();
        for (GeneratedPluginMountPlan.JavaSourcePackageGroup group : javaSourceGroups) {
            GeneratedPluginMountPlan.JavaSourceDescriptor descriptor = group.descriptor();
            String methodName = uniqueMethodName(methodNames, javaIdentifierPart(descriptor.runtimeRef()) + "Provider");
            source.append("    @Bean\n");
            source.append("    public RuntimePluginRealizationProvider ").append(methodName).append("() {\n");
            source.append("        return new RuntimePluginRealizationProvider() {\n");
            source.append("            @Override\n");
            source.append("            public String runtimeRef() {\n");
            source.append("                return \"").append(javaEscape(descriptor.runtimeRef())).append("\";\n");
            source.append("            }\n\n");
            source.append("            @Override\n");
            source.append("            public Object realize() {\n");
            source.append("                return new ArtifactLocalJavaSourceCapabilityHandler(\n");
            source.append("                        new ").append(descriptor.mainClass()).append("(),\n");
            source.append("                        Map.ofEntries(");
            List<Map.Entry<String, String>> entries = descriptor.methodByOperation().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                    .toList();
            for (int index = 0; index < entries.size(); index++) {
                Map.Entry<String, String> entry = entries.get(index);
                if (index > 0) {
                    source.append(", ");
                }
                source.append("Map.entry(\"")
                        .append(javaEscape(entry.getKey()))
                        .append("\", \"")
                        .append(javaEscape(entry.getValue()))
                        .append("\")");
            }
            source.append(")\n");
            source.append("                );\n");
            source.append("            }\n");
            source.append("        };\n");
            source.append("    }\n\n");
        }
        source.append("}\n");
        return source.toString();
    }

    private static String uniqueMethodName(Set<String> used, String base) {
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            candidate = base + suffix;
            suffix++;
        }
        return candidate;
    }

    private static String javaIdentifierPart(String value) {
        String normalized = value == null ? "" : value.trim();
        StringBuilder builder = new StringBuilder();
        boolean capitalizeNext = false;
        for (int index = 0; index < normalized.length(); index++) {
            char ch = normalized.charAt(index);
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                if (builder.length() == 0 && Character.isDigit(ch)) {
                    builder.append('_');
                }
                builder.append(capitalizeNext ? Character.toUpperCase(ch) : ch);
                capitalizeNext = false;
            } else {
                capitalizeNext = builder.length() > 0;
            }
        }
        return builder.length() == 0 ? "javaSourceRuntimeRef" : builder.toString();
    }

    private static String javaEscape(String value) {
        return value == null ? "" : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static java.util.List<String> listPluginPackageDescriptorFiles() {
        Path pluginPackagesDirectory = resolveProjectResourcePath("PluginPackages", "");
        if (pluginPackagesDirectory == null) {
            throw new RuntimeException("Required plugin package source directory not found: resources\\PluginPackages");
        }
        try (java.util.stream.Stream<Path> stream = Files.list(pluginPackagesDirectory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".package.json"))
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException exception) {
            throw new RuntimeException("Failed listing plugin package descriptors: " + pluginPackagesDirectory, exception);
        }
    }

    private static String adapterContributionKey(String capability, String operation, String adapterId) {
        return normalizeKey(capability) + "|" + normalizeKey(operation) + "|" + normalizeKey(adapterId);
    }

    private static String bindingKey(String capability, String adapterId) {
        return normalizeKey(capability) + "|" + normalizeKey(adapterId);
    }

    private static String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String stripJsonBom(String json) {
        if (json != null && !json.isEmpty() && json.charAt(0) == '\uFEFF') {
            return json.substring(1);
        }
        return json;
    }

    private static String prettyJson(JsonNode node) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node) + System.lineSeparator();
        } catch (IOException exception) {
            throw new RuntimeException("Failed writing generated JSON", exception);
        }
    }

    private static Path resolveProjectResourcePath(String folder, String fileName) {
        Path[] candidates = new Path[] {
                Paths.get("resources", folder, fileName),
                Paths.get("..", "resources", folder, fileName)
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return candidate.normalize();
            }
        }
        return null;
    }

    private void emitOptionalReactUiAssets() {
        emitOptionalReactUiFile(
                "npdev-templates/static-react/index.html",
                "src/main/resources/static/npdev-ui-react/index.html"
        );
        emitOptionalReactUiFile(
                "npdev-templates/static-react/assets/app.js",
                "src/main/resources/static/npdev-ui-react/assets/app.js"
        );
        emitOptionalReactUiFile(
                "npdev-templates/static-react/assets/app.css",
                "src/main/resources/static/npdev-ui-react/assets/app.css"
        );
    }

    private void emitOptionalReactUiFile(String classpathResource, String outputPath) {
        String content = readOptionalClasspathText(classpathResource);
        if (content == null || content.isBlank()) {
            return;
        }
        writer.writeRelative(outputPath, content);
    }

    private String readOptionalClasspathText(String classpathResource) {
        ClassLoader classLoader = RuntimeApiEmitter.class.getClassLoader();
        try (InputStream inputStream = classLoader.getResourceAsStream(classpathResource)) {
            if (inputStream == null) {
                return null;
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new RuntimeException("Failed reading classpath resource: " + classpathResource, exception);
        }
    }
}
