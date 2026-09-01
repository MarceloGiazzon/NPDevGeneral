package com.finalexec.pluginipc;

import com.finalexec.npdev.service.pluginipc.PluginIpcCallbackClient;
import com.npdev.kernel.CapabilityCall;
import com.npdev.kernel.CapabilityResult;
import com.npdev.kernel.ports.CapabilityAdapter;

import java.util.Map;

/**
 * Real-process stand-in for a plugin that crashes its own process outright -- design section 4's
 * "a plugin that actually calls {@code System.exit()} and does NOT take down the host process" done-when
 * bar. {@code System.exit} here only terminates this child JVM; it cannot reach the separate host JVM.
 */
public final class SystemExitPluginHandler implements CapabilityAdapter {

    public SystemExitPluginHandler(PluginIpcCallbackClient callbackClient) {
        // Unused: this handler crashes before it would ever call back into the host.
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
        System.exit(1);
        throw new AssertionError("unreachable: System.exit does not return");
    }
}
