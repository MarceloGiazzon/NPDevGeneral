package com.finalexec.pluginipc;

import com.finalexec.npdev.service.pluginipc.PluginIpcCallbackClient;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.util.Map;

/**
 * Real-process stand-in for a generated auditLog plugin handler: calls back into its own declared
 * capability. Must be a public top-level class with a public {@code (PluginIpcCallbackClient)}
 * constructor -- {@link com.finalexec.npdev.service.pluginipc.PluginIpcChildProcessMain} instantiates it
 * by reflection inside a separate {@code java} process, so a lambda (as the step-1 in-process prototype
 * used) cannot stand in here.
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
