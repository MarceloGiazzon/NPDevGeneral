package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Path A P6.3: declared navigation structure and default route for the generated app's shell chrome
 * (see docs/UI_CONTRACT.md's "Shell versioning" -- the browser shell this closes a chrome gap for).
 * A model that declares no {@code appShell} block at all keeps the shell's existing default behavior
 * unchanged (see {@link ModelAst#getAppShell()}, which returns {@code null} in that case) -- this is
 * structural plumbing only, not yet consumed by the generator, and neither {@code defaultRoute} nor
 * a nav item's {@code target} is validated against an actual concept/panel name here.
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
