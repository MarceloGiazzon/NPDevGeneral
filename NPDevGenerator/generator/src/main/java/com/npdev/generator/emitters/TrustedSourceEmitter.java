package com.npdev.generator.emitters;

import com.npdev.dsl.v1.compiled.CompiledModel;
import com.npdev.generator.emitters.trustedsource.model.ManifestEntry;
import com.npdev.generator.emitters.trustedsource.model.TrustedFlow;
import com.npdev.generator.emitters.trustedsource.model.TrustedPanel;
import com.npdev.generator.emitters.trustedsource.model.TrustedProcedure;
import com.npdev.generator.emitters.trustedsource.model.TrustedReference;
import com.npdev.generator.emitters.trustedsource.model.TrustedWidget;
import com.npdev.generator.output.GeneratedSourceWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.npdev.generator.emitters.TrustedActionKernelRunnerTemplate.generatedActionKernelRunnerSource;
import static com.npdev.generator.emitters.TrustedActionRegistryTemplate.generatedActionRegistrySource;
import static com.npdev.generator.emitters.TrustedActionRegistryTemplate.packagedProcedureSource;
import static com.npdev.generator.emitters.TrustedActionSupportTemplates.generatedActionCapabilityAdapterSource;
import static com.npdev.generator.emitters.TrustedActionSupportTemplates.generatedActionCapabilityDispatcherFactorySource;
import static com.npdev.generator.emitters.TrustedActionSupportTemplates.generatedActionCapabilityRegistryContributorSource;
import static com.npdev.generator.emitters.TrustedActionSupportTemplates.generatedActionCapabilityRequestSource;
import static com.npdev.generator.emitters.TrustedActionSupportTemplates.generatedActionCapabilityResultSource;
import static com.npdev.generator.emitters.TrustedActionSupportTemplates.generatedActionDescriptorSource;
import static com.npdev.generator.emitters.TrustedActionSupportTemplates.generatedActionExecutionRequestSource;
import static com.npdev.generator.emitters.TrustedActionSupportTemplates.generatedActionExecutionResponseSource;
import static com.npdev.generator.emitters.TrustedActionSupportTemplates.procedureContextSource;
import static com.npdev.generator.emitters.TrustedFlowCodaRunnerTemplate.generatedFlowCodaRunnerSource;
import static com.npdev.generator.emitters.TrustedFlowSupportTemplates.generatedFlowDescriptorSource;
import static com.npdev.generator.emitters.TrustedFlowSupportTemplates.generatedFlowExecutionRequestSource;
import static com.npdev.generator.emitters.TrustedFlowSupportTemplates.generatedFlowExecutionResponseSource;
import static com.npdev.generator.emitters.TrustedFlowSupportTemplates.generatedFlowRegistrySource;
import static com.npdev.generator.emitters.TrustedSourceControllerTemplate.controllerSource;
import static com.npdev.generator.emitters.TrustedSourceManifest.generationManifest;
import static com.npdev.generator.emitters.TrustedSourceManifest.readManifest;
import static com.npdev.generator.emitters.TrustedSourceManifest.referencesFrom;
import static com.npdev.generator.emitters.TrustedSourceManifest.toPanel;
import static com.npdev.generator.emitters.TrustedSourceManifest.toProcedure;
import static com.npdev.generator.emitters.TrustedSourceManifest.toWidget;
import static com.npdev.generator.emitters.TrustedSourceManifest.trustedFlowsFrom;
import static com.npdev.generator.emitters.TrustedSourceManifest.validateHash;

public final class TrustedSourceEmitter {
    private static final String PACKAGE_PATH = "com/npdev/generated/trusted";

    private final GeneratedSourceWriter writer;

    public TrustedSourceEmitter(GeneratedSourceWriter writer) {
        this.writer = writer;
    }

    public void emit(CompiledModel model, Path modelSourcePath) throws IOException {
        List<TrustedReference> references = referencesFrom(model);
        if (references.isEmpty()) {
            return;
        }
        if (modelSourcePath == null || modelSourcePath.getParent() == null) {
            throw new IllegalStateException("Trusted source references require a model source path for sibling manifest discovery.");
        }

        Path sourceRoot = modelSourcePath.toAbsolutePath().normalize().getParent();
        Path manifestPath = sourceRoot.resolve("trusted-source-manifest.json").normalize();
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("Trusted source references require trusted-source-manifest.json next to the model.");
        }

