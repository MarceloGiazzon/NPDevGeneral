package com.npdev.dsl.v1.compiled;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Path A P6.3: compiled form of {@code AppShellAst} -- declared navigation structure and default
 * route for the generated app's shell chrome. {@code null} on {@link CompiledModel#getAppShell()}
 * when the model declares no {@code appShell} block (unlike {@code CompiledSettings}, there is no
 * meaningful "platform default" appShell to synthesize, so this is nullable rather than
 * always-populated). Structural plumbing only -- not yet consumed by the generator.
 */
public final class CompiledAppShell {
    private final String defaultRoute;
    private final List<CompiledAppShellNavItem> navigation;

    public CompiledAppShell(String defaultRoute, List<CompiledAppShellNavItem> navigation) {
        this.defaultRoute = defaultRoute;
        this.navigation = navigation == null ? new ArrayList<>() : new ArrayList<>(navigation);
    }

    public String getDefaultRoute() { return defaultRoute; }

    public List<CompiledAppShellNavItem> getNavigation() {
        return Collections.unmodifiableList(navigation);
    }
}
