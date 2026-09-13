package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Path A P6.3: declared navigation structure and default route for the generated app's shell chrome
 * (see docs/UI_CONTRACT.md's "Shell versioning" -- the browser shell this closes a chrome gap for).
 * A model that declares no {@code appShell} block at all keeps the shell's existing default behavior
 * unchanged (see {@link ModelAst#getAppShell()}, which returns {@code null} in that case).
 *
 * <p>W1.1 (NPDEV_ROADMAP_2026-09-12.md Wave 1): consumed by BusinessUiEmitter (threaded into
 * {@code generated-ui-manifest.json}) and, client-side, by business-ui-app.mustache's
 * {@code deriveNativeGroups}/bootstrap default-route logic. Both {@code defaultRoute} and every
 * nav item's {@code target} are validated against a real concept/Panel name by
 * {@code PanelValidation#validateAppShell}, called from {@code SemanticValidator}.</p>
 */
public final class AppShellAst {
    private final String defaultRoute;
    private final List<AppShellNavItemAst> navigation;

    public AppShellAst(String defaultRoute, List<AppShellNavItemAst> navigation) {
        this.defaultRoute = defaultRoute;
        this.navigation = navigation == null ? new ArrayList<>() : new ArrayList<>(navigation);
    }

    public String getDefaultRoute() { return defaultRoute; }

    public List<AppShellNavItemAst> getNavigation() {
        return Collections.unmodifiableList(navigation);
    }
}
