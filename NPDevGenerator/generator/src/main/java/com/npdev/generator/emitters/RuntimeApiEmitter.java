package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.dsl.v1.compiled.CompiledMetadataCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledModelCanonicalJson;
import com.npdev.dsl.v1.compiled.CompiledConcept;
import com.npdev.dsl.v1.compiled.CompiledField;
import com.npdev.dsl.v1.compiled.CompiledFlow;
import com.npdev.dsl.v1.compiled.CompiledFlowStep;
import com.npdev.dsl.v1.compiled.CompiledOrchestration;
import com.npdev.dsl.v1.compiled.CompiledOrchestrationAction;
import com.npdev.dsl.v1.compiled.CompiledPresentationMetadata;
import com.npdev.generator.output.GeneratedSourceWriter;
import com.npdev.generator.templates.TemplateEngine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class RuntimeApiEmitter extends AbstractEmitter {

    public RuntimeApiEmitter(TemplateEngine templates, GeneratedSourceWriter writer) {
        super(templates, writer);
    }

    public void emit(CompiledModel model, Path modelSourcePath) {
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

        if (modelSourcePath != null && Files.exists(modelSourcePath)) {
            writer.writeRelative("src/main/resources/npdev/model.json", readModelSource(modelSourcePath));
        }
        writer.writeRelative(
                "src/main/resources/npdev/compiled-model.json",
                CompiledModelCanonicalJson.toJson(model)
        );
        writer.writeRelative(
                "src/main/resources/npdev/compiled-metadata.json",
                CompiledMetadataCanonicalJson.toJson(modelSourcePath, model)
        );

        writer.writeRelative(
                "src/main/resources/npdev/bindings/dev.bindings.json",
                readBindingManifest("dev.bindings.json")
        );
        writer.writeRelative(
                "src/main/resources/npdev/bindings/alt.bindings.json",
                readBindingManifest("alt.bindings.json")
        );
        writer.writeRelative(
                "src/main/resources/npdev/security/dev.permissions.json",
                generatePermissionManifest(model)
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
                readPluginManifest("default.plugin-manifest.json")
        );
        writer.writeRelative(
                "src/main/resources/npdev/plugins/warning.plugin-manifest.json",
                readPluginManifest("warning.plugin-manifest.json")
        );
        for (String fileName : listPluginPackageDescriptorFiles()) {
            writer.writeRelative(
                    "src/main/resources/npdev/plugin-packages/" + fileName,
                    readPluginPackageDescriptor(fileName)
            );
        }
        writer.writeRelative(
                "src/main/resources/npdev/plugin-packages/index.json",
                readPluginPackageIndex()
        );
    }

    private static String readModelSource(Path modelSourcePath) {
        try {
            return Files.readString(modelSourcePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed reading model source: " + modelSourcePath, e);
        }
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

    private static String generatePermissionManifest(CompiledModel model) {
        Set<String> permissions = new LinkedHashSet<>();
        permissions.add("flow.execute");
        permissions.add("capability.invoke");
        permissions.add("event.publish");

        for (CompiledFlow flow : model.getFlows()) {
            addIfPresent(permissions, flow.getAction() == null ? null : flow.getAction().getPermissionHint());
            collectStepPermissionHints(flow.getSteps(), permissions);
        }
        for (CompiledOrchestration orchestration : model.getOrchestrationRules()) {
            for (CompiledOrchestrationAction action : orchestration.getActions()) {
                addIfPresent(permissions, action.getAction() == null ? null : action.getAction().getPermissionHint());
            }
        }

        String grants = permissions.stream()
                .map(permission -> """
    {
      "permission": "%s",
      "tenantId": "dev",
      "actorId": "",
      "role": "admin"
    }""".formatted(jsonEscape(permission)))
                .collect(Collectors.joining("," + System.lineSeparator()));

        return """
{
  "grants": [
%s
  ]
}
""".formatted(grants);
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

    private static String readPluginPackageIndex() {
        String resources = listPluginPackageDescriptorFiles().stream()
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
