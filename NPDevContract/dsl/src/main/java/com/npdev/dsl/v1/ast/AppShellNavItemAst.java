package com.npdev.dsl.v1.ast;

/**
 * Path A P6.3: a single declared navigation entry under {@code appShell.navigation}. {@code target}
 * is optional -- an entry with none is a group-header-only row with no navigation target of its own.
 * Neither {@code target} nor {@code group} is validated against an actual concept/panel name here;
 * that is a follow-up (see AppShellAst's own javadoc), not part of this structural plumbing.
 */
public final class AppShellNavItemAst {
    private final String label;
    private final String target;
    private final String group;

    public AppShellNavItemAst(String label, String target, String group) {
        this.label = label;
        this.target = target;
        this.group = group;
    }

    public String getLabel() { return label; }
    public String getTarget() { return target; }
    public String getGroup() { return group; }
}
