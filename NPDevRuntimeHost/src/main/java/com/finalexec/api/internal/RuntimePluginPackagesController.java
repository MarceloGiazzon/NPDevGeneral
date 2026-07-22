package com.finalexec.api.internal;

import com.finalexec.api.*;

import com.finalexec.npdev.service.RuntimePluginPackageCatalog;
import com.finalexec.npdev.service.RuntimePluginPackageRealizationService;
import com.finalexec.npdev.service.RuntimePluginProfileDiagnostics;
import com.npdev.generated.runtime.service.RuntimeContextService;
import com.npdev.kernel.ExecutionContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/api/v1/admin/runtime/plugin-packages", "/api/admin/runtime/plugin-packages"})
public class RuntimePluginPackagesController {

    private final RuntimePluginPackageCatalog runtimePluginPackageCatalog;
    private final RuntimePluginPackageRealizationService runtimePluginPackageRealizationService;
    private final RuntimePluginProfileDiagnostics runtimePluginProfileDiagnostics;
    private final RuntimeContextService runtimeContextService;

    public RuntimePluginPackagesController(
            RuntimePluginPackageCatalog runtimePluginPackageCatalog,
            RuntimePluginPackageRealizationService runtimePluginPackageRealizationService,
            RuntimePluginProfileDiagnostics runtimePluginProfileDiagnostics,
            RuntimeContextService runtimeContextService
    ) {
        this.runtimePluginPackageCatalog = runtimePluginPackageCatalog;
        this.runtimePluginPackageRealizationService = runtimePluginPackageRealizationService;
        this.runtimePluginProfileDiagnostics = runtimePluginProfileDiagnostics;
        this.runtimeContextService = runtimeContextService;
    }

    @GetMapping
    public Map<String, Object> getPluginPackages(HttpServletRequest request) {
        requireAdminContext(request);
        RuntimePluginPackageCatalog.Summary summary =
                runtimePluginPackageCatalog.toSummary(runtimePluginProfileDiagnostics.pluginManifestPath());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("discoveryMode", summary.discoveryMode());
        response.put("discoveryLocation", summary.discoveryLocation());
        response.put("indexResourcePath", summary.indexResourcePath());
        response.put("activePluginManifestPath", summary.activePluginManifestPath());
        response.put("runtimeCompatibility", summary.runtimeCompatibility());
        response.put("trustPolicy", summary.trustPolicy());
        response.put("governance", summary.governance());
        response.put("discoveredPackageIds", summary.discoveredPackageIds());
        response.put("admittedPackageIds", summary.admittedPackageIds());
        response.put("admittedPackages", summary.packages().stream()
                .filter(RuntimePluginPackageCatalog.PackageSummary::admitted)
                .toList());
        response.put("rejectedPackageIds", summary.rejectedPackageIds());
        response.put("selectedPackageIds", summary.selectedPackageIds());
        response.put("rejectedPackages", summary.rejectedPackages());
        response.put("packages", summary.packages());
        response.put("realization", runtimePluginPackageRealizationService.toSummary());
        response.put("discoveredPackageCount", summary.discoveredPackageIds().size());
        response.put("admittedPackageCount", summary.admittedPackageIds().size());
        response.put("rejectedPackageCount", summary.rejectedPackageIds().size());
        response.put("selectedPackageCount", summary.selectedPackageIds().size());
        response.put("packageCount", summary.packages().size());
        return response;
    }

    private void requireAdminContext(HttpServletRequest request) {
        ExecutionContext context = runtimeContextService.currentContext(request);
        if (!context.hasRole("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }
}
