package com.npdev.generator.emitters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R10: covers {@link GeneratedPluginMountPlan}'s new plugin:java-controller mount kind -- discovery
 * (via {@link com.npdev.dsl.v1.compiled.CompiledPluginRequirementGraphBuilder}'s synthesized
 * requirement, since no flow ever calls a mounted controller), descriptor validation, and the two
 * generation-time refusals D9/the 4th enforcement point depend on: a missing
 * {@code security.minimumRole} and a controller class outside the reserved
 * {@code com.npdev.generated.plugin.} package must both fail GENERATION, not merely fail silently at
 * runtime. Neither {@code GeneratedPluginMountPlan} nor its existing JAVA_SOURCE sibling had any unit
 * coverage before this -- the mechanism's only prior cover was the live lib-probe end-to-end proof.
 */
final class GeneratedPluginMountPlanJavaControllerTest {

    @TempDir
    Path artifactRoot;

    @Test
    void mountsAValidJavaControllerDescriptor() throws Exception {
        Path modelPath = writeModel("adminTools", "AdminToolsController");
        writeDescriptor("adminTools", "AdminToolsController",
                "com.npdev.generated.plugin.admintools.AdminToolsController",
                "/api/plugins/admin-tools", "ADMIN");
        writeControllerSource("adminTools", "com.npdev.generated.plugin.admintools", "AdminToolsController");

        GeneratedPluginMountPlan plan = GeneratedPluginMountPlan.fromModelSource(modelPath);

        assertEquals(1, plan.javaControllerMounts().size());
        GeneratedPluginMountPlan.Mount mount = plan.javaControllerMounts().get(0);
        assertEquals(GeneratedPluginMountPlan.MountKind.JAVA_CONTROLLER, mount.mountKind());
        assertEquals("/api/plugins/admin-tools", mount.controllerBasePath());
        assertEquals("ADMIN", mount.controllerMinimumRole());
        assertEquals("AdminToolsController", mount.controllerSimpleClassName());
        assertEquals("com.npdev.generated.plugin.admintools.AdminToolsController", mount.controllerClassName());

        // A controller mount MUST still appear in packageGroups() -- NpdevCapabilityBindingConfig
        // .capabilityRegistry() eagerly resolves every declared binding at boot, controller-bound or
        // not (a real boot proved this; see GeneratedPluginMountPlan.packageGroups()' javadoc), so
        // omitting it here would fail every generated app with a plugin controller at startup.
        assertEquals(1, plan.packageGroups().size());
        assertEquals(GeneratedPluginMountPlan.GENERIC_MOUNTED_RUNTIME_REF, mount.runtimeRef());
    }

