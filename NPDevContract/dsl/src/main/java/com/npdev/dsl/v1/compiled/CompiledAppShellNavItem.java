package com.npdev.dsl.v1.compiled;

/**
 * Path A P6.3: compiled form of {@code AppShellNavItemAst}. {@code target} is optional -- an entry
 * with none is a group-header-only row with no navigation target of its own.
 */
public final class CompiledAppShellNavItem {
    private final String label;
    private final String target;
    private final String group;

    public CompiledAppShellNavItem(String label, String target, String group) {
        this.label = label;
        this.target = target;
        this.group = group;
    }

    public String getLabel() { return label; }
    public String getTarget() { return target; }
    public String getGroup() { return group; }
}
