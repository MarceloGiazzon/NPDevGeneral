package com.npdev.dsl.v1.ast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** LNCH-12: a flow's optional recurring-execution declaration (cron expression + tenant scope). */
public final class FlowScheduleAst {
    private final String cron;
    private final List<String> tenantScope;

    public FlowScheduleAst(String cron, List<String> tenantScope) {
        this.cron = cron;
        this.tenantScope = tenantScope == null ? List.of() : new ArrayList<>(tenantScope);
    }

    public String getCron() {
        return cron;
    }

    public List<String> getTenantScope() {
        return Collections.unmodifiableList(tenantScope);
    }
}
