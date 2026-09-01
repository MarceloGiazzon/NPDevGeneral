package com.finalexec.npdev.service.pluginipc;

import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.util.Map;

/**
 * Real-process stand-in for a generated auditLog plugin handler: calls back into its own declared
 * capability. Must be a public top-level class with a public {@code (PluginIpcCallbackClient)}
 * constructor -- {@link PluginIpcChildProcessMain} instantiates it
 * by reflection inside a separate {@code java} process, so a lambda (as the step-1 in-process prototype
 * used) cannot stand in here.
 *
 * <p>Twin of NPDevRuntimeHost/src/test/java/com/finalexec/pluginipc/AuditLogEchoPluginHandler.java --
 * see MemoryHogPluginHandler's own javadoc in this package for why the duplication exists and how it is
 * kept in sync (twin-pair rule plugin-linux-proof-fixture-duplication). SEC-3 fork (a), 2026-09-01.</p>
 */
public final class AuditLogEchoPluginHandler implements CapabilityAdapter {

    private final PluginIpcCallbackClient callbackClient;

    public AuditLogEchoPluginHandler(PluginIpcCallbackClient callbackClient) {
        this.callbackClient = callbackClient;
    }

    @Override
    public String adapterId() {
        return "auditlog-inproc";
    }

    @Override
    public String capability() {
        return "auditLog";
    }

    @Override
    public CapabilityResult invoke(CapabilityCall call, Map<String, Object> contextState) {
        return callbackClient.callBack("auditLog", "append", call.args());
    }
}
