package com.finalexec.npdev.service;

import java.util.List;
import java.util.Map;

public interface RuntimePluginExecutionSummaryStore {

    void append(SandboxedPluginExecutionResult.Summary summary);

    List<SandboxedPluginExecutionResult.Summary> recent(int limit);

    Map<String, Object> diagnostics();
}