    @Test
    void refusesGenerationWhenMinimumRoleIsMissing() throws Exception {
        Path modelPath = writeModel("adminTools", "AdminToolsController");
        writeDescriptorMissingSecurity("adminTools", "AdminToolsController",
                "com.npdev.generated.plugin.admintools.AdminToolsController",
                "/api/plugins/admin-tools");
        writeControllerSource("adminTools", "com.npdev.generated.plugin.admintools", "AdminToolsController");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> GeneratedPluginMountPlan.fromModelSource(modelPath));
        assertTrue(failure.getCause().getMessage().contains("minimumRole"), failure.getCause().getMessage());
    }

    @Test
    void refusesGenerationWhenControllerClassIsOutsideTheReservedPackage() throws Exception {
        Path modelPath = writeModel("adminTools", "AdminToolsController");
        writeDescriptor("adminTools", "AdminToolsController",
                "com.finalexec.api.AdminToolsController",
                "/api/plugins/admin-tools", "ADMIN");
        Path badSource = artifactRoot.resolve("capabilities/adminTools/src/main/java/com/finalexec/api");
        Files.createDirectories(badSource);
        Files.writeString(badSource.resolve("AdminToolsController.java"), """
                package com.finalexec.api;
                public class AdminToolsController {}
                """);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> GeneratedPluginMountPlan.fromModelSource(modelPath));
        assertTrue(failure.getCause().getMessage().contains("com.npdev.generated.plugin."), failure.getCause().getMessage());
    }

    @Test
    void refusesGenerationWhenBasePathIsOutsideTheReservedPrefix() throws Exception {
        Path modelPath = writeModel("adminTools", "AdminToolsController");
        writeDescriptor("adminTools", "AdminToolsController",
                "com.npdev.generated.plugin.admintools.AdminToolsController",
                "/api/agent-proxy/admin-tools", "ADMIN");
        writeControllerSource("adminTools", "com.npdev.generated.plugin.admintools", "AdminToolsController");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> GeneratedPluginMountPlan.fromModelSource(modelPath));
        assertTrue(failure.getCause().getMessage().contains("/api/plugins/"), failure.getCause().getMessage());
    }

    private Path writeModel(String capabilityName, String capabilityType) throws Exception {
        Path modelPath = artifactRoot.resolve("model.json");
        Files.writeString(modelPath, """
                {
                  "namespace": "demo",
                  "dslVersion": "1.0.0",
                  "version": "1.0",
                  "concepts": [
                    { "name": "Trigger", "fields": [ { "name": "id", "type": "uuid", "id": true, "required": true } ] }
                  ],
                  "customCapabilities": [
                    { "name": "%s", "type": "%s" }
                  ],
                  "bindings": [
                    { "capability": "%s", "adapter": "plugin:java-controller" }
                  ]
                }
                """.formatted(capabilityName, capabilityType, capabilityName));
        return modelPath;
    }

    private void writeDescriptor(
            String capabilityName, String capabilityType, String controllerClass, String basePath, String minimumRole
    ) throws Exception {
        Path descriptorDir = artifactRoot.resolve("capabilities").resolve(capabilityName);
        Files.createDirectories(descriptorDir);
        Files.writeString(descriptorDir.resolve("capability.plugin.json"), """
                {
                  "packageId": "user-%1$s-package",
                  "pluginId": "user-%1$s-plugin",
                  "displayName": "%1$s test plugin",
                  "version": "1.0.0",
                  "capability": "%1$s",
                  "capabilityType": "%2$s",
                  "adapterId": "plugin:java-controller",
                  "implementation": {
                    "kind": "javaController",
                    "sourceRoot": "capabilities/%1$s/src/main/java",
                    "controllerClass": "%3$s"
                  },
                  "mount": {
                    "basePath": "%4$s",
                    "security": { "minimumRole": "%5$s" }
                  }
                }
                """.formatted(capabilityName, capabilityType, controllerClass, basePath, minimumRole));
    }

    private void writeDescriptorMissingSecurity(
            String capabilityName, String capabilityType, String controllerClass, String basePath
    ) throws Exception {
        Path descriptorDir = artifactRoot.resolve("capabilities").resolve(capabilityName);
        Files.createDirectories(descriptorDir);
        Files.writeString(descriptorDir.resolve("capability.plugin.json"), """
                {
                  "packageId": "user-%1$s-package",
                  "pluginId": "user-%1$s-plugin",
                  "displayName": "%1$s test plugin",
                  "version": "1.0.0",
                  "capability": "%1$s",
                  "capabilityType": "%2$s",
                  "adapterId": "plugin:java-controller",
                  "implementation": {
                    "kind": "javaController",
                    "sourceRoot": "capabilities/%1$s/src/main/java",
                    "controllerClass": "%3$s"
                  },
                  "mount": {
                    "basePath": "%4$s"
                  }
                }
                """.formatted(capabilityName, capabilityType, controllerClass, basePath));
    }

    private void writeControllerSource(String capabilityName, String packageName, String simpleClassName) throws Exception {
        Path sourceDir = artifactRoot.resolve("capabilities").resolve(capabilityName)
                .resolve("src/main/java").resolve(packageName.replace('.', '/'));
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve(simpleClassName + ".java"), """
                package %s;
                public class %s {}
                """.formatted(packageName, simpleClassName));
    }
}