        List<ManifestEntry> entries = readManifest(manifestPath, sourceRoot);
        Map<String, TrustedReference> referenceByKey = new LinkedHashMap<>();
        for (TrustedReference reference : references) {
            referenceByKey.put(key(reference.kind(), reference.relativePath()), reference);
        }

        Map<String, ManifestEntry> entryByKey = new LinkedHashMap<>();
        for (ManifestEntry entry : entries) {
            String key = key(entry.kind(), entry.relativePath());
            if (entryByKey.put(key, entry) != null) {
                throw new IllegalStateException("Duplicate trusted source manifest entry: " + key);
            }
            if (!referenceByKey.containsKey(key)) {
                throw new IllegalStateException("Unexpected trusted source manifest entry with no model reference: " + entry.relativePath());
            }
        }
        for (TrustedReference reference : references) {
            if (!entryByKey.containsKey(key(reference.kind(), reference.relativePath()))) {
                throw new IllegalStateException("Trusted source model reference has no manifest entry: " + reference.relativePath());
            }
        }

        List<TrustedProcedure> procedures = new ArrayList<>();
        List<TrustedPanel> panels = new ArrayList<>();
        List<TrustedWidget> widgets = new ArrayList<>();
        for (TrustedReference reference : references) {
            ManifestEntry entry = entryByKey.get(key(reference.kind(), reference.relativePath()));
            validateHash(sourceRoot, entry);
            if ("procedure".equals(reference.kind())) {
                procedures.add(toProcedure(reference, entry, sourceRoot));
            } else if ("panel".equals(reference.kind())) {
                panels.add(toPanel(reference, entry, sourceRoot));
            } else if ("widget".equals(reference.kind())) {
                widgets.add(toWidget(entry, sourceRoot));
            }
        }
        List<TrustedFlow> flows = trustedFlowsFrom(model);

        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/NPDevProcedureContext.java",
                procedureContextSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionDescriptor.java",
                generatedActionDescriptorSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionExecutionRequest.java",
                generatedActionExecutionRequestSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionExecutionResponse.java",
                generatedActionExecutionResponseSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionCapabilityRequest.java",
                generatedActionCapabilityRequestSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionCapabilityResult.java",
                generatedActionCapabilityResultSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionCapabilityAdapter.java",
                generatedActionCapabilityAdapterSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionCapabilityDispatcherFactory.java",
                generatedActionCapabilityDispatcherFactorySource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionCapabilityRegistryContributor.java",
                generatedActionCapabilityRegistryContributorSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionKernelRunner.java",
                generatedActionKernelRunnerSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedFlowDescriptor.java",
                generatedFlowDescriptorSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedFlowExecutionRequest.java",
                generatedFlowExecutionRequestSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedFlowExecutionResponse.java",
                generatedFlowExecutionResponseSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedFlowCodaRunner.java",
                generatedFlowCodaRunnerSource()
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedFlowRegistry.java",
                generatedFlowRegistrySource(flows)
        );
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedActionRegistry.java",
                generatedActionRegistrySource(procedures)
        );
        for (TrustedProcedure procedure : procedures) {
            writer.writeRelative(
                    "src/main/java/" + PACKAGE_PATH + "/" + procedure.className() + ".java",
                    packagedProcedureSource(procedure)
            );
        }
        for (TrustedPanel panel : panels) {
            writer.writeRelative(
                    "src/main/resources/trusted-source/panel/" + panel.resourceName(),
                    panel.source()
            );
            writer.writeRelative(
                    "src/main/resources/trusted-source/panel/" + panel.cssResourceName(),
                    panel.cssSource()
            );
            writer.writeRelative(
                    "src/main/resources/trusted-source/panel/" + panel.jsResourceName(),
                    panel.jsSource()
            );
        }
        for (TrustedWidget widget : widgets) {
            writer.writeRelative(
                    "src/main/resources/trusted-source/widget/" + widget.relativePath(),
                    widget.source()
            );
        }
        writer.writeRelative(
                "src/main/java/" + PACKAGE_PATH + "/GeneratedTrustedSourceRuntimeController.java",
                controllerSource(procedures, panels, widgets, flows)
        );
        writer.writeRelative(
                "src/main/resources/trusted-source/trusted-source-generation-manifest.json",
                generationManifest(entries, procedures, panels, widgets, manifestPath)
        );
    }

    private static String key(String kind, String relativePath) {
        return kind + "::" + relativePath.replace('\\', '/');
    }
}
